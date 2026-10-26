package com.rendyhd.vicu.speech

/**
 * The native speech engine (`native/speech/vicu_speech.h`, whisper.cpp): the Silero voice detector
 * and the Whisper and Parakeet models. Android implements it over JNI (`libvicu_speech.so`), iOS over
 * cinterop with the static library linked into the shared framework. Handles are opaque; 0 is none.
 * Calls on one handle come from one thread at a time; transcribing blocks, so it runs off the main
 * thread (`docs/ai-assistant-v1.md`, section 3).
 */
interface SpeechEngineNative {
    /** False when the library is not on this device (no build for its processor, or it failed to load). */
    val isLoaded: Boolean

    /** The whisper.cpp version, or null when not loaded. */
    fun version(): String?

    fun vadOpen(path: String, threads: Int): Long

    /**
     * Feeds [count] samples of [samples] (16 kHz mono, -1 to 1) to the detector and writes one speech
     * probability per whole 512-sample window to [outProbs]; returns how many, or -1. Samples short
     * of a window are kept for the next call.
     */
    fun vadFeed(vad: Long, samples: FloatArray, count: Int, outProbs: FloatArray): Int

    fun vadReset(vad: Long)

    fun vadClose(vad: Long)

    /** [engine] is [ENGINE_WHISPER] or [ENGINE_PARAKEET]. */
    fun modelOpen(path: String, engine: Int, useGpu: Boolean): Long

    /** The phrase's text, or null when it failed or was aborted. [language] null means detect. */
    fun transcribe(model: Long, samples: FloatArray, count: Int, language: String?, prompt: String?, threads: Int): String?

    /** Asks a running [transcribe] on [model] to stop; it then returns null. */
    fun abort(model: Long)

    fun modelClose(model: Long)

    companion object {
        const val ENGINE_WHISPER = 0
        const val ENGINE_PARAKEET = 1
        const val VAD_WINDOW_SAMPLES = 512
    }
}

enum class MicrophoneAccess { GRANTED, NOT_ASKED, DENIED }

/** The microphone, recording 16 kHz mono (section 3.1): one recorder the app owns. */
interface AudioRecorder {
    fun access(): MicrophoneAccess

    /** Shows the system prompt; [onResult] says whether recording is now allowed. Main thread. */
    fun requestAccess(onResult: (Boolean) -> Unit)

    /**
     * Starts recording. [onSamples] gets the audio as 16 kHz mono samples (-1 to 1), in order, on
     * one background thread, until [stop]; [onError] once if the microphone fails or is taken away.
     * False when recording could not start (busy, not allowed).
     */
    fun start(onSamples: (FloatArray) -> Unit, onError: (String) -> Unit): Boolean

    /** Stops recording; no samples follow once it returns. */
    fun stop()
}

/** The device voice that reads Talk's answers aloud (section 6). */
interface SpeechOutput {
    val isAvailable: Boolean

    /** Speaks [text] in [languageTag] (BCP 47, null for the device language); [onDone] when finished or stopped. */
    fun speak(text: String, languageTag: String?, onDone: () -> Unit)

    fun stop()
}

/** Where speech files live and what the device is like (sections 3.5 and 3.6). */
interface SpeechStorage {
    /** The folder for downloaded speech models, outside backups (Android noBackupFilesDir, iOS excluded from backup). */
    fun modelsDirectory(): String

    /** The path of the bundled Silero model, copied out of the app's assets on first use; null when missing. */
    suspend fun vadModelPath(): String?

    /** The device's memory in bytes, for the suggested model. */
    fun totalMemoryBytes(): Long

    /** True on Wi-Fi or another connection that is not metered. */
    fun isUnmeteredNetwork(): Boolean

    /** Free space in the models folder, in bytes. */
    fun freeBytes(): Long
}
