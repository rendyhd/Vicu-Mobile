package com.rendyhd.vicu.voice

/** Whether the app can listen in-app right now. */
enum class SpeechAvailability {
    /** The recognizer is there and the microphone (and on iOS speech recognition) is allowed. */
    READY,

    /** Not asked yet: the first hold asks. */
    NEEDS_PERMISSION,

    /** The user said no; only Settings can change it. */
    DENIED,

    /** The device has no in-app recognizer (or none for the device language). */
    UNAVAILABLE,
}

enum class SpeechError {
    /** Nothing was understood (silence, or no match). */
    NO_MATCH,

    /** The permission was taken away while listening. */
    PERMISSION,

    /** The recognizer needs a connection it does not have. */
    NETWORK,

    /** Anything else: busy recognizer, audio error. */
    OTHER,
}

/** What a recognizer reports while it listens. Called on the main thread. */
interface SpeechListener {
    /** The words heard so far in this utterance (each call replaces the previous one). */
    fun onPartial(text: String)

    /** The loudness of the voice, 0 to 1, for the microphone's pulse. */
    fun onLevel(level: Float)

    /** The utterance is over: its words. Nothing follows until the next [SpeechInput.start]. */
    fun onFinal(text: String)

    /** The utterance ended without words. Nothing follows until the next [SpeechInput.start]. */
    fun onError(error: SpeechError)
}

/**
 * An in-app speech recognizer: the platform seam behind holding the + button
 * (`docs/voice-input.md`). Android implements it with `SpeechRecognizer`, iOS with
 * `SFSpeechRecognizer` and `AVAudioEngine` in Swift (`AppleSpeechBridge`). Every call is made on the
 * main thread.
 */
interface SpeechInput {
    fun availability(): SpeechAvailability

    /** Shows the system prompt(s) for the microphone; [onResult] says whether listening is now allowed. */
    fun requestPermission(onResult: (Boolean) -> Unit)

    /** Starts listening; [listener] gets the words until [SpeechListener.onFinal] or [SpeechListener.onError]. */
    fun start(listener: SpeechListener)

    /** Stops listening and finishes: [SpeechListener.onFinal] or [SpeechListener.onError] follows. */
    fun stop()

    /** Stops listening; nothing follows. */
    fun cancel()

    /**
     * Without an in-app recognizer (or the microphone permission): the system's own speech screen,
     * which adds the task by itself. False when the platform has none.
     */
    fun openSystemSpeech(): Boolean

    /** The app's page in the system settings, where the permission can be given again. */
    fun openSettings()

    /**
     * How long, after [stop], to wait for the last words before adding what was heard. A
     * recognizer that transcribes on the device after the recording ends (local speech) waits longer.
     */
    val finalWaitMs: Long get() = 1_500L
}
