package com.rendyhd.vicu.speech

import com.rendyhd.vicu.ai.AiContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** What the voice card shows while local speech listens (`docs/ai-assistant-v1.md`, section 3). */
data class LocalSpeechState(
    val recording: Boolean = false,
    /** The voice's loudness, 0 to 1. */
    val level: Float = 0f,
    /** The transcript so far: the finished phrases' texts. */
    val text: String = "",
    /** Phrases cut but not transcribed yet. */
    val pending: Int = 0,
    /** Seconds recorded. */
    val seconds: Int = 0,
    val error: String? = null,
)

/**
 * Keeps the speech model and voice detector open between recordings (loading a large model takes
 * seconds) and runs every native call on one thread of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeechEngineHost(
    private val native: SpeechEngineNative,
    private val storage: SpeechStorage,
    io: CoroutineDispatcher,
) {
    /** One thread for the model, one for the detector: transcribing a phrase never holds up the detector. */
    val modelThread: CoroutineDispatcher = io.limitedParallelism(1)
    val vadThread: CoroutineDispatcher = io.limitedParallelism(1)

    private val lock = Mutex()
    private var modelPath: String? = null
    private var model = 0L
    private var vad = 0L

    val isAvailable: Boolean get() = native.isLoaded

    suspend fun vad(): Long = lock.withLock {
        if (vad != 0L) return vad
        val path = storage.vadModelPath() ?: return 0L
        vad = withContext(vadThread) { native.vadOpen(path, 1) }
        vad
    }

    suspend fun model(path: String, engine: Int): Long = lock.withLock {
        if (model != 0L && modelPath == path) return model
        val old = model
        model = 0L
        modelPath = null
        withContext(modelThread) {
            if (old != 0L) native.modelClose(old)
            val opened = native.modelOpen(path, engine, useGpu = true).takeIf { it != 0L }
                ?: native.modelOpen(path, engine, useGpu = false)
            model = opened
        }
        if (model != 0L) modelPath = path
        model
    }

    /** Frees the model (memory warning, model deleted, settings changed). */
    suspend fun release() = lock.withLock {
        val old = model
        model = 0L
        modelPath = null
        if (old != 0L) withContext(modelThread) { native.modelClose(old) }
    }

    fun native(): SpeechEngineNative = native
}

/**
 * One recording with local speech: records, cuts phrases with the voice detector, transcribes each
 * finished phrase at once in the background, in order, and joins the texts (section 3.2).
 */
