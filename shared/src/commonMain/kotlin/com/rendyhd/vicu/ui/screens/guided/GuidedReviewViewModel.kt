package com.rendyhd.vicu.ui.screens.guided

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.organize.NextStep
import com.rendyhd.vicu.ai.organize.OrganizeSuggester
import com.rendyhd.vicu.ai.organize.OrganizeSession
import com.rendyhd.vicu.ai.organize.Suggestion
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.screens.shared.ProjectActionResult
import com.rendyhd.vicu.ui.screens.shared.ProjectActions
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.Guided
import com.rendyhd.vicu.util.GuidedWorld
import com.rendyhd.vicu.util.ProjectHealth
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.sortProjectTasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An untouched task on a review page, with what the session did to it ([note], null while open). */
data class UntouchedRow(val task: Task, val untouchedDays: Int?, val suggestion: Suggestion?, val note: String?)

data class GuidedReviewUiState(
    val loading: Boolean = true,
    val total: Int = 0,
    val index: Int = 0,
    val project: Project? = null,
    val path: String = "",
    /** "Reviewed 41 days ago" or "Not reviewed yet". */
    val lastReviewed: String = "",
    val health: ProjectHealth? = null,
    val canArchive: Boolean = false,
    val untouched: List<UntouchedRow> = emptyList(),
    /** The next step: the model's pick, else the first open task in the project's order. */
    val nextTask: Task? = null,
    val nextSuggested: Boolean = false,
    /** The model's choice of date for [nextTask], marked as suggested until the person picks one. */
    val nextDue: DueChoice? = null,
    /** The model's title for a new task when no open task fits. */
    val newStepTitle: String? = null,
    val suggesting: Boolean = false,
    val canGoBack: Boolean = false,
    val busy: Boolean = false,
    val confirmDelete: Boolean = true,
    val finished: Boolean = false,
    val summary: String = "",
    val taskSummary: String = "",
    val canUndo: Boolean = false,
)

/**
 * The project review session (docs/cross-app-semantics-v1.md, section 14.4): one page per project
 * due for review, with its health line, Keep or Archive, its untouched tasks, the next step, and
 * Mark reviewed or Skip. Changes on a page are written at once; Undo all at the end puts back every
 * one but deletes, the reviews and archives too.
 */
