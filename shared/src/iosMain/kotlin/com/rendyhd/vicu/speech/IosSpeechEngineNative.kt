package com.rendyhd.vicu.speech

import cnames.structs.vs_model
import cnames.structs.vs_vad
import com.rendyhd.vicu.speech.cinterop.vs_abort
import com.rendyhd.vicu.speech.cinterop.vs_model_close
import com.rendyhd.vicu.speech.cinterop.vs_model_open
import com.rendyhd.vicu.speech.cinterop.vs_transcribe
import com.rendyhd.vicu.speech.cinterop.vs_vad_close
import com.rendyhd.vicu.speech.cinterop.vs_vad_feed
import com.rendyhd.vicu.speech.cinterop.vs_vad_open
import com.rendyhd.vicu.speech.cinterop.vs_vad_reset
import com.rendyhd.vicu.speech.cinterop.vs_version
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned

/**
 * [SpeechEngineNative] over cinterop: `libvicu_speech_all.a` (iosApp/scripts/build-speech.sh) is
 * linked into the shared framework, so it is always there. Handles are the C pointers as numbers.
 */
class IosSpeechEngineNative : SpeechEngineNative {
    override val isLoaded: Boolean = true

    override fun version(): String? = vs_version()?.toKString()

    override fun vadOpen(path: String, threads: Int): Long = vs_vad_open(path, threads).toLong()

    override fun vadFeed(vad: Long, samples: FloatArray, count: Int, outProbs: FloatArray): Int {
        val detector = vad.asVad() ?: return -1
        if (count < 0 || count > samples.size) return -1
        if (count == 0) return 0
        // The detector copies the samples it keeps; pinning only spans the call.
        return samples.usePinned { input ->
            if (outProbs.isEmpty()) {
                vs_vad_feed(detector, input.addressOf(0), count, null, 0)
            } else {
                outProbs.usePinned { out ->
                    vs_vad_feed(detector, input.addressOf(0), count, out.addressOf(0), outProbs.size)
                }
            }
        }
    }

    override fun vadReset(vad: Long) {
        vad.asVad()?.let(::vs_vad_reset)
    }

    override fun vadClose(vad: Long) {
        vad.asVad()?.let(::vs_vad_close)
    }

    override fun modelOpen(path: String, engine: Int, useGpu: Boolean): Long =
        vs_model_open(path, engine, if (useGpu) 1 else 0).toLong()

    override fun transcribe(
        model: Long,
        samples: FloatArray,
        count: Int,
        language: String?,
        prompt: String?,
        threads: Int,
    ): String? {
        val handle = model.asModel() ?: return null
        if (count <= 0 || count > samples.size) return null
        val text = ByteArray(MAX_TEXT_BYTES)
        val length = samples.usePinned { input ->
            text.usePinned { out ->
                vs_transcribe(handle, input.addressOf(0), count, language, prompt, threads, out.addressOf(0), text.size)
            }
        }
        if (length < 0) return null
        return text.decodeToString(0, length)
    }

    override fun abort(model: Long) {
        model.asModel()?.let(::vs_abort)
    }

    override fun modelClose(model: Long) {
        model.asModel()?.let(::vs_model_close)
    }

    private fun Long.asVad(): CPointer<vs_vad>? = if (this == 0L) null else toCPointer()

    private fun Long.asModel(): CPointer<vs_model>? = if (this == 0L) null else toCPointer()

    private companion object {
        /** Longer than any phrase's text (a phrase is at most 30 s of speech). */
        const val MAX_TEXT_BYTES = 64 * 1024
    }
}
