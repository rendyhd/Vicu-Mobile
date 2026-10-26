package com.rendyhd.vicu.voice

import com.rendyhd.vicu.speech.LocalSpeechEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingTurns : TurnListener {
    val words = mutableListOf<String>()
    val turns = mutableListOf<String>()
    var speechStarts = 0
    var off = false
    var offError: SpeechError? = null

    override fun onWords(text: String) {
        words += text
    }

    override fun onLevel(level: Float) = Unit

    override fun onSpeechStart() {
        speechStarts++
    }

    override fun onTurn(text: String) {
        turns += text
    }

    override fun onOff(error: SpeechError?) {
        off = true
        offError = error
    }
}

private class FakeRecognizer : SpeechInput {
    var listener: SpeechListener? = null
    var starts = 0
    var stops = 0
    var cancels = 0

    override fun availability() = SpeechAvailability.READY
    override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(true)
    override fun start(listener: SpeechListener) {
        starts++
        this.listener = listener
    }
    override fun stop() {
        stops++
    }
    override fun cancel() {
        cancels++
        listener = null
    }
    override fun openSystemSpeech() = false
    override fun openSettings() = Unit

    fun final(text: String) = listener!!.also { listener = null }.onFinal(text)
    fun error(error: SpeechError) = listener!!.also { listener = null }.onError(error)
}

