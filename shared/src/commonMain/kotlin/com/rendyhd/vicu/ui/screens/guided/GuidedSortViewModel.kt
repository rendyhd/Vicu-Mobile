package com.rendyhd.vicu.ui.screens.guided

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.organize.OrganizeSuggester
import com.rendyhd.vicu.ai.organize.OrganizeSession
import com.rendyhd.vicu.ai.organize.Suggestion
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.Guided
import com.rendyhd.vicu.util.GuidedQueue
import com.rendyhd.vicu.util.GuidedWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A project the card can move a task to, with its path. */
data class GuidedProject(val id: Long, val path: String)

data class GuidedSortUiState(
    val queue: GuidedQueue,
    val loading: Boolean = true,
    /** The cards of the session; [index] is the one shown. */
    val total: Int = 0,
    val index: Int = 0,
    val task: Task? = null,
    val projectPath: String = "",
    /** "Added 3 weeks ago". */
    val added: String? = null,
    val draft: CardDraft = CardDraft(),
    val suggestion: Suggestion? = null,
    /** The suggestion for this card is on its way. */
    val suggesting: Boolean = false,
    val projects: List<GuidedProject> = emptyList(),
    /** The projects the project picker offers: the ones the person can change. */
    val pickerProjects: List<Project> = emptyList(),
    val inboxId: Long? = null,
    val confirmDelete: Boolean = true,
    val busy: Boolean = false,
    val canGoBack: Boolean = false,
    val finished: Boolean = false,
    val summary: String = "",
    val canUndo: Boolean = false,
) {
    fun pathOf(projectId: Long): String = projects.firstOrNull { it.id == projectId }?.path.orEmpty()
}

/**
 * A guided session over one queue, a card at a time (docs/cross-app-semantics-v1.md, sections
 * 14.2 and 14.3): the person picks a date, a project or a new title and goes on, or completes,
 * deletes or skips; with Ask set up, each card comes with a suggestion (`docs/ai-assistant-v1.md`,
 * section 9). Nothing is written before Next, Complete or Delete; Undo all puts back all but deletes.
 */
