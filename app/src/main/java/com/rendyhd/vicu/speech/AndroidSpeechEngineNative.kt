package com.rendyhd.vicu.speech

import com.rendyhd.vicu.util.Logger

/**
 * The JNI entry points of `libvicu_speech.so` (`native/speech/vicu_speech_jni.cpp`). The native
 * names follow this class and method names, so R8 keeps both (`proguard-rules.pro`).
 */
internal object VicuSpeechJni {
    @JvmStatic external fun version(): String?
    @JvmStatic external fun vadOpen(path: String, threads: Int): Long
    @JvmStatic external fun vadFeed(vad: Long, samples: FloatArray, count: Int, outProbs: FloatArray): Int
    @JvmStatic external fun vadReset(vad: Long)
    @JvmStatic external fun vadClose(vad: Long)
    @JvmStatic external fun modelOpen(path: String, engine: Int, useGpu: Boolean): Long
    @JvmStatic external fun transcribe(
        model: Long,
        samples: FloatArray,
        count: Int,
        language: String?,
        prompt: String?,
        threads: Int,
    ): String?
    @JvmStatic external fun abort(model: Long)
    @JvmStatic external fun modelClose(model: Long)
}

/**
 * [SpeechEngineNative] over JNI. The library is built for arm64-v8a, armeabi-v7a and x86_64; on
 * any other processor (32-bit x86) it is missing, [isLoaded] is false and every call answers
 * "nothing" (0, -1 or null) instead of throwing. Loading happens on first use, not when Koin
 * builds this.
 */
class AndroidSpeechEngineNative : SpeechEngineNative {

    override val isLoaded: Boolean by lazy {
        try {
            System.loadLibrary(LIBRARY)
            true
        } catch (e: UnsatisfiedLinkError) {
            Logger.w(TAG, "No speech library for this device: ${e.message}")
            false
        } catch (e: SecurityException) {
            Logger.w(TAG, "Speech library not allowed: ${e.message}")
            false
        }
    }

    override fun version(): String? = if (isLoaded) VicuSpeechJni.version() else null

    override fun vadOpen(path: String, threads: Int): Long =
        if (isLoaded) VicuSpeechJni.vadOpen(path, threads) else 0L

    override fun vadFeed(vad: Long, samples: FloatArray, count: Int, outProbs: FloatArray): Int {
        if (!isLoaded || vad == 0L || count < 0 || count > samples.size) return -1
        return VicuSpeechJni.vadFeed(vad, samples, count, outProbs)
    }

    override fun vadReset(vad: Long) {
        if (isLoaded && vad != 0L) VicuSpeechJni.vadReset(vad)
    }

    override fun vadClose(vad: Long) {
        if (isLoaded && vad != 0L) VicuSpeechJni.vadClose(vad)
    }

    override fun modelOpen(path: String, engine: Int, useGpu: Boolean): Long =
        if (isLoaded) VicuSpeechJni.modelOpen(path, engine, useGpu) else 0L

    override fun transcribe(
        model: Long,
        samples: FloatArray,
        count: Int,
        language: String?,
        prompt: String?,
        threads: Int,
    ): String? {
        if (!isLoaded || model == 0L || count <= 0 || count > samples.size) return null
        return VicuSpeechJni.transcribe(model, samples, count, language, prompt, threads)
    }

    override fun abort(model: Long) {
        if (isLoaded && model != 0L) VicuSpeechJni.abort(model)
    }

    override fun modelClose(model: Long) {
        if (isLoaded && model != 0L) VicuSpeechJni.modelClose(model)
    }

    private companion object {
        const val TAG = "SpeechEngine"
        const val LIBRARY = "vicu_speech"
    }
}