/** The open microphone (`docs/ai-assistant-v1.md`, section 3.7): turns, interrupting, pausing and going off. */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenMicTest {
    private val settings = TurnSettings(endOfTurnMs = 1_000, idleMs = 60_000)

    @Test
    fun theContractGivesTheTurnTimes() {
        val fromFixture = TurnSettings.fromContract()
        assertEquals(1_000L, fromFixture.endOfTurnMs)
        assertEquals(60_000L, fromFixture.idleMs)
    }

    // --- Which words go out ---

    @Test
    fun aTurnIsSentAndTalkingAgainCancelsItAndJoinsTheWords() {
        val sender = TurnSender()
        assertEquals("buy milk", sender.turn("buy milk"))
        assertTrue(sender.interrupt())
        assertEquals("buy milk", sender.waiting)
        // Talking again a second time cancels nothing more.
        assertFalse(sender.interrupt())
        assertEquals("buy milk and eggs", sender.turn("and eggs"))
        assertEquals("", sender.waiting)
    }

    @Test
    fun aFinishedRequestIsNotCancelled() {
        val sender = TurnSender()
        sender.turn("what is due today")
        sender.finished()
        assertFalse(sender.interrupt())
        assertEquals("", sender.waiting)
    }

    @Test
    fun aTurnWithoutWordsSendsOnlyWordsThatWait() {
        val sender = TurnSender()
        assertNull(sender.turn(""))
        sender.turn("call the plumber")
        sender.interrupt()
        // The speech that interrupted gave no words: the cancelled words go out again.
        assertEquals("call the plumber", sender.turn(""))
    }

    // --- Local speech: turns from the detector's events ---

    private fun localTurns(listener: RecordingTurns, turnsEnded: MutableList<Unit> = mutableListOf()) =
        LocalTurns(listener, onSpeech = {}, afterTurn = { turnsEnded += Unit })

    @Test
    fun aTurnEndsAtThePauseOnceItsPhrasesAreTranscribed() {
        val listener = RecordingTurns()
        val turns = localTurns(listener)
        turns.on(LocalSpeechEvent.SpeechStarted)
        turns.on(LocalSpeechEvent.PhraseCut)
        turns.on(LocalSpeechEvent.Paused)
        // The phrase is still being transcribed: the turn waits for it.
        assertEquals(emptyList(), listener.turns)
        turns.on(LocalSpeechEvent.Transcribed("buy milk"))
        assertEquals(listOf("buy milk"), listener.words)
        assertEquals(listOf("buy milk"), listener.turns)
        assertEquals(1, listener.speechStarts)
    }

    @Test
    fun talkingBeforeTheTurnEndsKeepsItGoing() {
        val listener = RecordingTurns()
        val turns = localTurns(listener)
        turns.on(LocalSpeechEvent.SpeechStarted)
        turns.on(LocalSpeechEvent.PhraseCut)
        turns.on(LocalSpeechEvent.Paused)
        turns.on(LocalSpeechEvent.SpeechStarted)
        turns.on(LocalSpeechEvent.Transcribed("buy milk"))
        assertEquals(emptyList(), listener.turns)
        turns.on(LocalSpeechEvent.PhraseCut)
        turns.on(LocalSpeechEvent.Transcribed("and eggs"))
        turns.on(LocalSpeechEvent.Paused)
        assertEquals(listOf("buy milk and eggs"), listener.turns)
        assertEquals(2, listener.speechStarts)
    }

    @Test
    fun speechWithoutWordsEndsAnEmptyTurn() {
        val listener = RecordingTurns()
        val ended = mutableListOf<Unit>()
        val turns = localTurns(listener, ended)
        turns.on(LocalSpeechEvent.SpeechStarted)
        turns.on(LocalSpeechEvent.PhraseCut)
        turns.on(LocalSpeechEvent.Transcribed(null))
        turns.on(LocalSpeechEvent.Paused)
        assertEquals(listOf(""), listener.turns)
        assertEquals(1, ended.size)
    }

    // --- The platform recognizer, an utterance at a time ---

    private fun TestScope.platformMic(recognizer: FakeRecognizer, listener: RecordingTurns) =
        PlatformOpenMic(recognizer, this, settings, listener).also { it.start() }

    @Test
    fun theFirstWordsStartTalkingAndTheRecognizersEndIsATurn() = runTest {
        val recognizer = FakeRecognizer()
        val listener = RecordingTurns()
        val mic = platformMic(recognizer, listener)
        recognizer.listener!!.onPartial("buy")
        recognizer.listener!!.onPartial("buy milk")
        assertEquals(1, listener.speechStarts)
        assertEquals(listOf("buy", "buy milk"), listener.words)
        recognizer.final("buy milk")
        assertEquals(listOf("buy milk"), listener.turns)
        runCurrent()
        // Listening goes on at once.
        assertEquals(2, recognizer.starts)
        mic.stop()
    }

    @Test
    fun wordsThatStopChangingEndTheUtterance() = runTest {
        val recognizer = FakeRecognizer()
        val listener = RecordingTurns()
        val mic = platformMic(recognizer, listener)
        recognizer.listener!!.onPartial("call mum")
        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, recognizer.stops)
        advanceTimeBy(2)
        runCurrent()
        // The recognizer is asked for its last words; they end the turn.
        assertEquals(1, recognizer.stops)
        recognizer.final("call mum")
        assertEquals(listOf("call mum"), listener.turns)
        mic.stop()
    }

    @Test
    fun silenceIsNoTurnAndListeningGoesOnAfterAMoment() = runTest {
        val recognizer = FakeRecognizer()
        val listener = RecordingTurns()
        val mic = platformMic(recognizer, listener)
        recognizer.error(SpeechError.NO_MATCH)
        assertEquals(emptyList(), listener.turns)
        runCurrent()
        assertEquals(1, recognizer.starts)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(2, recognizer.starts)
        mic.stop()
    }

    @Test
    fun pausedItDoesNotListenUntilResumed() = runTest {
        val recognizer = FakeRecognizer()
        val listener = RecordingTurns()
        val mic = platformMic(recognizer, listener)
        mic.pause()
        assertEquals(1, recognizer.cancels)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, recognizer.starts)
        mic.resume()
        assertEquals(2, recognizer.starts)
        mic.stop()
    }

    @Test
    fun withoutSpeechItTurnsItselfOff() = runTest {
        val recognizer = FakeRecognizer()
        val listener = RecordingTurns()
        platformMic(recognizer, listener)
        advanceTimeBy(60_001)
        runCurrent()
        assertTrue(listener.off)
        assertNull(listener.offError)
        assertEquals(1, recognizer.cancels)
    }

    @Test
    fun aRecognizerFailureTurnsItOffWithTheError() = runTest {
        val recognizer = FakeRecognizer()
        val listener = RecordingTurns()
        platformMic(recognizer, listener)
        recognizer.error(SpeechError.NETWORK)
        assertTrue(listener.off)
        assertEquals(SpeechError.NETWORK, listener.offError)
    }
}
