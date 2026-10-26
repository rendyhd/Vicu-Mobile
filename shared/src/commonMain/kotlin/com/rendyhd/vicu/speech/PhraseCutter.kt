package com.rendyhd.vicu.speech

import com.rendyhd.vicu.ai.AiContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The voice detector's settings (`speech.vad` in the fixture). */
data class VadSettings(
    val threshold: Float,
    val minSpeechMs: Int,
    val minSilenceMs: Int,
    val padMs: Int,
    val maxPhraseSeconds: Int,
) {
    companion object {
        fun fromContract(): VadSettings = from(AiContract.speech.getValue("vad").jsonObject)

        fun from(o: JsonObject) = VadSettings(
            threshold = o.getValue("threshold").jsonPrimitive.double.toFloat(),
            minSpeechMs = o.getValue("min_speech_ms").jsonPrimitive.int,
            minSilenceMs = o.getValue("min_silence_ms").jsonPrimitive.int,
            padMs = o.getValue("pad_ms").jsonPrimitive.int,
            maxPhraseSeconds = o.getValue("max_phrase_s").jsonPrimitive.int,
        )
    }
}

/**
 * Cuts the recording into phrases while the user talks (`docs/ai-assistant-v1.md`, section 3.2),
 * from one speech probability per detector window: a phrase ends after a pause of at least
 * `min_silence_ms` or at `max_phrase_s`; silence between phrases is dropped, with `pad_ms` kept on
 * each side; a phrase with less than `min_speech_ms` of speech is dropped. Pure: no clock, no I/O.
 */
class PhraseCutter(
    private val settings: VadSettings,
    private val sampleRate: Int = 16_000,
    private val window: Int = SpeechEngineNative.VAD_WINDOW_SAMPLES,
) {
    private val windowMs = window * 1_000 / sampleRate
    private val padWindows = (settings.padMs + windowMs - 1) / windowMs
    private val silenceWindows = (settings.minSilenceMs + windowMs - 1) / windowMs
    private val speechWindowsMin = (settings.minSpeechMs + windowMs - 1) / windowMs
    private val maxWindows = settings.maxPhraseSeconds * 1_000 / windowMs

    /** Recent silent windows, kept to pad the start of the next phrase. */
    private val preRoll = ArrayDeque<FloatArray>()
    private val phrase = mutableListOf<FloatArray>()
    private var inSpeech = false
    private var speechWindows = 0
    private var silenceRun = 0

    /** In a phrase with at least `min_speech_ms` of speech so far: the person is talking (section 3.7). */
    val hasSpeech: Boolean get() = inSpeech && speechWindows >= speechWindowsMin

    /** One window and its speech probability; returns a finished phrase, or null. */
    fun push(samples: FloatArray, probability: Float): FloatArray? {
        val speech = probability >= settings.threshold
        if (!inSpeech) {
            if (!speech) {
                preRoll.addLast(samples)
                while (preRoll.size > padWindows) preRoll.removeFirst()
                return null
            }
            inSpeech = true
            phrase.addAll(preRoll)
            preRoll.clear()
            phrase += samples
            speechWindows = 1
            silenceRun = 0
            return null
        }
        phrase += samples
        if (speech) {
            speechWindows++
            silenceRun = 0
        } else {
            silenceRun++
        }
        if (silenceRun >= silenceWindows) return end(trailingSilence = silenceRun, continueSpeech = false)
        if (phrase.size >= maxWindows) return end(trailingSilence = silenceRun, continueSpeech = speech)
        return null
    }

    /** The recording ended: the phrase in progress, if it has enough speech. */
    fun finish(): FloatArray? {
        if (!inSpeech) return null
        return end(trailingSilence = silenceRun, continueSpeech = false)
    }

    fun reset() {
        preRoll.clear()
        phrase.clear()
        inSpeech = false
        speechWindows = 0
        silenceRun = 0
    }

    private fun end(trailingSilence: Int, continueSpeech: Boolean): FloatArray? {
        // Keep pad_ms of the pause after the speech; the rest of it pads the next phrase.
        val drop = (trailingSilence - padWindows).coerceAtLeast(0)
        val kept = phrase.subList(0, phrase.size - drop).toList()
        val tail = phrase.subList(phrase.size - drop, phrase.size).toList()
        val enough = speechWindows >= speechWindowsMin
        phrase.clear()
        preRoll.clear()
        tail.takeLast(padWindows).forEach { preRoll.addLast(it) }
        speechWindows = 0
        silenceRun = 0
        inSpeech = continueSpeech
        if (!enough) return null
        return join(kept)
    }

    private fun join(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }
}

/**
 * Talking and pauses for an open microphone (`docs/ai-assistant-v1.md`, section 3.7), one detector
 * window at a time, after [PhraseCutter] took it: talking starts once a phrase has `min_speech_ms` of
 * speech, rests after `min_silence_ms` of silence (so speech after it starts again), and a pause of
 * [endOfTurnMs] after speech ends the turn. Pure: no clock, no I/O.
 */
class TurnWatch(
    private val settings: VadSettings,
    endOfTurnMs: Long,
    sampleRate: Int = 16_000,
    window: Int = SpeechEngineNative.VAD_WINDOW_SAMPLES,
) {
    private val windowMs = window * 1_000 / sampleRate
    private val pauseWindows = ((endOfTurnMs + windowMs - 1) / windowMs).toInt()
    private val restWindows = (settings.minSilenceMs + windowMs - 1) / windowMs
    private var talking = false
    private var turnOpen = false
    private var silent = 0

    /** The window with [probability] that [cutter] just took: talking started, the turn paused, or nothing. */
    fun push(cutter: PhraseCutter, probability: Float): LocalSpeechEvent? {
        if (probability >= settings.threshold) silent = 0 else silent++
        if (!talking && cutter.hasSpeech) {
            talking = true
            turnOpen = true
            return LocalSpeechEvent.SpeechStarted
        }
        if (talking && silent >= restWindows) talking = false
        if (turnOpen && silent >= pauseWindows) {
            turnOpen = false
            return LocalSpeechEvent.Paused
        }
        return null
    }
}
