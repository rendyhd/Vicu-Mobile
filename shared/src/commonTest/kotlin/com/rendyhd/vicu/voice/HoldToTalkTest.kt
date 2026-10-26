package com.rendyhd.vicu.voice

import com.rendyhd.vicu.util.AppMessage
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DateDisplayFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSpeech(var available: SpeechAvailability = SpeechAvailability.READY) : SpeechInput {
    var listener: SpeechListener? = null
    var starts = 0
    var stops = 0
    var cancels = 0
    var permissionCallback: ((Boolean) -> Unit)? = null
    var systemSpeech = false
    var systemSpeechOpened = 0

    override fun availability() = available
    override fun requestPermission(onResult: (Boolean) -> Unit) {
        permissionCallback = onResult
    }
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
    override fun openSystemSpeech(): Boolean {
        if (systemSpeech) systemSpeechOpened++
        return systemSpeech
    }
    override fun openSettings() = Unit

    /** The recognizer reports the end of an utterance; the listener is done after it. */
    fun final(text: String) = listener!!.also { listener = null }.onFinal(text)
    fun error(error: SpeechError) = listener!!.also { listener = null }.onError(error)
}

/** Holding the + listens, shows the words, and adds them when the finger lifts. */
@OptIn(ExperimentalCoroutinesApi::class)
class HoldToTalkTest {
    private val format = DateDisplayFormat.system(is24Hour = true)
    private val speech = FakeSpeech()
    private val messages = AppMessages()
    private val added = mutableListOf<String>()
    private val addedDefaults = mutableListOf<TaskDefaults>()
    private var undone = 0
    private var voiceUsed = 0
    private val lockedFor = mutableListOf<TaskDefaults>()
    private val lockedWords = mutableListOf<String>()

    private fun TestScope.session(addFails: Boolean = false, canLock: Boolean = false) = HoldToTalk(
        speech = speech,
        messages = messages,
        scope = this,
        addTask = { text, _, defaults ->
            added += text
            addedDefaults += defaults
            if (addFails) VoiceAddResult("Sign in to Vicu first.") else VoiceAddResult("Added “$text”") { undone++ }
        },
        onVoiceAdded = { voiceUsed++ },
        canLock = { canLock },
        onLock = { lockedFor += it },
        onLockedWords = { lockedWords += it },
    )

    private suspend fun nextMessage(): AppMessage? = withTimeoutOrNull(1) { messages.messages.first() }

    @Test
    fun theWordsShowAndLettingGoAddsThem() = runTest {
        val voice = session()
        assertEquals(VoiceBegin.STARTED, voice.begin(format))
        assertEquals(VoicePhase.LISTENING, voice.state.value?.phase)
        speech.listener!!.onPartial("call mom")
        speech.listener!!.onLevel(0.6f)
        assertEquals("call mom", voice.state.value?.text)
        assertEquals(0.6f, voice.state.value?.level)
        speech.listener!!.onPartial("call mom tomorrow")

        voice.release()
        assertEquals(1, speech.stops)
        assertEquals(VoicePhase.FINISHING, voice.state.value?.phase)
        speech.final("call mom tomorrow 3pm")
        advanceUntilIdle()

        assertNull(voice.state.value)
        assertEquals(listOf("call mom tomorrow 3pm"), added)
        val message = nextMessage()!!
        assertEquals("Added “call mom tomorrow 3pm”", message.text)
        assertEquals("Undo", message.actionLabel)
        message.onAction!!()
        advanceUntilIdle()
        assertEquals(1, undone)
        assertEquals(1, voiceUsed)
    }

    @Test
    fun theLockHandsTheWordsToSmartDictationAndAddsNothing() = runTest {
        val voice = session(canLock = true)
        val work = TaskDefaults(projectId = 2, listName = "Work")
        voice.begin(format, defaults = work)
        assertTrue(voice.lockOffered())
        assertEquals(true, voice.state.value?.canLock)
        speech.listener!!.onPartial("buy milk")
        voice.lock()
        // Smart dictation opens at once; the card is gone while the last words come.
        assertEquals(listOf(work), lockedFor)
        assertEquals(true, voice.state.value?.locked)
        assertEquals(1, speech.stops)
        speech.final("buy milk and eggs")
        advanceUntilIdle()
        assertNull(voice.state.value)
        assertEquals(listOf("buy milk and eggs"), lockedWords)
        assertEquals(emptyList(), added)
        assertNull(nextMessage())
    }

    @Test
    fun withoutSmartDictationThereIsNoLock() = runTest {
        val voice = session(canLock = false)
        voice.begin(format)
        voice.lock()
        assertEquals(emptyList(), lockedFor)
        assertEquals(false, voice.state.value?.locked)
    }

    @Test
    fun cancellingWhileLockedStillLetsDictationListen() = runTest {
        val voice = session(canLock = true)
        voice.begin(format)
        voice.lock()
        voice.cancel()
        assertEquals(listOf(""), lockedWords)
        assertNull(voice.state.value)
    }

    @Test
    fun theTaskGetsTheDefaultsOfTheListItWasSaidIn() = runTest {
        val voice = session()
        val work = TaskDefaults(projectId = 2, dueDate = "2026-10-20T21:59:59Z")
        voice.begin(format, defaults = work)
        assertEquals(work, voice.state.value?.defaults)
        voice.release()
        speech.final("call mom")
        advanceUntilIdle()
        assertEquals(listOf(work), addedDefaults)
    }

