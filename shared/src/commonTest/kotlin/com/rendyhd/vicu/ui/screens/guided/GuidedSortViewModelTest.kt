package com.rendyhd.vicu.ui.screens.guided

import androidx.lifecycle.SavedStateHandle
import com.rendyhd.vicu.ai.ask.Proposals
import com.rendyhd.vicu.ai.dictation.DraftWriter
import com.rendyhd.vicu.ai.organize.OrganizeResult
import com.rendyhd.vicu.ai.organize.OrganizeSession
import com.rendyhd.vicu.ai.organize.OrganizeSuggester
import com.rendyhd.vicu.ai.organize.Suggestion
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.data.repository.InMemorySyncedEntries
import com.rendyhd.vicu.data.repository.TaskOrderPreferences
import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.Guided
import com.rendyhd.vicu.util.SchedulerTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A whole Inbox session (contract section 14.3): the suggestion fills in the first card, Next
 * writes it, Complete completes, the end says what changed, and Undo all puts it back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuidedSortViewModelTest {
    private val zone = TimeZone.UTC
    private val friday = LocalDate(2026, 10, 30).atTime(DueDates.DATE_ONLY_TIME)

    @BeforeTest
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    private class FakeSuggester(private val ready: Boolean, private val answer: (List<Long>) -> OrganizeResult) : OrganizeSuggester {
        val asked = mutableListOf<Pair<OrganizeSession, List<Long>>>()

        override suspend fun isReady() = ready

        override suspend fun suggest(session: OrganizeSession, taskIds: List<Long>, list: String, defaultProjectId: Long?): OrganizeResult {
            asked += session to taskIds
            return answer(taskIds)
        }
    }

    private class Rig(val tasks: FakeTaskRepository, val vm: GuidedSortViewModel, val progress: GuidedProgress, val suggester: FakeSuggester, val scope: CoroutineScope)

    private suspend fun TestScope.rig(ready: Boolean = true): Rig {
        val tasks = FakeTaskRepository()
        listOf(101L, 102L, 103L).forEachIndexed { i, id ->
            tasks.put(Task(id = id, title = "Task $id", projectId = 1, position = i.toDouble(), created = "2026-10-01T09:00:00Z", updated = "2026-10-01T09:00:00Z"))
        }
        tasks.put(Task(id = 201, title = "Elsewhere", projectId = 3))
        val projects = FakeProjectRepository(listOf(Project(id = 1, title = "Inbox", maxPermission = 2), Project(id = 3, title = "Home", maxPermission = 2)))
        val scope = CoroutineScope(SupervisorJob())
        val auth = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = InMemoryTokenStorage(),
            apiServiceProvider = { error("no network in this test") },
            appScope = scope,
            networkMonitor = FakeNetworkMonitor(),
        )
        auth.onInboxProjectSelected(1)
        val time = SchedulerTimeSource(testScheduler, Instant.parse("2026-10-27T10:00:00Z"), zone)
        val dayClock = DayClock(backgroundScope, time)
        val source = GuidedWorldSource(
            tasks = tasks,
            projects = projects,
            auth = auth,
            reviewPrefs = ReviewPrefsStore(InMemoryPreferencesDataStore()),
            taskOrder = TaskOrderPreferences(InMemorySyncedEntries()),
            collabState = flowOf(CollabState()),
            dayClock = dayClock,
        )
        val labels = FakeLabelRepository()
        val proposals = Proposals(tasks, labels, DraftWriter(tasks, labels, NotificationPrefsStore(InMemoryPreferencesDataStore()), null))
        val suggester = FakeSuggester(ready) { ids ->
            OrganizeResult(
                suggestions = listOf(Suggestion(ids.first(), null, null, friday, false, false, null, "The notes say Friday")),
                nextStep = null,
                dropped = emptyList(),
            )
        }
        val progress = GuidedProgress()
        val vm = GuidedSortViewModel(
            savedStateHandle = SavedStateHandle(mapOf("kind" to "inbox")),
            source = source,
            writer = GuidedWriter(tasks, proposals, dayClock),
            organize = suggester,
            behaviorPrefs = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            progress = progress,
            messages = AppMessages(),
            dayClock = dayClock,
        )
        runCurrent()
        return Rig(tasks, vm, progress, suggester, scope)
    }

    @Test
    fun anInboxSessionWritesWhatTheCardsSayAndUndoesIt() = runTest {
        val rig = rig()
        val vm = rig.vm
        assertEquals(3, vm.uiState.value.total)
        assertEquals(101L, vm.uiState.value.task?.id)
        assertEquals(listOf(OrganizeSession.INBOX to listOf(101L, 102L, 103L)), rig.suggester.asked)
        // The suggestion filled in the date, marked as suggested; nothing is written yet.
        assertEquals(DueChoice.On(friday, false), vm.uiState.value.draft.due)
        assertTrue(CardDraft.FIELD_WHEN in vm.uiState.value.draft.suggested)
        assertEquals("", rig.tasks.current(101)?.dueDate)

        vm.next()
        runCurrent()
        assertEquals(DueDates.dateOnlyDue(LocalDate(2026, 10, 30), zone), DueDates.localDateOf(rig.tasks.current(101)?.dueDate, zone)?.let { DueDates.dateOnlyDue(it, zone) })
        assertEquals(102L, vm.uiState.value.task?.id)

        vm.complete()
        runCurrent()
        assertTrue(rig.tasks.current(102)?.done == true)
        assertEquals(103L, vm.uiState.value.task?.id)

        vm.skip()
        runCurrent()
        val end = vm.uiState.value
        assertTrue(end.finished)
        assertEquals("Sorted 2: 1 scheduled, 1 completed", end.summary)
        assertTrue(end.canUndo)
        assertTrue(Guided.STEP_INBOX in rig.progress.finished.value)

        vm.undoAll()
        runCurrent()
        assertEquals(null, DueDates.localDateOf(rig.tasks.current(101)?.dueDate, zone))
        assertFalse(rig.tasks.current(102)?.done == true)
        assertFalse(vm.uiState.value.canUndo)
        rig.scope.cancel()
    }

    @Test
    fun withoutAskTheSessionWorksWithoutSuggestions() = runTest {
        val rig = rig(ready = false)
        assertEquals(emptyList(), rig.suggester.asked)
        assertEquals(CardDraft(), rig.vm.uiState.value.draft)
        assertFalse(rig.vm.uiState.value.suggesting)
        // Next without a change is Skip: nothing is written.
        rig.vm.next()
        runCurrent()
        assertEquals(102L, rig.vm.uiState.value.task?.id)
        assertEquals(emptyList(), rig.tasks.updates)
        rig.scope.cancel()
    }

    @Test
    fun backShowsThePreviousCardAsItIsNow() = runTest {
        val rig = rig()
        rig.vm.next()
        runCurrent()
        assertTrue(rig.vm.uiState.value.canGoBack)
        rig.vm.back()
        runCurrent()
        assertEquals(101L, rig.vm.uiState.value.task?.id)
        // Already decided: the suggestion is not filled in again.
        assertEquals(CardDraft(), rig.vm.uiState.value.draft)
        rig.scope.cancel()
    }
}
