package com.rendyhd.vicu.speech

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.math.sqrt
import kotlin.time.TimeSource

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

/** What an open microphone's recording reports, in order (section 3.7). */
sealed interface LocalSpeechEvent {
    /** The person started talking: `min_speech_ms` of speech after a pause. */
    data object SpeechStarted : LocalSpeechEvent

    /** A phrase was cut and waits to be transcribed. */
    data object PhraseCut : LocalSpeechEvent

    /** A phrase was transcribed: its text, or null when it had no words. */
    data class Transcribed(val text: String?) : LocalSpeechEvent

    /** Silence of `end_of_turn_ms` after speech. */
    data object Paused : LocalSpeechEvent
}

/** One recording with local speech as its users see it ([LocalSpeechSession]; fakes in tests). */
interface SpeechRecording {
    val state: StateFlow<LocalSpeechState>

    /** Talking, phrases and pauses as they happen, for a recording with an end of turn; ends with the recording. */
    val events: Flow<LocalSpeechEvent>

    /** Starts listening; false when the model or microphone fails. */
    suspend fun start(): Boolean

    /** Stops recording and waits for the last phrases: the whole transcript. */
    suspend fun finish(): String

    /** Stops and throws everything away. */
    fun cancel()
}

/**
 * One recording with local speech: records, cuts phrases with the voice detector, transcribes each
 * finished phrase at once in the background, in order, and joins the texts (section 3.2). With
 * [endOfTurnMs] it also reports [events] for an open microphone (section 3.7).
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
    /** The pause that ends a turn; null for a recording without turns. */
    private val endOfTurnMs: Long? = null,
) : SpeechRecording {
    private val _state = MutableStateFlow(LocalSpeechState())
    override val state: StateFlow<LocalSpeechState> = _state.asStateFlow()

    private val _events = Channel<LocalSpeechEvent>(Channel.UNLIMITED)

    /** With [endOfTurnMs]: talking, phrases and pauses as they happen; ends with the recording. */
    override val events: Flow<LocalSpeechEvent> = _events.receiveAsFlow()

    private val audio = Channel<FloatArray>(Channel.UNLIMITED)
    private val phrases = Channel<FloatArray>(Channel.UNLIMITED)
    private val texts = mutableListOf<String>()
    private var detector: Job? = null
    private var transcriber: Job? = null
    private val done = CompletableDeferred<String>()
    private var samplesRecorded = 0L
    private val maxSamples = AiContract.limits.audioMaxSeconds * SAMPLE_RATE.toLong()
    private var cancelled = false

    /** Set on the model's thread once it loaded; read by [cancel]. */
    @Volatile
    private var modelHandle = 0L

    /** [finish] came before the recording started (the model was still loading): it never starts. */
    private var stopRequested = false

    /** Starts listening; false (with [LocalSpeechState.error]) when the model or microphone fails. */
    override suspend fun start(): Boolean {
        val native = host.native()
        val vad = host.vad()
        if (vad == 0L) {
            _state.update { it.copy(error = "The voice detector could not be loaded.") }
            done.complete("")
            _events.close()
            return false
        }
        withContext(host.vadThread) { native.vadReset(vad) }
        val vadSettings = VadSettings.fromContract()
        val cutter = PhraseCutter(vadSettings)
        val turns = endOfTurnMs?.let { TurnWatch(vadSettings, it) }
        val window = SpeechEngineNative.VAD_WINDOW_SAMPLES

        detector = scope.launch(host.vadThread) {
            // The samples the detector has not given a probability for yet, in order.
            var pending = FloatArray(window * 4)
            var pendingCount = 0
            var probs = FloatArray(64)
            // Diagnostics for the debug app: does the detector hear speech at all?
            var windowsSeen = 0
            var loudest = 0f
            var feedFailed = false
            for (chunk in audio) {
                if (pendingCount + chunk.size > pending.size) pending = pending.copyOf((pendingCount + chunk.size) * 2)
                chunk.copyInto(pending, pendingCount)
                pendingCount += chunk.size
                if (probs.size < chunk.size / window + 2) probs = FloatArray(chunk.size / window + 2)
                val n = native.vadFeed(vad, chunk, chunk.size, probs)
                if (n < 0 && !feedFailed) {
                    feedFailed = true
                    Logger.w(TAG, "the voice detector refused the audio ($n)")
                }
                var used = 0
                for (i in 0 until n.coerceAtLeast(0)) {
                    if (pendingCount - used < window) break
                    val samples = pending.copyOfRange(used, used + window)
                    used += window
                    loudest = maxOf(loudest, probs[i])
                    if (++windowsSeen % DIAGNOSTIC_WINDOWS == 0) {
                        Logger.d(TAG, "${windowsSeen * window * 1_000L / SAMPLE_RATE} ms heard, speech chance up to $loudest")
                        loudest = 0f
                    }
                    cutter.push(samples, probs[i])?.let { phrase ->
                        Logger.d(TAG, "phrase of ${phrase.size * 1_000L / SAMPLE_RATE} ms cut")
                        _state.update { s -> s.copy(pending = s.pending + 1) }
                        if (turns != null) _events.trySend(LocalSpeechEvent.PhraseCut)
                        phrases.send(phrase)
                    }
                    turns?.push(cutter, probs[i])?.let { _events.trySend(it) }
                }
                if (used > 0) {
                    pending.copyInto(pending, 0, used, pendingCount)
                    pendingCount -= used
                }
            }
            cutter.finish()?.let { phrase ->
                _state.update { s -> s.copy(pending = s.pending + 1) }
                if (turns != null) _events.trySend(LocalSpeechEvent.PhraseCut)
                phrases.send(phrase)
            }
            phrases.close()
        }

        // The model loads (seconds, the first time) while the person already talks: recording and the
        // detector start at once, and the phrases wait for the model, so no words are lost.
        transcriber = scope.launch(host.modelThread) {
            val loading = TimeSource.Monotonic.markNow()
            val model = host.model(modelPath, spec.engineCode)
            modelHandle = model
            Logger.d(TAG, "${spec.id}: ready after ${loading.elapsedNow().inWholeMilliseconds} ms")
            if (model == 0L) {
                _state.update { it.copy(error = "The speech model could not be loaded.") }
                scope.launch { stopRecording() }
                for (phrase in phrases) Unit
                done.complete("")
                _events.close()
                return@launch
            }
            val languageArg = language.takeIf { it != "auto" && spec.takesPrompt }
            val promptArg = prompt.takeIf { spec.takesPrompt }
            for (phrase in phrases) {
                if (cancelled) continue
                val transcribing = TimeSource.Monotonic.markNow()
                val text = native.transcribe(model, phrase, phrase.size, languageArg, promptArg, MAX_THREADS)
                    ?.trim()
                    ?.takeIf { t -> t.any { it.isLetterOrDigit() } }
                Logger.d(TAG, "${spec.id}: ${phrase.size * 1_000L / SAMPLE_RATE} ms of speech took ${transcribing.elapsedNow().inWholeMilliseconds} ms")
                if (text != null) texts += text
                _state.update { s -> s.copy(text = texts.joinToString(" "), pending = (s.pending - 1).coerceAtLeast(0)) }
                if (endOfTurnMs != null) _events.trySend(LocalSpeechEvent.Transcribed(text))
            }
            done.complete(texts.joinToString(" "))
            _events.close()
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
                Logger.w(TAG, "recording stopped: $message")
                _state.update { it.copy(error = message) }
                scope.launch { stopRecording() }
            },
        )
        if (!started) {
            Logger.w(TAG, "the microphone could not start")
            audio.close()
            _state.update { it.copy(error = it.error ?: "The microphone could not be used.") }
            return false
        }
        Logger.d(TAG, "${spec.id}: recording")
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
    override suspend fun finish(): String {
        stopRequested = true
        stopRecording()
        return done.await()
    }

    /** Stops and throws everything away. */
    override fun cancel() {
        cancelled = true
        stopRecording()
        detector?.cancel()
        transcriber?.cancel()
        phrases.close()
        if (modelHandle != 0L) host.native().abort(modelHandle)
        if (!done.isCompleted) done.complete("")
        _events.close()
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
        private const val TAG = "LocalSpeech"

        /** How often the debug app logs what the detector heard: about 3 s. */
        private const val DIAGNOSTIC_WINDOWS = 96
    }
}
