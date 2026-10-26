package com.rendyhd.vicu.ai.dictation

import com.rendyhd.vicu.ai.provider.LlmException
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.TextTaskCreator
import com.rendyhd.vicu.util.postIfRefused
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.voice.TaskDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DictationPhase {
    /** The model is reading the transcript. */
    THINKING,

    /** Draft cards are on screen. */
    REVIEW,

    /** The request failed: the transcript waits with Retry and Add as one task. */
    FAILED,

    /** The checks left nothing to add. */
    EMPTY,
}

/** What the draft sheet shows (`docs/ai-assistant-v1.md`, sections 4.4 to 4.6). */
data class DictationUi(
    val phase: DictationPhase,
    val transcript: String,
    val defaults: TaskDefaults,
    val parentTaskId: Long? = null,
    val drafts: List<CheckedDraft> = emptyList(),
    val dropped: List<DroppedField> = emptyList(),
    val unclear: List<String> = emptyList(),
    /** Per draft ref, the fields the last voice edit changed (highlighted on the card). */
    val changed: Map<String, Set<String>> = emptyMap(),
    val context: DictationContext? = null,
    val error: String? = null,
    /** A voice instruction is being applied to the drafts. */
    val editing: Boolean = false,
    val adding: Boolean = false,
)

/**
 * Smart dictation's flow across the app: a transcript comes in (holding +, Make tasks from this,
 * Break into steps), the model's drafts are checked and shown, the user changes them by hand or by
 * voice, and Add creates them with one Undo. A failed transcript is kept until added or discarded.
 */
class DictationController(
    private val service: DictationService,
    private val writer: DraftWriter,
    private val kept: KeptTranscriptStore,
    private val textTasks: TextTaskCreator,
    private val tasks: TaskRepository,
    private val messages: AppMessages,
    private val dayClock: DayClock,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<DictationUi?>(null)
    val state: StateFlow<DictationUi?> = _state.asStateFlow()

    private var job: Job? = null

    val isOpen: Boolean get() = _state.value != null

    suspend fun isReady(): Boolean = service.isReady()

    /** At app start: a transcript kept from a failed dictation comes back with its two choices. */
    fun restoreKept() {
        scope.launch {
            val k = kept.current() ?: return@launch
            if (_state.value != null) return@launch
            _state.value = DictationUi(
                phase = DictationPhase.FAILED,
                transcript = k.transcript,
                defaults = TaskDefaults(k.projectId, k.dueDate, k.listName),
                parentTaskId = k.parentTaskId,
                error = "This dictation was not turned into tasks yet.",
            )
        }
    }

    fun start(transcript: String, defaults: TaskDefaults, parentTaskId: Long? = null) {
        val text = transcript.trim()
        if (text.isEmpty()) return
        _state.value = DictationUi(DictationPhase.THINKING, text, defaults, parentTaskId)
        run()
    }

    fun retry() {
        val current = _state.value ?: return
        _state.value = current.copy(phase = DictationPhase.THINKING, error = null)
        run()
    }

    private fun run() {
        job?.cancel()
        job = scope.launch {
            val current = _state.value ?: return@launch
            try {
                val context = service.context(current.defaults)
                val result = service.run(current.transcript, context)
                kept.clear()
                _state.update {
                    it?.copy(
                        phase = if (result.drafts.isEmpty()) DictationPhase.EMPTY else DictationPhase.REVIEW,
                        drafts = result.drafts,
                        dropped = result.dropped,
                        unclear = result.unclear,
                        context = context,
                        changed = emptyMap(),
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                kept.keep(KeptTranscript(current.transcript, current.defaults.listName, current.defaults.projectId, current.defaults.dueDate, current.parentTaskId))
                _state.update { it?.copy(phase = DictationPhase.FAILED, error = (e as? LlmException)?.message ?: "The model did not answer.") }
            }
        }
    }

    /** A voice instruction about the drafts on screen (section 4.4). */
    fun edit(instruction: String) {
        val current = _state.value ?: return
        val context = current.context ?: return
        if (current.phase != DictationPhase.REVIEW || instruction.isBlank()) return
        _state.value = current.copy(editing = true)
        job?.cancel()
        job = scope.launch {
            try {
                val result = service.run(instruction.trim(), context, current.drafts)
                val before = current.drafts.associateBy { it.ref }
                val changed = result.drafts.associate { d -> d.ref to changedFields(before[d.ref], d) }.filterValues { it.isNotEmpty() }
                _state.update {
                    it?.copy(
                        phase = if (result.drafts.isEmpty()) DictationPhase.EMPTY else DictationPhase.REVIEW,
                        drafts = result.drafts,
                        dropped = result.dropped,
                        unclear = result.unclear,
                        changed = changed,
                        editing = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it?.copy(editing = false) }
                messages.post((e as? LlmException)?.message ?: "Could not change the drafts.")
            }
        }
    }

    fun updateDraft(ref: String, change: (CheckedDraft) -> CheckedDraft) {
        _state.update { s -> s?.copy(drafts = s.drafts.map { if (it.ref == ref) change(it) else it }) }
    }

    fun removeDraft(ref: String) {
        _state.update { s ->
            val drafts = s?.drafts?.filterNot { it.ref == ref } ?: return@update s
            s.copy(drafts = drafts, phase = if (drafts.isEmpty()) DictationPhase.EMPTY else s.phase)
        }
    }

    /** Add: creates the drafts in order, one message with Undo. */
    fun add() {
        val current = _state.value ?: return
        if (current.drafts.isEmpty() || current.adding) return
        _state.value = current.copy(adding = true)
        scope.launch {
            val zone = dayClock.day.value.zone
            val added = writer.add(current.drafts, zone, current.parentTaskId)
            _state.value = null
            kept.clear()
            val n = added.taskIds.size
            when {
                n == 0 -> messages.post(added.firstError ?: "Could not add the tasks.")
                else -> {
                    val text = (if (n == 1) "Added 1 task" else "Added $n tasks") +
                        if (added.failed > 0) ", ${added.failed} failed" else ""
                    messages.post(text, "Undo") { scope.launch { writer.undo(added) } }
                }
            }
        }
    }

    /** Add as one task: the transcript through the quick-add parser, as dictation did before. */
    fun addAsOneTask(format: DateDisplayFormat) {
        val current = _state.value ?: return
        _state.value = null
        scope.launch {
            kept.clear()
            when (val result = textTasks.create(current.transcript, format, current.defaults.projectId, current.defaults.dueDate)) {
                is TextTaskCreator.Result.Created -> messages.post(result.confirmation, "Undo") {
                    scope.launch { messages.postIfRefused(tasks.delete(result.task.id), "undo the task") }
                }
                is TextTaskCreator.Result.Failed -> messages.post(result.message)
            }
        }
    }

    fun discard() {
        job?.cancel()
        _state.value = null
        scope.launch { kept.clear() }
    }

    private fun changedFields(before: CheckedDraft?, after: CheckedDraft): Set<String> {
        if (before == null) return setOf("new")
        val fields = mutableSetOf<String>()
        if (before.title != after.title) fields += "title"
        if (before.notes != after.notes) fields += "notes"
        if (before.due != after.due || before.dueHasTime != after.dueHasTime) fields += "when"
        if (before.projectId != after.projectId) fields += "project"
        if (before.labels != after.labels) fields += "labels"
        if (before.priority != after.priority) fields += "priority"
        if (before.repeat != after.repeat) fields += "repeat"
        if (before.reminder != after.reminder) fields += "reminder"
        if (before.place != after.place) fields += "place"
        if (before.subtasks != after.subtasks) fields += "subtasks"
        return fields
    }
}