    @Test
    fun aPauseWhileHeldKeepsListeningAndJoinsTheWords() = runTest {
        val voice = session()
        voice.begin(format)
        speech.final("buy milk")
        assertEquals(2, speech.starts)
        assertEquals("buy milk", voice.state.value?.text)
        // A silence is no reason to stop either.
        speech.error(SpeechError.NO_MATCH)
        assertEquals(3, speech.starts)
        speech.listener!!.onPartial("on friday")
        assertEquals("buy milk on friday", voice.state.value?.text)
        voice.release()
        speech.final("on friday")
        advanceUntilIdle()
        assertEquals(listOf("buy milk on friday"), added)
    }

    @Test
    fun withoutTheLastWordsItAddsWhatWasHeardAfterAMoment() = runTest {
        val voice = session()
        voice.begin(format)
        speech.listener!!.onPartial("water the plants")
        voice.release()
        advanceTimeBy(HoldToTalk.FINAL_WAIT_MS - 1)
        runCurrent()
        assertTrue(added.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("water the plants"), added)
        // A final that comes too late is ignored.
        speech.listener?.onFinal("water the plants today")
        advanceUntilIdle()
        assertEquals(1, added.size)
    }

    @Test
    fun slidingDownCancels() = runTest {
        val voice = session()
        voice.begin(format)
        speech.listener!!.onPartial("never mind")
        voice.setCancelArmed(true)
        assertEquals(true, voice.state.value?.cancelArmed)
        voice.release()
        advanceUntilIdle()
        assertNull(voice.state.value)
        assertEquals(1, speech.cancels)
        assertTrue(added.isEmpty())
        assertNull(nextMessage())
    }

    @Test
    fun nothingHeardAddsNothingAndSaysSo() = runTest {
        val voice = session()
        voice.begin(format)
        voice.release()
        speech.error(SpeechError.NO_MATCH)
        advanceUntilIdle()
        assertTrue(added.isEmpty())
        assertEquals(HoldToTalk.NOTHING_HEARD, nextMessage()?.text)
    }

    @Test
    fun aFailedAddIsSaidWithoutUndo() = runTest {
        val voice = session(addFails = true)
        voice.begin(format)
        voice.release()
        speech.final("call mom")
        advanceUntilIdle()
        val message = nextMessage()!!
        assertEquals("Sign in to Vicu first.", message.text)
        assertNull(message.actionLabel)
        assertEquals(0, voiceUsed)
    }

    @Test
    fun theFirstHoldAsksForTheMicrophone() = runTest {
        speech.available = SpeechAvailability.NEEDS_PERMISSION
        val voice = session()
        assertEquals(VoiceBegin.ASKED_PERMISSION, voice.begin(format))
        assertNull(voice.state.value)
        assertEquals(0, speech.starts)
        speech.permissionCallback!!(true)
        assertEquals("Microphone allowed. Hold + and say the task.", nextMessage()?.text)
    }

    @Test
    fun refusedOrMissingFallsBackToTheSystemScreen() = runTest {
        speech.systemSpeech = true
        speech.available = SpeechAvailability.DENIED
        assertEquals(VoiceBegin.SYSTEM_SPEECH, session().begin(format))
        speech.available = SpeechAvailability.UNAVAILABLE
        assertEquals(VoiceBegin.SYSTEM_SPEECH, session().begin(format))
        assertEquals(2, speech.systemSpeechOpened)

        speech.systemSpeech = false
        assertEquals(VoiceBegin.UNAVAILABLE, session().begin(format))
        speech.available = SpeechAvailability.DENIED
        assertEquals(VoiceBegin.DENIED, session().begin(format))
        assertEquals("Settings", nextMessage()?.actionLabel)
    }

    @Test
    fun handsFreeWaitsForAdd() = runTest {
        val voice = session()
        voice.begin(format, handsFree = true)
        speech.final("pay rent")
        assertEquals(VoicePhase.HEARD, voice.state.value?.phase)
        assertEquals(1, speech.starts)
        voice.release()
        advanceUntilIdle()
        assertEquals(listOf("pay rent"), added)
    }

    @Test
    fun handsFreeCanTryAgainAfterSilence() = runTest {
        val voice = session()
        voice.begin(format, handsFree = true)
        speech.error(SpeechError.NO_MATCH)
        assertEquals(VoicePhase.HEARD, voice.state.value?.phase)
        assertEquals("", voice.state.value?.text)
        voice.listenAgain()
        assertEquals(VoicePhase.LISTENING, voice.state.value?.phase)
        assertEquals(2, speech.starts)
    }

    @Test
    fun aRecognizerFailureKeepsWhatWasHeard() = runTest {
        val voice = session()
        voice.begin(format)
        speech.listener!!.onPartial("book flights")
        speech.error(SpeechError.NETWORK)
        assertEquals(VoicePhase.HEARD, voice.state.value?.phase)
        voice.release()
        advanceUntilIdle()
        assertEquals(listOf("book flights"), added)
    }

    @Test
    fun aRecognizerFailureWithNothingHeardEndsWithAMessage() = runTest {
        val voice = session()
        voice.begin(format)
        speech.error(SpeechError.NETWORK)
        assertNull(voice.state.value)
        assertTrue(nextMessage()!!.text.contains("connection"))
    }

    @Test
    fun wordsJoinWithOneSpace() {
        assertEquals("a b", HoldToTalk.joinWords(" a ", " b"))
        assertEquals("b", HoldToTalk.joinWords("", "b"))
        assertEquals("a", HoldToTalk.joinWords("a", " "))
    }
}
