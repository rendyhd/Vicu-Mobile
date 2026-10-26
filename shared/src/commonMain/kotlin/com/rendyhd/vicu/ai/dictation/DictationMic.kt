package com.rendyhd.vicu.ai.dictation

import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.voice.HoldToTalk
import com.rendyhd.vicu.voice.OpenMic
import com.rendyhd.vicu.voice.SelectingSpeechInput
import com.rendyhd.vicu.voice.SpeechAvailability
import com.rendyhd.vicu.voice.SpeechError
import com.rendyhd.vicu.voice.TaskDefaults
import com.rendyhd.vicu.voice.TurnListener
import com.rendyhd.vicu.voice.TurnSender
import com.rendyhd.vicu.voice.TurnSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the draft sheet's microphone shows. */
data class DictationMicUi(
    val on: Boolean = false,
    /** Locked: waiting for the words said before the lock; the microphone starts after them. */
    val starting: Boolean = false,
    /** The words of the turn so far, after the words of a cancelled request. */
    val words: String = "",
    val level: Float = 0f,
)

/**
 * Smart dictation with the microphone on (`docs/ai-assistant-v1.md`, sections 3.7 and 4): opened by
 * sliding up from a held + (or Try it in Settings), each turn goes to the model, the first as a
 * dictation and later ones as edits of the drafts, so the cards appear and change while the person
 * talks. Talking again cancels a request still running; its words go out with the next turn's. The
 * microphone goes off with the sheet. In a brain dump (section 4.8) a turn is not sent: its words
 * are added to the dump, or after the plan to what waits for Update.
 */
class DictationMic(
    private val controller: DictationController,
    private val speech: SelectingSpeechInput,
    private val settingsStore: AiSettingsStore,
    private val messages: AppMessages,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DictationMicUi())
    val state: StateFlow<DictationMicUi> = _state.asStateFlow()

    /** Smart dictation can run: holding + offers the lock. */
    val ready: StateFlow<Boolean> = settingsStore.settings.map { it.dictationReady }.stateIn(scope, SharingStarted.Eagerly, false)

    /** The Brain dump switch as the person last left it: the sheet opens that way. */
    private val brainDumpChosen: StateFlow<Boolean> = settingsStore.settings.map { it.brainDump }.stateIn(scope, SharingStarted.Eagerly, false)

    private val brainDump: Boolean get() = controller.state.value?.brainDump == true

    private var mic: OpenMic? = null
    private val sender = TurnSender()
    private var request: Job? = null

    init {
        // Add, Discard or back closed the sheet: the microphone goes off with it.
        scope.launch { controller.state.collect { if (it == null && (mic != null || _state.value.starting)) off() } }
    }

    /** The lock: the sheet opens for the list [defaults] describes; [listen] follows with the words said before. */
    fun open(defaults: TaskDefaults) {
        controller.listen(defaults, brainDumpChosen.value)
        sender.reset()
        _state.value = DictationMicUi(starting = true)
    }

    /** The words said before the lock (or none): they go to the model, and the microphone stays on. */
    fun listen(heard: String) {
        if (controller.state.value == null) {
            _state.value = DictationMicUi()
            return
        }
        _state.value = DictationMicUi()
        if (brainDump) controller.addWords(heard) else sender.turn(heard)?.let(::dispatch)
        on()
    }

    /** Try it (Settings): smart dictation for the Inbox, listening; [brainDump] opens it as one, without remembering that. */
    fun tryIt(brainDump: Boolean = false) {
        open(TaskDefaults(listName = "Inbox"))
        if (brainDump) controller.setBrainDump(true)
        listen("")
    }

    /**
     * The sheet's Brain dump switch: remembered, and the microphone listens with its timing. Turned
     * off with words in the dump, they go out at once as smart dictation's first turn.
     */
    fun setBrainDump(on: Boolean) {
        val before = controller.state.value
        controller.setBrainDump(on)
        scope.launch { settingsStore.update { it.copy(brainDump = on) } }
        if (!on && before?.brainDump == true && before.phase == DictationPhase.LISTENING && before.transcript.isNotBlank()) {
            // The dump is the running turn, so talking again carries it into the next one.
            sender.reset()
            if (sender.turn(before.transcript) != null) dispatch("")
        }
        if (mic != null) {
            off()
            on()
        }
    }

    /** The sheet's microphone button. */
    fun toggle() {
        if (mic != null) off() else on()
    }

    fun on() {
        if (mic != null || controller.state.value == null) return
        when (speech.availability()) {
            SpeechAvailability.READY -> Unit
            SpeechAvailability.NEEDS_PERMISSION -> {
                speech.requestPermission { granted -> if (granted) on() }
                return
            }
            SpeechAvailability.DENIED -> {
                messages.post("Allow the microphone in Settings to keep talking.", "Settings") { speech.openSettings() }
                return
            }
            SpeechAvailability.UNAVAILABLE -> {
                messages.post("Speech recognition is not available on this device.")
                return
            }
        }
        _state.value = DictationMicUi(on = true, words = sender.waiting)
        mic = speech.openMic(listener, TurnSettings.fromContract(brainDump))
    }

    fun off() {
        mic?.stop()
        mic = null
        _state.value = DictationMicUi()
    }

    private val listener = object : TurnListener {
        override fun onWords(text: String) = _state.update { it.copy(words = HoldToTalk.joinWords(sender.waiting, text)) }

        override fun onLevel(level: Float) = _state.update { it.copy(level = level) }

        override fun onSpeechStart() {
            if (!brainDump && controller.isBusy && sender.interrupt()) {
                request = null
                controller.cancelRequest()
            }
            _state.update { it.copy(words = sender.waiting) }
        }

        override fun onTurn(text: String) {
            _state.update { it.copy(words = "") }
            when {
                !brainDump -> sender.turn(text)?.let(::dispatch)
                !controller.addWords(text) -> {
                    off()
                    messages.post("That is as long as one brain dump can be. Tap Organize, then keep going.")
                }
            }
        }

        override fun onOff(error: SpeechError?) {
            mic = null
            _state.value = DictationMicUi()
            if (error != null) messages.post("The microphone stopped. Tap it to listen again.")
        }
    }

    /**
     * Without drafts the words go out as a dictation, after what was said before (an empty or failed
     * one, or one still running); with drafts, as an edit of them.
     */
    private fun dispatch(text: String) {
        val ui = controller.state.value
        if (ui == null) {
            off()
            return
        }
        val job = if (ui.drafts.isEmpty()) {
            controller.start(HoldToTalk.joinWords(ui.transcript, text), ui.defaults, ui.parentTaskId)
        } else {
            controller.edit(text)
        }
        request = job
        if (job == null) {
            sender.finished()
            return
        }
        job.invokeOnCompletion {
            scope.launch {
                if (request === job) {
                    request = null
                    sender.finished()
                }
            }
        }
    }
}
