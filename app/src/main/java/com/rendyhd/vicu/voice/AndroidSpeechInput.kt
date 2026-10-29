package com.rendyhd.vicu.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.rendyhd.vicu.speech.MicrophoneAccess
import com.rendyhd.vicu.util.Logger

/**
 * Holding the + on Android: the device's `SpeechRecognizer` (the Google app's on most phones),
 * listening in the app with partial results, so the words show while they are spoken. Needs the
 * microphone permission, which the first hold asks for (never at start); the activity registers
 * the prompt while it is on screen ([attach]) and reports the answer ([onPermissionResult]).
 *
 * Without a recognizer, or after the permission was refused, the hold opens the system's own speech
 * screen ([VoiceAddActivity]), which needs no permission and adds the task by itself.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var launcher: (() -> Unit)? = null
    private var pendingPermission: ((Boolean) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechListener? = null

    override fun availability(): SpeechAvailability = when {
        !SpeechRecognizer.isRecognitionAvailable(context) -> SpeechAvailability.UNAVAILABLE
        granted() -> SpeechAvailability.READY
        // Asked before and refused: Android would not show the prompt again (or only once more).
        prefs.getBoolean(KEY_ASKED, false) -> SpeechAvailability.DENIED
        else -> SpeechAvailability.NEEDS_PERMISSION
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        val launch = launcher
        if (launch == null) {
            onResult(false)
            return
        }
        markAsked()
        pendingPermission = onResult
        launch()
    }

    /** A prompt shown by another screen ([VoiceAddActivity]) counts too: Android may not show it again. */
    fun markAsked() = prefs.edit { putBoolean(KEY_ASKED, true) }

    /**
     * The microphone permission for the local recorder (`AndroidAudioRecorder`), which asks through
     * this same prompt and remembers the same "asked" mark.
     */
    fun microphoneAccess(): MicrophoneAccess = when {
        granted() -> MicrophoneAccess.GRANTED
        prefs.getBoolean(KEY_ASKED, false) -> MicrophoneAccess.DENIED
        else -> MicrophoneAccess.NOT_ASKED
    }

    /** The answer to the prompt [attach]'s launcher showed. */
    fun onPermissionResult(granted: Boolean) {
        val callback = pendingPermission ?: return
        pendingPermission = null
        callback(granted)
    }

    override fun start(listener: SpeechListener) {
        this.listener = listener
        val recognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(Callbacks())
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // A short pause while thinking should not end the task (recognizers may ignore it).
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
        try {
            recognizer.startListening(intent)
        } catch (e: RuntimeException) {
            Logger.w(TAG, "Could not start listening: ${e.message}")
            deliverError(SpeechError.OTHER)
        }
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        listener = null
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
    }

    override fun openSystemSpeech(): Boolean {
        val speechScreen = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        if (speechScreen.resolveActivity(context.packageManager) == null) return false
        context.startActivity(Intent(context, VoiceAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    override fun openSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun attach(launch: () -> Unit) {
        launcher = launch
    }

    /** Withdraws [launch] only if it is still the current one (a new activity may have attached already). */
    fun detach(launch: () -> Unit) {
        if (launcher === launch) launcher = null
        // The activity is gone: a session without a screen has nobody to show its words to.
        cancel()
    }

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The utterance is over; the next [start] may come from inside the callback, so it runs after it. */
    private fun deliverFinal(text: String) {
        val current = listener ?: return
        listener = null
        main.post { current.onFinal(text) }
    }

    private fun deliverError(error: SpeechError) {
        val current = listener ?: return
        listener = null
        main.post { current.onError(error) }
    }

    private inner class Callbacks : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            // Roughly -2 (silence) to 10 dB (loud speech).
            listener?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.firstResult() ?: return
            if (text.isNotBlank()) listener?.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.firstResult()
            if (text.isNullOrBlank()) deliverError(SpeechError.NO_MATCH) else deliverFinal(text)
        }

        override fun onError(error: Int) {
            deliverError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_MATCH
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        SpeechError.NETWORK
                    else -> SpeechError.OTHER
                },
            )
        }

        private fun Bundle.firstResult(): String? =
            getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }

    private companion object {
        const val TAG = "AndroidSpeechInput"
        const val PREFS = "speech_input"
        const val KEY_ASKED = "microphone_asked"
        const val SILENCE_MS = 2_000L
    }
}
