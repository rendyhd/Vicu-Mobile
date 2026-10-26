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
 * microphone goes off with the sheet.
 */
class DictationMic(
    private val controller: DictationController,
    private val speech: SelectingSpeechInput,
    settingsStore: AiSettingsStore,
    private val messages: AppMessages,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DictationMicUi())
    val state: StateFlow<DictationMicUi> = _state.asStateFlow()

    /** Smart dictation can run: holding + offers the lock. */
    val ready: StateFlow<Boolean> = settingsStore.settings.map { it.dictationReady }.stateIn(scope, SharingStarted.Eagerly, false)

    private var mic: OpenMic? = null
    private val sender = TurnSender()
    private var request: Job? = null

    init {
        // Add, Discard or back closed the sheet: the microphone goes off with it.
        scope.launch { controller.state.collect { if (it == null && (mic != null || _state.value.starting)) off() } }
    }

    /** The lock: the sheet opens for the list [defaults] describes; [listen] follows with the words said before. */
    fun open(defaults: TaskDefaults) {
        controller.listen(defaults)
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
        sender.turn(heard)?.let(::dispatch)
        on()
    }

    /** Try it (Settings): smart dictation for the Inbox, listening. */
    fun tryIt() {
        open(TaskDefaults(listName = "Inbox"))
        listen("")
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
        mic = speech.openMic(listener)
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
            if (controller.isBusy && sender.interrupt()) {
                request = null
                controller.cancelRequest()
            }
            _state.update { it.copy(words = sender.waiting) }
        }

        override fun onTurn(text: String) {
            _state.update { it.copy(words = "") }
            sender.turn(text)?.let(::dispatch)
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
