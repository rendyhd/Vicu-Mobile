package com.rendyhd.vicu.ui.screens.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.ai.AiProviders
import com.rendyhd.vicu.ai.AiSettings
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.ai.ask.Applied
import com.rendyhd.vicu.ai.ask.AskBackend
import com.rendyhd.vicu.ai.ask.AskList
import com.rendyhd.vicu.ai.ask.AskSession
import com.rendyhd.vicu.ai.ask.AskState
import com.rendyhd.vicu.ai.ask.Conversation
import com.rendyhd.vicu.ai.ask.ConversationStore
import com.rendyhd.vicu.ai.ask.Proposal
import com.rendyhd.vicu.ai.ask.Proposals
import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.ai.talk.SpokenConfirmation
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.speech.SpeechOutput
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.deviceLocaleTag
import com.rendyhd.vicu.util.randomUuid
import com.rendyhd.vicu.voice.SelectingSpeechInput
import com.rendyhd.vicu.voice.SpeechAvailability
import com.rendyhd.vicu.voice.SpeechError
import com.rendyhd.vicu.voice.SpeechListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Talk's microphone: what it heard so far, while the button is held. */
data class TalkCapture(val listening: Boolean = false, val text: String = "", val level: Float = 0f, val finishing: Boolean = false)

data class AskUiState(
    val session: AskState? = null,
    val settings: AiSettings = AiSettings(),
    val history: List<Conversation> = emptyList(),
    /** Tasks that `[task:id]` marks of this conversation show. */
    val tasks: Map<Long, Task> = emptyMap(),
    val talk: TalkCapture = TalkCapture(),
    val talkMode: Boolean = false,
)