class GuidedReviewViewModel(
    private val source: GuidedWorldSource,
    private val writer: GuidedWriter,
    private val projectActions: ProjectActions,
    private val organize: OrganizeSuggester,
    private val behaviorPrefs: BehaviorPrefsStore,
    private val progress: GuidedProgress,
    private val messages: AppMessages,
    private val dayClock: DayClock,
) : ViewModel() {
    private val _uiState = MutableStateFlow(GuidedReviewUiState())
    val uiState: StateFlow<GuidedReviewUiState> = _uiState.asStateFlow()

    private var ids: List<Long> = emptyList()
    private var world: GuidedWorld? = null
    private val log = GuidedLog()

    /** What happened to each task on the pages, for its row ("Scheduled", "Completed"). */
    private val notes = HashMap<Long, String>()

    /** The untouched tasks of the page shown, fixed when the page opens so a handled one keeps its row. */
    private var pageUntouched: List<Long> = emptyList()

    /** Projects as they were before the session reviewed or archived them, the first time. */
    private val reviewedBefore = LinkedHashMap<Long, Project>()
    private val archivedBefore = LinkedHashMap<Long, Project>()
    private val created = mutableListOf<Long>()

    private val suggestions = HashMap<Long, Suggestion>()
    private val nextSteps = HashMap<Long, NextStep?>()
    private var suggestionsOn = false
    private var request: Job? = null
    private val writing = Mutex()

    init {
        viewModelScope.launch {
            suggestionsOn = organize.isReady()
            val now = source.now()
            ids = Guided.reviewQueue(now)
            world = now
            _uiState.update { it.copy(loading = false, total = ids.size) }
            show(0)
            source.world.collect { w ->
                world = w
                refreshPage()
            }
        }
        viewModelScope.launch {
            behaviorPrefs.getPrefs().collect { prefs -> _uiState.update { it.copy(confirmDelete = prefs.confirmBeforeDelete) } }
        }
    }

    private fun projectAt(index: Int): Project? = ids.getOrNull(index)?.let { id -> world?.projectById?.get(id) }

    /** A page can be shown: the project is still there, not archived, and can be changed. */
    private fun showable(index: Int): Boolean = projectAt(index)?.let { world?.canChange(it.id) == true } == true

    private fun show(index: Int) {
        var i = index
        while (i < ids.size && !showable(i)) i++
        if (i >= ids.size) return finish()
        val w = world ?: return
        val projectId = ids[i]
        pageUntouched = Guided.health(w, projectId).untouchedIds
        _uiState.update { it.copy(index = i, canGoBack = (0 until i).any(::showable)) }
        refreshPage()
        askForSuggestions(projectId)
    }

    private fun refreshPage() {
        val w = world ?: return
        val state = _uiState.value
        if (state.loading || state.finished) return
        val project = projectAt(state.index) ?: return
        val health = Guided.health(w, project.id)
        val status = ReviewMetadata.computeStatus(ReviewMetadata.parse(project.description), w.reviewCadenceDays, w.today)
        val byId = w.tasks.associateBy { it.id }
        val untouched = pageUntouched.mapNotNull { id ->
            val task = byId[id]
            val note = notes[id]
            when {
                task == null || task.done -> note?.let { UntouchedRow(task ?: Task(id = id, title = ""), null, null, it) }
                else -> UntouchedRow(task, w.untouchedDays(task), suggestions[id], note)
            }
        }
        val next = nextSteps[project.id]
        val modelTask = next?.taskId?.let { byId[it] }?.takeIf { !it.done }
        val nextTask = modelTask ?: health.firstOpen?.let { byId[it] }
        _uiState.update {
            it.copy(
                project = project,
                path = w.path(project.id),
                lastReviewed = status.daysSinceReviewed?.let { days -> "Reviewed ${Guided.ago(days.toInt())}" } ?: "Not reviewed yet",
                health = health,
                canArchive = project.id != w.inboxId,
                untouched = untouched,
                nextTask = nextTask,
                nextSuggested = modelTask != null,
                nextDue = if (modelTask != null && modelTask.id !in notes) next.due?.let { d -> DueChoice.On(d, next.dueHasTime) } else null,
                newStepTitle = next?.takeIf { n -> n.taskId == null }?.title,
                suggesting = suggestionsOn && project.id !in nextSteps,
            )
        }
    }

    // --- Suggestions (ai-assistant 9.1: one request per page) ----------------------------------

    private fun askForSuggestions(projectId: Long) {
        val w = world ?: return
        if (!suggestionsOn || projectId in nextSteps) return
        val open = w.tasks.filter { it.projectId == projectId && !it.done }
        val untouched = pageUntouched.filter { id -> open.any { it.id == id } }
        val rest = sortProjectTasks(open.filter { it.id !in untouched }, w.order.orderFor(projectId, w.projects)).map { it.id }
        val batch = (untouched + rest).take(AiContract.limits.organizeBatchMax)
        if (batch.isEmpty()) {
            nextSteps[projectId] = null
            return
        }
        request?.cancel()
        request = viewModelScope.launch {
            try {
                val result = organize.suggest(OrganizeSession.REVIEW, batch, w.path(projectId), defaultProjectId = projectId)
                result.suggestions.forEach { suggestions[it.taskId] = it }
                nextSteps[projectId] = result.nextStep
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (suggestionsOn) messages.post("No suggestions: " + (e.message ?: "the model did not answer."))
                suggestionsOn = false
            }
            refreshPage()
        }
    }

    // --- Changes on the page -----------------------------------------------------------------

    /** A new date (or none) for [task]: an untouched one or the next step. */
    fun schedule(task: Task, due: DueChoice?) {
        val change = CardDraft(due = due).changeOf(task) ?: return
        val note = if (due == DueChoice.Clear) "No date now" else "Scheduled"
        act { if (writer.write(change)) done(task.id, note) { log.changed(change, dayClock.day.value.zone) } else failed(task, "change") }
    }

    fun complete(task: Task) = act {
        if (writer.complete(task)) done(task.id, "Completed") { log.completed(task) } else failed(task, "complete")
    }

    fun delete(task: Task) = act {
        if (writer.delete(task)) done(task.id, "Deleted") { log.deleted(task) } else failed(task, "delete")
    }

    /** A new task in the project, the next step. */
    fun addStep(title: String) {
        val project = _uiState.value.project ?: return
        val name = title.trim().takeIf { it.isNotEmpty() } ?: return
        act {
            val task = writer.create(name, project.id)
            if (task == null) {
                messages.post("Could not add \"$name\"")
            } else {
                created += task.id
                nextSteps[project.id] = NextStep(task.id, null, null, false)
                messages.post("Added \"$name\" to ${project.title}")
            }
        }
    }

    private fun done(taskId: Long, note: String, record: () -> Unit) {
        record()
        notes[taskId] = note
    }

    private fun failed(task: Task, what: String) = messages.post("Could not $what \"${task.title}\"")

    /** Archive: the project goes, without being marked reviewed (section 14.4). */
    fun archive() {
        val project = _uiState.value.project ?: return
        act {
            when (val result = projectActions.archive(project)) {
                is ProjectActionResult.Failed -> messages.post(result.error)
                is ProjectActionResult.Done -> {
                    archivedBefore.getOrPut(project.id) { project }
                    goOn()
                }
            }
        }
    }

    fun markReviewed() {
        val project = _uiState.value.project ?: return
        act {
            when (val result = projectActions.markReviewed(project)) {
                is ProjectActionResult.Failed -> messages.post(result.error)
                is ProjectActionResult.Done -> {
                    reviewedBefore.getOrPut(project.id) { project }
                    goOn()
                }
            }
        }
    }

    fun skip() {
        if (_uiState.value.busy) return
        show(_uiState.value.index + 1)
    }

    fun back() {
        val index = _uiState.value.index
        val previous = (index - 1 downTo 0).firstOrNull(::showable) ?: return
        show(previous)
    }

    private var pendingPage: Int? = null

    private fun goOn() {
        pendingPage = _uiState.value.index + 1
    }

    private fun act(write: suspend () -> Unit) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            writing.withLock { write() }
            world = source.world.first()
            _uiState.update { it.copy(busy = false) }
            val page = pendingPage
            pendingPage = null
            if (page != null) show(page) else refreshPage()
        }
    }

    private fun finish() {
        progress.finished(Guided.STEP_REVIEW)
        val projects = listOfNotNull(
            "${reviewedBefore.size} reviewed".takeIf { reviewedBefore.isNotEmpty() },
            "${archivedBefore.size} archived".takeIf { archivedBefore.isNotEmpty() },
            "${created.size} ${if (created.size == 1) "task" else "tasks"} added".takeIf { created.isNotEmpty() },
        ).joinToString(", ")
        _uiState.update {
            it.copy(
                finished = true,
                project = null,
                summary = if (projects.isEmpty()) "No project was reviewed" else "Projects: $projects",
                taskSummary = "Tasks: " + log.counts().text,
                canUndo = log.all.any { e -> !e.deleted } || reviewedBefore.isNotEmpty() || archivedBefore.isNotEmpty() || created.isNotEmpty(),
            )
        }
    }

    /** Undo all: the task changes but deletes, the tasks added, the reviews and the archives. */
    fun undoAll() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            writing.withLock {
                writer.undo(log)
                for (id in created) writer.deleteCreated(id)
                for (previous in reviewedBefore.values) projectActions.undoReview(previous)
                for (previous in archivedBefore.values) {
                    val now = source.world.first().projectById[previous.id] ?: continue
                    projectActions.restore(now)
                }
            }
            log.clear()
            created.clear()
            reviewedBefore.clear()
            archivedBefore.clear()
            _uiState.update { it.copy(busy = false, canUndo = false, summary = "Everything is back as it was", taskSummary = "") }
        }
    }
}
