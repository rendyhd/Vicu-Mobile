package com.rendyhd.vicu.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * libvicu_speech.so on a device: it loads, the bundled Silero detector comes out of the assets and
 * answers one probability per 512-sample window, and (when a model and a recording were pushed
 * into the app's external files folder) Whisper transcribes JFK's sample from whisper.cpp:
 *
 *   adb -s emulator-5554 push ggml-base-q5_1.bin jfk.wav /sdcard/Android/data/com.rendyhd.vicu.debug/files/
 */
@RunWith(AndroidJUnit4::class)
class SpeechEngineInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = AndroidSpeechEngineNative()

    @Test
    fun theLibraryLoads() {
        assertTrue(engine.isLoaded)
        val version = engine.version()
        assertNotNull(version)
        assertTrue(version!!, version.startsWith("1.9."))
    }

    @Test
    fun theDetectorAnswersPerWindow() {
        val path = runBlocking { AndroidSpeechStorage(context).vadModelPath() }
        assertNotNull("voice detector copied out of the assets", path)
        assertEquals(885_098L, File(path!!).length())

        val vad = engine.vadOpen(path, 2)
        assertNotEquals(0L, vad)
        try {
            val probs = FloatArray(64)
            val silence = FloatArray(SAMPLE_RATE)
            val quiet = engine.vadFeed(vad, silence, silence.size, probs)
            assertEquals(31, quiet)
            // Silence stays well under the speech threshold (0.5) in every window.
            assertTrue("silence: ${probs.take(quiet)}", probs.take(quiet).all { it in 0f..0.2f })

            val tone = FloatArray(SAMPLE_RATE) { (0.5 * sin(2 * PI * 440.0 * it / SAMPLE_RATE)).toFloat() }
            val toned = engine.vadFeed(vad, tone, tone.size, probs)
            assertEquals(31, toned)
            assertTrue("tone: ${probs.take(toned)}", probs.take(toned).all { it in 0f..1f })

            engine.vadReset(vad)
            assertEquals(0, engine.vadFeed(vad, silence, 511, probs))
        } finally {
            engine.vadClose(vad)
        }
    }

    @Test
    fun whisperTranscribesJfk() {
        val folder = context.getExternalFilesDir(null)
        val model = File(folder, "ggml-base-q5_1.bin")
        val wav = File(folder, "jfk.wav")
        assumeTrue("push ggml-base-q5_1.bin and jfk.wav to $folder", model.isFile && wav.isFile)

        val samples = readPcm16Wav(wav)

        // The detector hears the speech in it.
        val vadPath = runBlocking { AndroidSpeechStorage(context).vadModelPath() }!!
        val vad = engine.vadOpen(vadPath, 2)
        val probs = FloatArray(samples.size / SpeechEngineNative.VAD_WINDOW_SAMPLES)
        val windows = try {
            engine.vadFeed(vad, samples, samples.size, probs)
        } finally {
            engine.vadClose(vad)
        }
        val speech = probs.take(windows).count { it > 0.5f }
        assertTrue("speech windows: $speech of $windows", speech > windows / 2)

        val handle = engine.modelOpen(model.absolutePath, SpeechEngineNative.ENGINE_WHISPER, false)
        assertNotEquals(0L, handle)
        try {
            val started = System.nanoTime()
            val text = engine.transcribe(handle, samples, samples.size, "en", null, 4)
            val millis = (System.nanoTime() - started) / 1_000_000
            println("Transcribed ${samples.size / SAMPLE_RATE} s in $millis ms: $text")
            assertNotNull(text)
            val words = text!!.lowercase().replace(Regex("[^a-z ]"), "")
            assertTrue(text, words.contains("ask not what your country can do for you"))
        } finally {
            engine.modelClose(handle)
        }
    }

    /** The samples of a 16 kHz mono 16-bit WAV file. */
    private fun readPcm16Wav(file: File): FloatArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(12)
        while (bytes.remaining() >= 8) {
            val id = ByteArray(4).also { bytes.get(it) }.decodeToString()
            val size = bytes.int
            if (id == "data") {
                val count = minOf(size, bytes.remaining()) / 2
                return FloatArray(count) { bytes.short / 32768f }
            }
            bytes.position(bytes.position() + size + (size and 1))
        }
        error("no data chunk in $file")
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
