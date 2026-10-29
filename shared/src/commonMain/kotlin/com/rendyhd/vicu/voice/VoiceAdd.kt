package com.rendyhd.vicu.voice

import com.rendyhd.vicu.speech.LocalSpeechEvent
import com.rendyhd.vicu.speech.SpeechRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the voice add card shows: [VoicePhase.LISTENING] or, after Done or a pause, [VoicePhase.FINISHING]. */
data class VoiceAddState(
    val phase: VoicePhase = VoicePhase.LISTENING,
    /** The phrases transcribed so far. */
    val text: String = "",
    /** The voice's loudness, 0 to 1. */
    val level: Float = 0f,
)

/**
 * Adding a task by voice from outside the app (the widgets' mic) with
 * the chosen local speech model, so it hears every language the model does. It listens until a
 * pause after the words, Done ([done]) or Cancel ([cancel]), then hands the whole transcript to
 * [onWords] (empty when nothing was understood). Without a word for [idleMs] it gives up. It runs
 * on the app scope: words still being transcribed when the card closes are added all the same.
 * Every call on the main thread.
 */
class VoiceAddSession(
    private val recording: SpeechRecording,
    private val scope: CoroutineScope,
    private val onWords: suspend (String) -> Unit,
    /** The microphone or the model could not start. */
    private val onFailed: () -> Unit,
    private val idleMs: Long = IDLE_MS,
) {
    private val _state = MutableStateFlow<VoiceAddState?>(VoiceAddState())

    /** Null once the session is over: the card closes. */
    val state: StateFlow<VoiceAddState?> = _state.asStateFlow()

    private var watch: Job? = null

    fun start() {
        watch = scope.launch {
            if (!recording.start()) {
                recording.cancel()
                if (_state.value != null) {
                    _state.value = null
                    onFailed()
                }
                return@launch
            }
            val shown = launch {
                recording.state.collect { heard ->
                    _state.value?.let { _state.value = it.copy(text = heard.text, level = if (it.phase == VoicePhase.LISTENING) heard.level else 0f) }
                }
            }
            var spoke = false
            val idle = launch {
                delay(idleMs)
                if (!spoke) done()
            }
            recording.events.collect { event ->
                when (event) {
                    LocalSpeechEvent.SpeechStarted -> spoke = true
                    // A pause after the words ends the task, as the system speech screen does.
                    LocalSpeechEvent.Paused -> if (spoke) done()
                    else -> Unit
                }
            }
            idle.cancel()
            shown.cancel()
            // The recording ended by itself (its time ran out, the microphone was taken): what was heard counts.
            done()
        }
    }

    /** Done, or a pause: stops recording and adds what was heard once its last phrase is transcribed. */
    fun done() {
        val current = _state.value ?: return
        if (current.phase == VoicePhase.FINISHING) return
        _state.value = current.copy(phase = VoicePhase.FINISHING, level = 0f)
        scope.launch {
            val text = recording.finish().trim()
            watch?.cancel()
            if (_state.value == null) return@launch
            _state.value = null
            onWords(text)
        }
    }

    /** Throws the recording away; nothing is added. */
    fun cancel() {
        if (_state.value == null) return
        _state.value = null
        watch?.cancel()
        recording.cancel()
    }

    companion object {
        /** The pause after the words that ends a task: room to think in the middle of a sentence. */
        const val END_OF_TASK_MS = 2_000L

        /** Without a word for this long the microphone turns off. */
        const val IDLE_MS = 8_000L
    }
}
