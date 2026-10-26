package com.rendyhd.vicu.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SpeechCoreTest {
    @Test
    fun sha256KnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256().hexDigest())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256().apply { update("abc".encodeToByteArray()) }.hexDigest())
        val million = Sha256()
        val chunk = ByteArray(1000) { 'a'.code.toByte() }
        repeat(1000) { million.update(chunk) }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", million.hexDigest())
        val split = Sha256()
        val text = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()
        split.update(text, 0, 5)
        split.update(text, 5, text.size - 5)
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", split.hexDigest())
    }

    private val settings = VadSettings(threshold = 0.5f, minSpeechMs = 250, minSilenceMs = 600, padMs = 200, maxPhraseSeconds = 28)
    private val window = FloatArray(512)

    /** Windows are 32 ms: runs of voice (true) or quiet (false). */
    private fun feed(cutter: PhraseCutter, vararg runs: Pair<Boolean, Int>): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        for ((speech, n) in runs) repeat(n) { cutter.push(window, if (speech) 0.9f else 0.1f)?.let(out::add) }
        return out
    }

    @Test
    fun aPauseEndsAPhraseWithPaddingOnBothSides() {
        val cutter = PhraseCutter(settings)
        val phrases = feed(cutter, false to 20, true to 30, false to 19)
        assertEquals(1, phrases.size)
        // 7 windows of padding before (200 ms), the speech, and 7 of the 19 quiet ones after.
        assertEquals((7 + 30 + 7) * 512, phrases[0].size)
    }

    @Test
    fun aShortSoundIsNotAPhrase() {
        val cutter = PhraseCutter(settings)
        assertEquals(0, feed(cutter, true to 5, false to 30).size)
    }

    @Test
    fun aLongPhraseIsCutAtTheLimit() {
        val cutter = PhraseCutter(settings)
        val phrases = feed(cutter, true to 900)
        assertEquals(1, phrases.size)
        assertEquals(28_000 / 32 * 512, phrases[0].size)
        assertNotNull(cutter.finish())
    }

    @Test
    fun finishingKeepsThePhraseInProgress() {
        val cutter = PhraseCutter(settings)
        feed(cutter, true to 20)
        assertNotNull(cutter.finish())
        assertNull(cutter.finish())
    }
}

/** Talking and pauses for the open microphone (section 3.7), from the detector's windows of 32 ms. */
class TurnWatchTest {
    private val settings = VadSettings(threshold = 0.5f, minSpeechMs = 250, minSilenceMs = 600, padMs = 200, maxPhraseSeconds = 28)
    private val window = FloatArray(512)

    /** Runs of voice (true) or quiet (false); the events the watch gave, in order. */
    private fun watch(vararg runs: Pair<Boolean, Int>): List<LocalSpeechEvent> {
        val cutter = PhraseCutter(settings)
        val turns = TurnWatch(settings, endOfTurnMs = 1_000)
        val out = mutableListOf<LocalSpeechEvent>()
        for ((speech, n) in runs) repeat(n) {
            val p = if (speech) 0.9f else 0.1f
            cutter.push(window, p)
            turns.push(cutter, p)?.let(out::add)
        }
        return out
    }

    @Test
    fun talkingStartsAfterMinSpeechAndAPauseEndsTheTurn() {
        assertEquals(listOf(LocalSpeechEvent.SpeechStarted, LocalSpeechEvent.Paused), watch(true to 20, false to 40))
    }

    @Test
    fun aPauseShorterThanTheEndOfTurnKeepsTheTurn() {
        // 0.8 s of quiet between words: talking rests and starts again, but the turn does not end.
        assertEquals(
            listOf(LocalSpeechEvent.SpeechStarted, LocalSpeechEvent.SpeechStarted, LocalSpeechEvent.Paused),
            watch(true to 20, false to 25, true to 20, false to 40),
        )
    }

    @Test
    fun aShortNoiseIsNotTalking() {
        assertEquals(emptyList(), watch(true to 3, false to 60))
    }
}
