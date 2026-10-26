package com.rendyhd.vicu.speech

import com.rendyhd.vicu.util.Logger
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioApplicationRecordPermissionDenied
import platform.AVFAudio.AVAudioApplicationRecordPermissionGranted
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterInputStatus_NoDataNow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSRecursiveLock
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The microphone for local speech: `AVAudioEngine`'s input, converted by `AVAudioConverter` to
 * 16 kHz mono float samples and handed on from the tap's thread. The session is the one the hold-
 * to-talk recognizer uses (record, measurement mode: no voice processing, other audio ducked).
 * A phone call or a route change that stops the engine ends the recording with [start]'s onError.
 * Building this touches nothing (CLAUDE.md, gotcha 19).
 */
class IosAudioRecorder : AudioRecorder {
    /** Guards [current]; recursive, so [stop] may be called from inside onSamples. */
    private val lock = NSRecursiveLock()
    private var current: Recording? = null

    override fun access(): MicrophoneAccess = when (AVAudioApplication.sharedInstance.recordPermission) {
        AVAudioApplicationRecordPermissionGranted -> MicrophoneAccess.GRANTED
        AVAudioApplicationRecordPermissionDenied -> MicrophoneAccess.DENIED
        else -> MicrophoneAccess.NOT_ASKED
    }

    override fun requestAccess(onResult: (Boolean) -> Unit) {
        AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted ->
            dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
        }
    }

    override fun start(onSamples: (FloatArray) -> Unit, onError: (String) -> Unit): Boolean {
        lock.lock()
        try {
            if (current != null) return false
            if (access() != MicrophoneAccess.GRANTED) return false
            val recording = Recording(onSamples, onError)
            if (!recording.begin()) {
                recording.end()
                return false
            }
            current = recording
            return true
        } finally {
            lock.unlock()
        }
    }

    override fun stop() {
        lock.lock()
        val recording = current
        current = null
        try {
            recording?.end()
        } finally {
            lock.unlock()
        }
    }

    private inner class Recording(
        private val onSamples: (FloatArray) -> Unit,
        private val onError: (String) -> Unit,
    ) {
        private val engine = AVAudioEngine()
        private var tapped = false
        private var failed = false
        private val observers = mutableListOf<NSObjectProtocol>()

        fun begin(): Boolean {
            val session = AVAudioSession.sharedInstance()
            val sessionReady = memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                session.setCategory(
                    AVAudioSessionCategoryRecord,
                    mode = AVAudioSessionModeMeasurement,
                    options = AVAudioSessionCategoryOptionDuckOthers,
                    error = error.ptr,
                ) && session.setActive(true, error = error.ptr).also {
                    if (!it) Logger.w(TAG, "Audio session: ${error.value?.localizedDescription}")
                }
            }
            if (!sessionReady) return false

            val input = engine.inputNode
            val inputFormat = input.outputFormatForBus(0u)
            if (inputFormat.sampleRate <= 0.0 || inputFormat.channelCount == 0u) {
                Logger.w(TAG, "No microphone input")
                return false
            }
            val target = AVAudioFormat(
                commonFormat = AVAudioPCMFormatFloat32,
                sampleRate = SAMPLE_RATE,
                channels = 1u,
                interleaved = false,
            )
            val converter = AVAudioConverter(fromFormat = inputFormat, toFormat = target)
            val ratio = SAMPLE_RATE / inputFormat.sampleRate

            input.installTapOnBus(0u, bufferSize = TAP_FRAMES, format = inputFormat) { buffer, _ ->
                if (buffer != null) deliver(convert(converter, target, buffer, ratio))
            }
            tapped = true

            val center = NSNotificationCenter.defaultCenter
            observers += center.addObserverForName(AVAudioSessionInterruptionNotification, session, NSOperationQueue.mainQueue) { _ ->
                fail("The recording was interrupted.")
            }
            observers += center.addObserverForName(AVAudioEngineConfigurationChangeNotification, engine, NSOperationQueue.mainQueue) { _ ->
                if (!engine.running) fail("The microphone changed.")
            }

            engine.prepare()
            val started = memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                engine.startAndReturnError(error.ptr).also {
                    if (!it) Logger.w(TAG, "Could not start recording: ${error.value?.localizedDescription}")
                }
            }
            return started
        }

        /** Stops the engine and gives the audio session back. */
        fun end() {
            observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
            observers.clear()
            if (tapped) engine.inputNode.removeTapOnBus(0u)
            tapped = false
            engine.stop()
            AVAudioSession.sharedInstance().setActive(
                false,
                withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                error = null,
            )
        }

        /** On the tap's thread; nothing is delivered once [stop] has returned. */
        private fun deliver(samples: FloatArray?) {
            if (samples == null || samples.isEmpty()) return
            lock.lock()
            try {
                if (current === this) onSamples(samples)
            } finally {
                lock.unlock()
            }
        }

        private fun fail(message: String) {
            lock.lock()
            val mine = current === this && !failed
            if (mine) {
                failed = true
                current = null
                end()
            }
            lock.unlock()
            if (mine) {
                Logger.w(TAG, message)
                onError(message)
            }
        }

        private fun convert(converter: AVAudioConverter, target: AVAudioFormat, buffer: AVAudioPCMBuffer, ratio: Double): FloatArray? {
            val capacity = (buffer.frameLength.toDouble() * ratio).toUInt() + 32u
            val out = AVAudioPCMBuffer(pCMFormat = target, frameCapacity = capacity)
            var given = false
            val error = memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                converter.convertToBuffer(out, error.ptr) { _, status ->
                    if (given) {
                        status?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                        null
                    } else {
                        given = true
                        status?.pointed?.value = AVAudioConverterInputStatus_HaveData
                        buffer
                    }
                }
                error.value
            }
            if (error != null) {
                Logger.w(TAG, "Could not convert audio: ${error.localizedDescription}")
                return null
            }
            val frames = out.frameLength.toInt()
            val channel = out.floatChannelData?.get(0) ?: return null
            return FloatArray(frames) { channel[it] }
        }
    }

    private companion object {
        const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16_000.0
        const val TAP_FRAMES = 4096u
    }
}
