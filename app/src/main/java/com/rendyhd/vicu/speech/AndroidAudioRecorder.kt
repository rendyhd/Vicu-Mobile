package com.rendyhd.vicu.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresApi
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.voice.AndroidSpeechInput
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The microphone for local speech: `AudioRecord` from the voice-recognition source (no automatic
 * gain or noise effects that would change what the model hears), 16 kHz mono 16-bit, read in
 * 100 ms pieces on its own thread and handed on as samples in [-1, 1]. The permission is the one
 * holding + already asks for, through [permission]'s prompt.
 */
class AndroidAudioRecorder(private val permission: AndroidSpeechInput) : AudioRecorder {
    private val lock = Any()
    private var session: Session? = null

    override fun access(): MicrophoneAccess = permission.microphoneAccess()

    override fun requestAccess(onResult: (Boolean) -> Unit) = permission.requestPermission(onResult)

    @SuppressLint("MissingPermission") // access() is checked first; a refusal in between throws and is caught.
    override fun start(onSamples: (FloatArray) -> Unit, onError: (String) -> Unit): Boolean = synchronized(lock) {
        if (session != null) return false
        if (access() != MicrophoneAccess.GRANTED) return false
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes, CHUNK_SAMPLES * 2 * 4),
            )
        } catch (e: IllegalArgumentException) {
            Logger.w(TAG, "No recorder: ${e.message}")
            return false
        } catch (e: SecurityException) {
            Logger.w(TAG, "Microphone not allowed: ${e.message}")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "Could not start recording: ${e.message}")
            record.release()
            return false
        }
        // Another app holding the microphone leaves the recorder stopped.
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return false
        }
        val started = Session(record, onSamples, onError)
        session = started
        started.start()
        true
    }

    override fun stop() {
        val current = synchronized(lock) { session.also { session = null } } ?: return
        current.finish()
    }

    /** One recording: its thread reads until [finish], then stops and releases the recorder. */
    private inner class Session(
        private val record: AudioRecord,
        private val onSamples: (FloatArray) -> Unit,
        private val onError: (String) -> Unit,
    ) {
        @Volatile
        private var running = true

        private val failed = AtomicBoolean(false)

        private val thread = Thread(::readLoop, "vicu-audio-recorder").apply { isDaemon = true }
        private var silencedWatch: Any? = null

        fun start() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) silencedWatch = watchSilenced()
            thread.start()
        }

        /** Returns once no more samples will be delivered (unless called from that delivery itself). */
        fun finish() {
            running = false
            if (Thread.currentThread() !== thread) {
                try {
                    thread.join(JOIN_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        private fun readLoop() {
            val pcm = ShortArray(CHUNK_SAMPLES)
            try {
                while (running) {
                    val read = record.read(pcm, 0, pcm.size)
                    if (read < 0) {
                        fail("The microphone stopped (error $read).")
                        break
                    }
                    if (read == 0 || !running) continue
                    val samples = FloatArray(read) { pcm[it] / 32768f }
                    onSamples(samples)
                }
            } catch (e: RuntimeException) {
                fail("The microphone stopped: ${e.message}")
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) unwatchSilenced()
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                }
                record.release()
                synchronized(lock) { if (session === this) session = null }
            }
        }

        private fun fail(message: String) {
            if (!failed.compareAndSet(false, true)) return
            running = false
            Logger.w(TAG, message)
            onError(message)
        }

        /** Android 10 and later silence a recording when another app (a call) takes the microphone. */
        @RequiresApi(Build.VERSION_CODES.Q)
        private fun watchSilenced(): Any {
            val callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return
                    if (mine.isClientSilenced && running) fail("Another app is using the microphone.")
                }
            }
            record.registerAudioRecordingCallback(callbackExecutor, callback)
            return callback
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun unwatchSilenced() {
            val callback = silencedWatch as? AudioManager.AudioRecordingCallback ?: return
            try {
                record.unregisterAudioRecordingCallback(callback)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private companion object {
        const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = SAMPLE_RATE / 10
        const val JOIN_MILLIS = 2_000L
        val callbackExecutor by lazy {
            Executors.newSingleThreadExecutor { Thread(it, "vicu-audio-watch").apply { isDaemon = true } }
        }
    }
}
