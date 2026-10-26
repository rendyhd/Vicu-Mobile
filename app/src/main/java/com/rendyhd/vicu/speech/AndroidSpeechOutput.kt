package com.rendyhd.vicu.speech

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.rendyhd.vicu.util.Logger
import java.util.Locale

/**
 * The device voice (`TextToSpeech`), created on the first [speak] and kept for the life of the app.
 * Text spoken before the engine is ready waits for it; [onDone] callbacks run on the main thread,
 * once each, when the text was spoken, failed or was stopped.
 */
class AndroidSpeechOutput(private val context: Context) : SpeechOutput {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: Request? = null
    private var nextId = 0L

    /** Utterance id of a request's last piece to its callback. */
    private val callbacks = HashMap<String, () -> Unit>()

    private class Request(val text: String, val languageTag: String?, val onDone: () -> Unit)

    override val isAvailable: Boolean
        get() = !failed && hasEngine

    override val playsPrivately: Boolean
        get() {
            val audio = context.getSystemService(AudioManager::class.java) ?: return false
            return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONES }
        }

    private val hasEngine: Boolean by lazy {
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
    }

    override fun speak(text: String, languageTag: String?, onDone: () -> Unit) {
        val request = Request(text, languageTag, onDone)
        val replaced: Request?
        synchronized(lock) {
            if (failed) {
                main.post(onDone)
                return
            }
            if (!ready) {
                replaced = pending
                pending = request
                ensureEngine()
            } else {
                replaced = null
                say(request)
            }
        }
        replaced?.let { main.post(it.onDone) }
    }

    override fun stop() {
        val done: List<() -> Unit>
        synchronized(lock) {
            done = callbacks.values.toList() + listOfNotNull(pending?.onDone)
            callbacks.clear()
            pending = null
            tts?.stop()
        }
        done.forEach { main.post(it) }
    }

    /** Under [lock]. */
    private fun ensureEngine() {
        if (tts != null) return
        val engine = TextToSpeech(context.applicationContext) { status -> onInit(status) }
        engine.setOnUtteranceProgressListener(Progress())
        // Without an engine, the answer can come before the constructor returns.
        if (failed) engine.shutdown() else tts = engine
    }

    private fun onInit(status: Int) {
        val waiting: Request?
        val gaveUp: Boolean
        synchronized(lock) {
            waiting = pending
            pending = null
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                waiting?.let(::say)
            } else {
                Logger.w(TAG, "No speech engine (status $status)")
                failed = true
                tts?.shutdown()
                tts = null
            }
            gaveUp = failed
        }
        if (gaveUp) waiting?.let { main.post(it.onDone) }
    }

    /** Under [lock], with the engine ready. */
    private fun say(request: Request) {
        val engine = tts
        if (engine == null) {
            main.post(request.onDone)
            return
        }
        val locale = request.languageTag?.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
        val pieces = pieces(request.text, TextToSpeech.getMaxSpeechInputLength())
        if (pieces.isEmpty()) {
            main.post(request.onDone)
            return
        }
        pieces.forEachIndexed { index, piece ->
            val id = "vicu-${nextId++}"
            if (index == pieces.lastIndex) callbacks[id] = request.onDone
            val queue = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(piece, queue, null, id) != TextToSpeech.SUCCESS && index == pieces.lastIndex) {
                callbacks.remove(id)
                main.post(request.onDone)
            }
        }
    }

    private fun finished(utteranceId: String?) {
        val done = synchronized(lock) { utteranceId?.let { callbacks.remove(it) } } ?: return
        main.post(done)
    }

    private inner class Progress : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = finished(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
    }

    internal companion object {
        /** Outputs the phone's microphone cannot hear. */
        private val HEADPHONES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        private const val TAG = "SpeechOutput"

        /** [text] cut at spaces into pieces the engine accepts. */
        fun pieces(text: String, max: Int): List<String> {
            val result = mutableListOf<String>()
            var rest = text.trim()
            while (rest.isNotEmpty()) {
                if (rest.length <= max) {
                    result += rest
                    break
                }
                val cut = rest.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
                result += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            return result.filter { it.isNotEmpty() }
        }
    }
}
