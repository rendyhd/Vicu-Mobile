package com.rendyhd.vicu.voice

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.speech.LocalSpeechEvent
import com.rendyhd.vicu.speech.LocalSpeechSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** The open microphone's timing (`docs/ai-assistant-v1.md`, section 3.7; `speech.turn` in the fixture). */
data class TurnSettings(
    /** The pause after speech that ends a turn. */
    val endOfTurnMs: Long,
    /** Without speech for this long, the microphone turns off. */
    val idleMs: Long,
) {
    companion object {
        fun fromContract(): TurnSettings {
            val o = AiContract.speech.getValue("turn").jsonObject
            return TurnSettings(
                endOfTurnMs = o.getValue("end_of_turn_ms").jsonPrimitive.long,
                idleMs = o.getValue("idle_s").jsonPrimitive.long * 1_000,
            )
        }
    }
}

/** What an open microphone reports. Called on the main thread. */
interface TurnListener {
    /** The words of the turn so far (each call replaces the previous one). */
    fun onWords(text: String)

    /** The voice's loudness, 0 to 1. */
    fun onLevel(level: Float)

    /** The person started talking; a request still running for the last turn is cancelled (section 3.7). */
    fun onSpeechStart()

    /** A pause ended the turn: its words, empty when the speech had none. */
    fun onTurn(text: String)

    /** The microphone turned itself off: after `idle_s` without speech ([error] null), or on [error]. Nothing follows. */
    fun onOff(error: SpeechError?)
}

/**
 * A microphone that stays on (`docs/ai-assistant-v1.md`, section 3.7) and reports what is said in
 * turns, each ending at a pause. Made by [SelectingSpeechInput.openMic]. Every call on the main thread.
 */
interface OpenMic {
    /** Stops listening while an answer is read aloud; the turn in progress is dropped. */
    fun pause()

    /** Listens again after [pause]. */
    fun resume()

    /** Turns the microphone off; nothing follows. */
    fun stop()
}

/**
 * Which words go out when a turn ends, and when talking again cancels the request still running for
 * the last turn (section 3.7): the cancelled words go out with the next turn's. Pure.
 */
class TurnSender {
    /** Words of a cancelled request, sent with the next turn. */
    var waiting: String = ""
        private set

    /** Words of the request still running; null when none runs. */
    private var running: String? = null

    /** A turn ended with [words]: the text to send now, or null when there is nothing to send. */
    fun turn(words: String): String? {
        val text = HoldToTalk.joinWords(waiting, words)
        waiting = ""
        if (text.isBlank()) return null
        running = text
        return text
    }

    /** The person talks again: true when the running request is to be cancelled; its words wait. */
    fun interrupt(): Boolean {
        val words = running ?: return false
        waiting = words
        running = null
        return true
    }

    /** The request for the last turn finished, or no longer counts as running (a card waits for an answer). */
    fun finished() {
        running = null
    }

    fun reset() {
        waiting = ""
        running = null
    }
}