class GuidedSortViewModel(
    savedStateHandle: SavedStateHandle,
    private val source: GuidedWorldSource,
    private val writer: GuidedWriter,
    private val organize: OrganizeSuggester,
    private val behaviorPrefs: BehaviorPrefsStore,
    private val progress: GuidedProgress,
    private val messages: AppMessages,
    private val dayClock: DayClock,
) : ViewModel() {
    private val queue: GuidedQueue = GuidedQueue.of(savedStateHandle.get<String>("kind").orEmpty()) ?: GuidedQueue.INBOX

    private val _uiState = MutableStateFlow(GuidedSortUiState(queue))
    val uiState: StateFlow<GuidedSortUiState> = _uiState.asStateFlow()

    private var ids: List<Long> = emptyList()
    private var world: GuidedWorld? = null
    private val log = GuidedLog()

    /** Cards already left once: going back to one shows it as it is, without the suggestion filled in. */
    private val visited = HashSet<Long>()

    /** The person changed something on the card shown: a suggestion that arrives late leaves it alone. */
    private var touched = false

    private val suggestions = HashMap<Long, Suggestion>()
    private val requested = HashSet<Int>()
    private val waiting = HashSet<Int>()
    private var suggestionsOn = false
    private val writing = Mutex()

    init {
        viewModelScope.launch {
            suggestionsOn = organize.isReady()
            val now = source.now()
            ids = Guided.queue(queue, now)
            world = now
            _uiState.update { it.copy(loading = false, total = ids.size) }
            show(0)
            source.world.collect { w ->
                world = w
                refreshCard()
            }
        }
        viewModelScope.launch {
            behaviorPrefs.getPrefs().collect { prefs -> _uiState.update { it.copy(confirmDelete = prefs.confirmBeforeDelete) } }
        }
    }

    private fun taskAt(index: Int): Task? = ids.getOrNull(index)?.let { id -> world?.tasks?.firstOrNull { it.id == id } }

    /** A card can be shown: the task is still there and open (section 14.2). */
    private fun showable(index: Int): Boolean = taskAt(index)?.let { !it.done } == true

    private fun show(index: Int) {
        var i = index
        while (i < ids.size && !showable(i)) i++
        if (i >= ids.size) return finish()
        val id = ids[i]
        touched = false
        val suggestion = suggestions[id]
        _uiState.update {
            it.copy(
                index = i,
                draft = if (id !in visited && suggestion != null) CardDraft.of(suggestion) else CardDraft(),
                suggestion = suggestion,
                canGoBack = (0 until i).any(::showable),
            )
        }
        askForSuggestions(i)
        refreshCard()
    }

    /** The card's task, project and the pickers, as the latest world has them. */
    private fun refreshCard() {
        val w = world ?: return
        val state = _uiState.value
        if (state.finished || state.loading) return
        val task = taskAt(state.index)
        if (task == null || task.done) {
            // Done or deleted elsewhere: passed over.
            if (!state.busy) show(state.index + 1)
            return
        }
        val projects = w.projects.filter { w.canChange(it.id) }
            .map { GuidedProject(it.id, w.path(it.id)) }
            .sortedWith(compareBy({ it.path.lowercase() }, { it.id }))
        _uiState.update {
            it.copy(
                task = task,
                projectPath = w.path(task.projectId),
                added = Guided.added(task, w),
                projects = projects,
                pickerProjects = w.projects.filter { p -> w.canChange(p.id) },
                inboxId = w.inboxId,
                suggesting = suggestionsOn && task.id !in suggestions && (state.index / batchSize) in waiting,
            )
        }
    }

    // --- Suggestions (ai-assistant 9.1) ------------------------------------------------------

    private val batchSize get() = AiContract.limits.organizeBatchMax

    private fun askForSuggestions(index: Int) {
        if (!suggestionsOn) return
        val batch = index / batchSize
        request(batch)
        if (index >= (batch + 1) * batchSize - AiContract.limits.organizePrefetch) request(batch + 1)
    }

    private fun request(batch: Int) {
        val from = batch * batchSize
        if (!suggestionsOn || from >= ids.size || !requested.add(batch)) return
        val slice = ids.subList(from, minOf(ids.size, from + batchSize)).toList()
        waiting += batch
        viewModelScope.launch {
            try {
                val result = organize.suggest(sessionOf(queue), slice, queue.title, defaultProjectId = null)
                result.suggestions.forEach { suggestions[it.taskId] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Said once; the session goes on without suggestions (9.4).
                if (suggestionsOn) messages.post("No suggestions: " + (e.message ?: "the model did not answer."))
                suggestionsOn = false
            } finally {
                waiting -= batch
            }
            val id = ids.getOrNull(_uiState.value.index)
            val suggestion = id?.let { suggestions[it] }
            if (suggestion != null && _uiState.value.suggestion == null) {
                _uiState.update { s -> s.copy(suggestion = suggestion, draft = if (touched || id in visited) s.draft else CardDraft.of(suggestion)) }
            }
            refreshCard()
        }
    }

    // --- The card's choices -------------------------------------------------------------------

    fun setTitle(title: String) = edit { it.withTitle(title) }

    fun setDue(due: DueChoice?) = edit { it.withDue(due) }

    fun setProject(projectId: Long?) = edit { it.withProject(projectId) }

    private fun edit(change: (CardDraft) -> CardDraft) {
        touched = true
        _uiState.update { it.copy(draft = change(it.draft)) }
    }

    /** Writes the card's choices and goes on; without a change it is Skip. */
    fun next() {
        val state = _uiState.value
        val task = state.task ?: return
        val change = state.draft.changeOf(task) ?: return skip()
        act(task) {
            if (writer.write(change)) log.changed(change, dayClock.day.value.zone) else messages.post("Could not change \"${task.title}\"")
        }
    }

    fun skip() {
        val task = _uiState.value.task ?: return
        visited += task.id
        show(_uiState.value.index + 1)
    }

    fun complete() {
        val task = _uiState.value.task ?: return
        act(task) { if (writer.complete(task)) log.completed(task) else messages.post("Could not complete \"${task.title}\"") }
    }

    fun delete() {
        val task = _uiState.value.task ?: return
        act(task) { if (writer.delete(task)) log.deleted(task) else messages.post("Could not delete \"${task.title}\"") }
    }

    /** The previous card that is still there, as it is now. */
    fun back() {
        val index = _uiState.value.index
        val previous = (index - 1 downTo 0).firstOrNull(::showable) ?: return
        _uiState.value.task?.let { visited += it.id }
        show(previous)
    }

    private fun act(task: Task, write: suspend () -> Unit) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            writing.withLock { write() }
            visited += task.id
            // The world shows the write before the card moves on.
            world = source.world.first()
            _uiState.update { it.copy(busy = false) }
            show(_uiState.value.index + 1)
        }
    }

    private fun finish() {
        progress.finished(queue.id)
        _uiState.update {
            it.copy(finished = true, task = null, summary = log.counts().text, canUndo = log.all.any { e -> !e.deleted })
        }
    }

    /** Undo all: every change of the session back, except deletes (section 14.3). */
    fun undoAll() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            writing.withLock { writer.undo(log) }
            log.clear()
            _uiState.update { it.copy(busy = false, canUndo = false, summary = "Everything is back as it was") }
        }
    }

    companion object {
        fun sessionOf(queue: GuidedQueue): OrganizeSession = when (queue) {
            GuidedQueue.INBOX -> OrganizeSession.INBOX
            GuidedQueue.OVERDUE -> OrganizeSession.OVERDUE
            GuidedQueue.WAITING -> OrganizeSession.WAITING
        }
    }
}
