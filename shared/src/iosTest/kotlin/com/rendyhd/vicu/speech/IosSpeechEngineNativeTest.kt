package com.rendyhd.vicu.speech

import com.rendyhd.vicu.util.TestPaths
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The speech library linked through cinterop: the bundled Silero detector, read from this
 * repository (the simulator runs the tests on this Mac), answers one probability per window.
 * Every handle is closed before the test ends (ggml's teardown asserts on open ones at exit).
 */
class IosSpeechEngineNativeTest {
    private val engine = IosSpeechEngineNative()
    private val vadModel = "${TestPaths.REPO_ROOT}/native/speech/models/ggml-silero-v6.2.0.bin"

    @Test
    fun the_library_is_linked() {
        assertTrue(engine.isLoaded)
        val version = assertNotNull(engine.version())
        assertTrue(version.startsWith("1.9."), version)
    }

    @Test
    fun the_detector_answers_per_window_and_keeps_the_rest() {
        val vad = engine.vadOpen(vadModel, 1)
        assertNotEquals(0L, vad)
        try {
            val probs = FloatArray(64)

            // 1 s: 31 whole windows of 512, 128 samples kept for the next call.
            val silence = FloatArray(SAMPLE_RATE)
            val quiet = engine.vadFeed(vad, silence, silence.size, probs)
            assertEquals(31, quiet)
            // Silence stays well under the speech threshold (0.5) in every window.
            assertTrue(probs.take(quiet).all { it in 0f..0.2f }, "silence: ${probs.take(quiet)}")

            // 128 kept + 16,000 = 16,128 samples: 31 windows again, 256 kept.
            val tone = FloatArray(SAMPLE_RATE) { (0.5 * sin(2 * PI * 440.0 * it / SAMPLE_RATE)).toFloat() }
            val toned = engine.vadFeed(vad, tone, tone.size, probs)
            assertEquals(31, toned)
            assertTrue(probs.take(toned).all { it in 0f..1f }, "tone: ${probs.take(toned)}")

            // 256 kept + 256 = one window; after a reset nothing is kept.
            assertEquals(1, engine.vadFeed(vad, silence, 256, probs))
            engine.vadReset(vad)
            assertEquals(0, engine.vadFeed(vad, silence, 511, probs))
        } finally {
            engine.vadClose(vad)
        }
    }

    @Test
    fun missing_files_and_handles_answer_nothing() {
        assertEquals(0L, engine.vadOpen("${TestPaths.REPO_ROOT}/no-such-model.bin", 1))
        assertEquals(0L, engine.modelOpen("${TestPaths.REPO_ROOT}/no-such-model.bin", SpeechEngineNative.ENGINE_WHISPER, false))
        assertEquals(-1, engine.vadFeed(0L, FloatArray(512), 512, FloatArray(1)))
        assertNull(engine.transcribe(0L, FloatArray(16), 16, null, null, 1))
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