/** Turns the microphone off after a stretch without speech. */
internal class IdleTimer(
    private val scope: CoroutineScope,
    private val ms: Long,
    private val onIdle: () -> Unit,
) {
    private var job: Job? = null

    fun restart() {
        job?.cancel()
        job = scope.launch {
            delay(ms)
            onIdle()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/**
 * Puts local speech's events together into turns: a turn ends at a pause once the phrases before it
 * are transcribed; talking again before that keeps the turn going.
 */
internal class LocalTurns(
    private val listener: TurnListener,
    /** Talking started (the idle time starts again). */
    private val onSpeech: () -> Unit,
    /** A turn was reported. */
    private val afterTurn: () -> Unit,
) {
    private val words = mutableListOf<String>()
    private var pending = 0
    private var paused = false

    fun on(event: LocalSpeechEvent) {
        when (event) {
            LocalSpeechEvent.SpeechStarted -> {
                paused = false
                onSpeech()
                listener.onSpeechStart()
            }
            LocalSpeechEvent.PhraseCut -> pending++
            is LocalSpeechEvent.Transcribed -> {
                pending = (pending - 1).coerceAtLeast(0)
                event.text?.let {
                    words += it
                    listener.onWords(words.joinToString(" "))
                }
                end()
            }
            LocalSpeechEvent.Paused -> {
                paused = true
                end()
            }
        }
    }

    private fun end() {
        if (!paused || pending > 0) return
        paused = false
        val text = words.joinToString(" ")
        words.clear()
        listener.onTurn(text)
        afterTurn()
    }
}

/**
 * The open microphone with local speech: one recording whose voice detector finds the turns
 * ([LocalSpeechSession] with an end of turn). A long conversation gets a new recording at a turn's
 * end before the recording's time limit.
 */
internal class LocalOpenMic(
    private val scope: CoroutineScope,
    settings: TurnSettings,
    private val listener: TurnListener,
    private val maxSeconds: Int,
    /** A new recording with turns. */
    private val newSession: suspend () -> LocalSpeechSession,
) : OpenMic {
    private var on = true
    private var session: LocalSpeechSession? = null
    private var job: Job? = null
    private val idle = IdleTimer(scope, settings.idleMs) { off(null) }

    fun start() {
        idle.restart()
        begin()
    }

    private fun begin() {
        job = scope.launch {
            val s = newSession()
            session = s
            if (!s.start()) {
                if (on && session === s) off(SpeechError.OTHER)
                return@launch
            }
            val turns = LocalTurns(listener, onSpeech = idle::restart, afterTurn = { renewIfLong(s) })
            val levels = launch { s.state.collect { listener.onLevel(it.level) } }
            s.events.collect { turns.on(it) }
            levels.cancel()
            // The recording ended by itself: the microphone was taken away, or its time ran out.
            if (on && session === s) off(if (s.state.value.error != null) SpeechError.OTHER else null)
        }
    }

    private fun renewIfLong(s: LocalSpeechSession) {
        if (session !== s || s.state.value.seconds < maxSeconds / 2) return
        end()
        begin()
    }

    private fun end() {
        val s = session
        session = null
        job?.cancel()
        job = null
        s?.cancel()
    }

    override fun pause() {
        idle.cancel()
        end()
    }

    override fun resume() {
        if (!on || job != null) return
        idle.restart()
        begin()
    }

    override fun stop() {
        on = false
        idle.cancel()
        end()
    }

    private fun off(error: SpeechError?) {
        if (!on) return
        stop()
        listener.onOff(error)
    }
}

/**
 * The open microphone with the platform recognizer, an utterance at a time: talking starts with the
 * first words, and an utterance ends when the recognizer ends it or when its words have not changed
 * for `end_of_turn_ms` (iOS never ends one by itself). Listening starts again after each.
 */
internal class PlatformOpenMic(
    private val speech: SpeechInput,
    private val scope: CoroutineScope,
    private val settings: TurnSettings,
    private val listener: TurnListener,
) : OpenMic {
    private var on = true
    private var paused = false
    private var run = 0
    private var words = ""
    private var talking = false
    private var quiet: Job? = null
    private var again: Job? = null
    private val idle = IdleTimer(scope, settings.idleMs) { off(null) }

    fun start() {
        idle.restart()
        listen()
    }

    private fun listen() {
        val mine = ++run
        words = ""
        talking = false
        speech.start(
            object : SpeechListener {
                override fun onPartial(text: String) {
                    if (mine != run || text.isBlank()) return
                    if (!talking) {
                        talking = true
                        idle.restart()
                        listener.onSpeechStart()
                    }
                    if (text != words) {
                        words = text
                        listener.onWords(text)
                        waitForPause(mine)
                    }
                }

                override fun onLevel(level: Float) {
                    if (mine == run) listener.onLevel(level)
                }

                override fun onFinal(text: String) {
                    if (mine == run) utteranceOver(text.ifBlank { words })
                }

                override fun onError(error: SpeechError) {
                    if (mine != run) return
                    if (error == SpeechError.NO_MATCH) utteranceOver(words) else off(error)
                }
            },
        )
    }

    /** No new words for `end_of_turn_ms`: the recognizer is asked for its last words (onFinal follows). */
    private fun waitForPause(mine: Int) {
        quiet?.cancel()
        quiet = scope.launch {
            delay(settings.endOfTurnMs)
            if (mine == run) speech.stop()
        }
    }

    private fun utteranceOver(text: String) {
        quiet?.cancel()
        run++
        val spoke = talking || text.isNotBlank()
        if (spoke) listener.onTurn(text.trim())
        if (!on || paused) return
        // After an utterance without words the recognizer gets a moment, so a fast failure does not spin.
        again = scope.launch {
            if (!spoke) delay(RETRY_MS)
            if (on && !paused) listen()
        }
    }

    override fun pause() {
        paused = true
        run++
        quiet?.cancel()
        again?.cancel()
        idle.cancel()
        speech.cancel()
    }

    override fun resume() {
        if (!on || !paused) return
        paused = false
        idle.restart()
        listen()
    }

    override fun stop() {
        on = false
        run++
        quiet?.cancel()
        again?.cancel()
        idle.cancel()
        speech.cancel()
    }

    private fun off(error: SpeechError?) {
        if (!on) return
        stop()
        listener.onOff(error)
    }

    private companion object {
        const val RETRY_MS = 250L
    }
}