class LocalSpeechSession(
    private val host: SpeechEngineHost,
    private val recorder: AudioRecorder,
    private val scope: CoroutineScope,
    private val modelPath: String,
    private val spec: SpeechModelSpec,
    /** `auto` or an ISO 639-1 code. */
    private val language: String,
    /** The vocabulary hint (section 3.4), or null. */
    private val prompt: String?,
) {
    private val _state = MutableStateFlow(LocalSpeechState())
    val state: StateFlow<LocalSpeechState> = _state.asStateFlow()

    private val audio = Channel<FloatArray>(Channel.UNLIMITED)
    private val phrases = Channel<FloatArray>(Channel.UNLIMITED)
    private val texts = mutableListOf<String>()
    private var detector: Job? = null
    private var transcriber: Job? = null
    private val done = CompletableDeferred<String>()
    private var samplesRecorded = 0L
    private val maxSamples = AiContract.limits.audioMaxSeconds * SAMPLE_RATE.toLong()
    private var cancelled = false
    private var modelHandle = 0L

    /** [finish] came before the recording started (the model was still loading): it never starts. */
    private var stopRequested = false

    /** Starts listening; false (with [LocalSpeechState.error]) when the model or microphone fails. */
    suspend fun start(): Boolean {
        val native = host.native()
        val vad = host.vad()
        val model = host.model(modelPath, spec.engineCode)
        modelHandle = model
        if (vad == 0L || model == 0L) {
            _state.update { it.copy(error = "The speech model could not be loaded.") }
            done.complete("")
            return false
        }
        withContext(host.vadThread) { native.vadReset(vad) }
        val cutter = PhraseCutter(VadSettings.fromContract())
        val window = SpeechEngineNative.VAD_WINDOW_SAMPLES

        detector = scope.launch(host.vadThread) {
            // The samples the detector has not given a probability for yet, in order.
            var pending = FloatArray(window * 4)
            var pendingCount = 0
            var probs = FloatArray(64)
            for (chunk in audio) {
                if (pendingCount + chunk.size > pending.size) pending = pending.copyOf((pendingCount + chunk.size) * 2)
                chunk.copyInto(pending, pendingCount)
                pendingCount += chunk.size
                if (probs.size < chunk.size / window + 2) probs = FloatArray(chunk.size / window + 2)
                val n = native.vadFeed(vad, chunk, chunk.size, probs)
                var used = 0
                for (i in 0 until n.coerceAtLeast(0)) {
                    if (pendingCount - used < window) break
                    val samples = pending.copyOfRange(used, used + window)
                    used += window
                    cutter.push(samples, probs[i])?.let { phrase ->
                        _state.update { s -> s.copy(pending = s.pending + 1) }
                        phrases.send(phrase)
                    }
                }
                if (used > 0) {
                    pending.copyInto(pending, 0, used, pendingCount)
                    pendingCount -= used
                }
            }
            cutter.finish()?.let { phrase ->
                _state.update { s -> s.copy(pending = s.pending + 1) }
                phrases.send(phrase)
            }
            phrases.close()
        }

        transcriber = scope.launch(host.modelThread) {
            val languageArg = language.takeIf { it != "auto" && spec.takesPrompt }
            val promptArg = prompt.takeIf { spec.takesPrompt }
            for (phrase in phrases) {
                if (cancelled) continue
                val text = native.transcribe(model, phrase, phrase.size, languageArg, promptArg, MAX_THREADS)
                    ?.trim()
                    ?.takeIf { t -> t.any { it.isLetterOrDigit() } }
                if (text != null) texts += text
                _state.update { s -> s.copy(text = texts.joinToString(" "), pending = (s.pending - 1).coerceAtLeast(0)) }
            }
            done.complete(texts.joinToString(" "))
        }

        if (stopRequested || cancelled) {
            audio.close()
            return true
        }
        val started = recorder.start(
            onSamples = { samples ->
                if (samplesRecorded >= maxSamples) return@start
                samplesRecorded += samples.size
                audio.trySend(samples)
                val level = rms(samples)
                _state.update { it.copy(level = level, seconds = (samplesRecorded / SAMPLE_RATE).toInt()) }
                if (samplesRecorded >= maxSamples) scope.launch { stopRecording() }
            },
            onError = { message ->
                _state.update { it.copy(error = message) }
                scope.launch { stopRecording() }
            },
        )
        if (!started) {
            audio.close()
            _state.update { it.copy(error = it.error ?: "The microphone could not be used.") }
            return false
        }
        _state.update { it.copy(recording = true) }
        return true
    }

    private fun stopRecording() {
        if (!_state.value.recording) return
        recorder.stop()
        audio.close()
        _state.update { it.copy(recording = false, level = 0f) }
    }

    /** Stops recording and waits for the last phrases: the whole transcript. */
    suspend fun finish(): String {
        stopRequested = true
        stopRecording()
        return done.await()
    }

    /** Stops and throws everything away. */
    fun cancel() {
        cancelled = true
        stopRecording()
        detector?.cancel()
        transcriber?.cancel()
        phrases.close()
        if (modelHandle != 0L) host.native().abort(modelHandle)
        if (!done.isCompleted) done.complete("")
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s * s
        return (sqrt(sum / samples.size) * 6).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MAX_THREADS = 4
    }
}
