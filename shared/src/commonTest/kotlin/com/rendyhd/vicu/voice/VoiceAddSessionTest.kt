package com.rendyhd.vicu.voice

import com.rendyhd.vicu.speech.LocalSpeechEvent
import com.rendyhd.vicu.speech.LocalSpeechState
import com.rendyhd.vicu.speech.SpeechRecording
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeRecording(private val starts: Boolean = true) : SpeechRecording {
    override val state = MutableStateFlow(LocalSpeechState())
    private val channel = Channel<LocalSpeechEvent>(Channel.UNLIMITED)
    override val events = channel.receiveAsFlow()
    val transcript = CompletableDeferred<String>()
    var finishes = 0
    var cancelled = false

    override suspend fun start(): Boolean = starts

    override suspend fun finish(): String {
        finishes++
        channel.close()
        return transcript.await()
    }

    override fun cancel() {
        cancelled = true
        channel.close()
        if (!transcript.isCompleted) transcript.complete("")
    }

    fun event(event: LocalSpeechEvent) = channel.trySend(event)

    fun heard(text: String) = state.update { it.copy(text = text) }
}

/** Adding a task by voice from a widget with local speech: when it ends, what it adds, and Cancel. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAddSessionTest {
    private val added = mutableListOf<String>()
    private var failed = false

    private fun TestScope.session(recording: FakeRecording) =
        VoiceAddSession(recording, backgroundScope, onWords = { added += it }, onFailed = { failed = true }).also {
            it.start()
            runCurrent()
        }

    @Test
    fun aPauseAfterTheWordsAddsTheWholeTranscript() = runTest {
        val recording = FakeRecording()
        val voice = session(recording)
        recording.event(LocalSpeechEvent.SpeechStarted)
        recording.heard("Call mom")
        runCurrent()
        assertEquals("Call mom", voice.state.value?.text)
        recording.event(LocalSpeechEvent.Paused)
        runCurrent()
        assertEquals(VoicePhase.FINISHING, voice.state.value?.phase)
        assertTrue(added.isEmpty(), "waits for the last phrase")
        recording.transcript.complete(" Call mom tomorrow ")
        runCurrent()
        assertEquals(listOf("Call mom tomorrow"), added)
        assertNull(voice.state.value)
        assertEquals(1, recording.finishes)
    }

    @Test
    fun aPauseBeforeAnyWordsKeepsListening() = runTest {
        val recording = FakeRecording()
        val voice = session(recording)
        recording.event(LocalSpeechEvent.Paused)
        runCurrent()
        assertEquals(VoicePhase.LISTENING, voice.state.value?.phase)
        assertEquals(0, recording.finishes)
    }

    @Test
    fun doneStopsAtOnce() = runTest {
        val recording = FakeRecording()
        val voice = session(recording)
        recording.event(LocalSpeechEvent.SpeechStarted)
        runCurrent()
        voice.done()
        voice.done()
        recording.transcript.complete("Buy milk")
        runCurrent()
        assertEquals(listOf("Buy milk"), added)
        assertEquals(1, recording.finishes)
    }

    @Test
    fun silenceGivesUpWithNothing() = runTest {
        val recording = FakeRecording()
        val voice = session(recording)
        advanceTimeBy(VoiceAddSession.IDLE_MS + 1)
        recording.transcript.complete("")
        runCurrent()
        assertEquals(listOf(""), added)
        assertNull(voice.state.value)
    }

    @Test
    fun cancelAddsNothing() = runTest {
        val recording = FakeRecording()
        val voice = session(recording)
        recording.event(LocalSpeechEvent.SpeechStarted)
        recording.event(LocalSpeechEvent.Paused)
        runCurrent()
        voice.cancel()
        runCurrent()
        assertTrue(recording.cancelled)
        assertTrue(added.isEmpty())
        assertNull(voice.state.value)
    }

    @Test
    fun aMicrophoneThatDoesNotStartFails() = runTest {
        val recording = FakeRecording(starts = false)
        val voice = session(recording)
        assertTrue(failed)
        assertTrue(recording.cancelled)
        assertNull(voice.state.value)
        assertFalse(added.isNotEmpty())
    }
}