/**
 * Ask and Talk (`docs/ai-assistant-v1.md`, sections 5 and 6): the conversation, its history,
 * proposal cards with Apply and Decline (Undo on the app snackbar), and push-to-talk with spoken
 * answers and spoken confirmation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModel(
    private val providers: AiProviders,
    private val settingsStore: AiSettingsStore,
    private val backend: AskBackend,
    private val proposals: Proposals,
    private val store: ConversationStore,
    private val tasks: TaskRepository,
    private val messages: AppMessages,
    private val speech: SelectingSpeechInput,
    private val voice: SpeechOutput,
    private val dayClock: DayClock,
    private val time: TimeSource,
) : ViewModel() {
    private val session = MutableStateFlow<AskSession?>(null)
    private val talk = MutableStateFlow(TalkCapture())
    private val talkMode = MutableStateFlow(false)
    private var list = AskList("Inbox")
    private var turn: Job? = null

    private val sessionState = session.flatMapLatest { it?.state ?: flowOf(null) }

    private val knownTasks = combine(sessionState.map { it?.conversation?.knownTaskIds.orEmpty() }, tasks.getAllTasksFlat()) { ids, all ->
        if (ids.isEmpty()) emptyMap() else all.filter { it.id in ids }.associateBy { it.id }
    }

    val uiState: StateFlow<AskUiState> = combine(
        sessionState,
        settingsStore.settings,
        store.conversations,
        knownTasks,
        combine(talk, talkMode) { t, m -> t to m },
    ) { state, settings, history, known, (capture, mode) ->
        AskUiState(state, settings, history, known, capture, mode)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AskUiState())

    init {
        viewModelScope.launch {
            val last = store.all().firstOrNull()
            open(last ?: Conversation(id = randomUuid()))
        }
    }

    /** Where the user came from, for the context block (section 5.1). */
    fun setList(name: String, projectId: Long?, customListId: String?) {
        list = AskList(name, projectId, customListId)
    }

    fun open(conversation: Conversation) {
        turn?.cancel()
        // A conversation saved halfway through a tool call loses that unanswered call.
        val messages = conversation.messages.let { m ->
            val last = m.lastOrNull()
            if (last is ChatMessage.Assistant && last.toolCalls.isNotEmpty()) m.dropLast(1) else m
        }
        session.value = AskSession(providers, settingsStore, backend, proposals, store, conversation.copy(messages = messages), time, ::randomUuid)
        talkMode.value = conversation.talk
    }

    fun newChat() {
        open(Conversation(id = randomUuid()))
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            store.delete(id)
            if (session.value?.conversation?.id == id) newChat()
        }
    }

    fun setTalkMode(on: Boolean) {
        talkMode.value = on
        if (!on) voice.stop()
    }

    fun send(text: String, spoken: Boolean = false) {
        val s = session.value ?: return
        val pending = s.state.value.pending
        if ((pending != null || s.state.value.lingering != null) && spoken) {
            // Section 6.1: an exact yes or no answers the card.
            val language = uiState.value.settings.speechLanguage.takeIf { it != "auto" } ?: deviceLocaleTag()
            when (SpokenConfirmation.answer(text, language)) {
                SpokenConfirmation.Answer.YES -> return apply()
                SpokenConfirmation.Answer.NO -> return decline()
                SpokenConfirmation.Answer.OTHER -> Unit
            }
        }
        if (s.state.value.busy && pending == null) return
        turn = viewModelScope.launch {
            s.send(text, list, talk = talkMode.value || spoken)
            if (talkMode.value) speakLastAnswer(s)
        }
    }

    fun retry() {
        val s = session.value ?: return
        turn = viewModelScope.launch {
            s.retry(list, talkMode.value)
            if (talkMode.value) speakLastAnswer(s)
        }
    }

    /**
     * Applies the open card, or else the lingering one; the change gets one message with Undo
     * (section 5.4).
     */
    fun apply() {
        val s = session.value ?: return
        val pending = s.state.value.pending
        val card = pending ?: s.state.value.lingering ?: return
        viewModelScope.launch {
            val applied = proposals.apply(card.proposal, dayClock.day.value.zone)
            if (pending != null) s.decide(applied) else s.decideLingering(applied)
            announce(card.proposal, applied)
        }
    }

    fun decline() {
        val s = session.value ?: return
        val pending = s.state.value.pending
        val card = pending ?: s.state.value.lingering ?: return
        val declined = Applied.declined(card.proposal.skipped)
        if (pending != null) s.decide(declined) else viewModelScope.launch { s.decideLingering(declined) }
    }

    fun completeTask(task: Task) {
        viewModelScope.launch { tasks.setDone(task.id, !task.done) }
    }

    private fun announce(proposal: Proposal, applied: Applied) {
        val n = applied.appliedIds.size
        val text = when {
            n == 0 -> "Nothing was changed"
            proposal is Proposal.Create -> if (n == 1) "Added 1 task" else "Added $n tasks"
            proposal is Proposal.Complete && proposal.done -> if (n == 1) "Completed 1 task" else "Completed $n tasks"
            else -> if (n == 1) "Changed 1 task" else "Changed $n tasks"
        }
        val undo = applied.undo
        if (undo == null) messages.post(text) else messages.post(text, "Undo") { viewModelScope.launch { undo() } }
    }

    private suspend fun speakLastAnswer(s: AskSession) {
        val settings = settingsStore.current()
        if (!settings.speakAnswers || !voice.isAvailable) return
        val last = s.conversation.messages.lastOrNull() as? ChatMessage.Assistant ?: return
        val text = AskSession.TASK_MARK.replace(last.text, "").trim()
        if (text.isEmpty()) return
        val language = settings.speechLanguage.takeIf { it != "auto" }
        voice.speak(text, language) {}
    }

    // --- Talk's microphone ------------------------------------------------------------------

    /** Press: starts listening (asking for the microphone the first time). */
    fun startTalking() {
        voice.stop()
        when (speech.availability()) {
            SpeechAvailability.READY -> Unit
            SpeechAvailability.NEEDS_PERMISSION -> {
                speech.requestPermission { granted -> if (granted) messages.post("Microphone allowed. Hold the microphone and talk.") }
                return
            }
            SpeechAvailability.DENIED -> {
                messages.post("Allow the microphone in Settings to talk to Vicu.", "Settings") { speech.openSettings() }
                return
            }
            SpeechAvailability.UNAVAILABLE -> {
                messages.post("Speech recognition is not available on this device.")
                return
            }
        }
        talk.value = TalkCapture(listening = true)
        speech.start(
            object : SpeechListener {
                override fun onPartial(text: String) = talk.update { it.copy(text = text) }
                override fun onLevel(level: Float) = talk.update { it.copy(level = level) }
                override fun onFinal(text: String) = heard(talk.value.text.let { if (it.isNotBlank() && !text.startsWith(it)) "$it $text" else text })
                override fun onError(error: SpeechError) = heard(talk.value.text)
            },
        )
    }

    /** Release: the words go to the model (or answer the open card). */
    fun stopTalking() {
        if (!talk.value.listening) return
        talk.update { it.copy(finishing = true) }
        speech.stop()
    }

    fun cancelTalking() {
        speech.cancel()
        talk.value = TalkCapture()
    }

    private fun heard(text: String) {
        talk.value = TalkCapture()
        if (text.isNotBlank()) send(text, spoken = true)
    }

    override fun onCleared() {
        voice.stop()
        speech.cancel()
    }

    private fun Set<Long>?.orEmpty(): Set<Long> = this ?: emptySet()

}
