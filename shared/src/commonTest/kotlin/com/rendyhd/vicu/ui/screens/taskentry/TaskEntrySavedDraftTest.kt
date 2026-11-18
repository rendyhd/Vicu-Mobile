package com.rendyhd.vicu.ui.screens.taskentry

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.location.FakeLocationPermission
import com.rendyhd.vicu.location.testLocations
import com.rendyhd.vicu.ui.FakeAttachmentRepository
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FixedTimeSource
import com.rendyhd.vicu.util.RecurrenceValue
import com.rendyhd.vicu.util.parser.TokenType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quick add's draft survives process death through the instance state: the system may end the
 * app while the camera is open for Take photo, and the sheet comes back with what was written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskEntrySavedDraftTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(authScope: CoroutineScope): TaskEntryViewModel {
        val vm = TaskEntryViewModel(
            taskRepository = FakeTaskRepository(),
            projectRepository = FakeProjectRepository(listOf(Project(id = 1, title = "Inbox"), Project(id = 2, title = "Errands"))),
            labelRepository = FakeLabelRepository(),
            attachmentRepository = FakeAttachmentRepository(),
            authManager = AuthManager(
                platformAuthHooks = RecordingAuthHooks(),
                tokenStorage = InMemoryTokenStorage(),
                apiServiceProvider = { error("no network in this test") },
                appScope = authScope,
                networkMonitor = FakeNetworkMonitor(),
            ),
            nlpPrefsStore = NlpPrefsStore(InMemoryPreferencesDataStore()),
            notificationPrefsStore = NotificationPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            appMessages = AppMessages(),
            locations = testLocations(),
            locationPermission = FakeLocationPermission(),
            dayClock = DayClock(
                backgroundScope,
                FixedTimeSource(Clock.System.now(), TimeZone.currentSystemDefault()),
                ticking = false,
            ),
        )
        runCurrent()
        return vm
    }

    @Test
    fun `a draft comes back in a new view model, photos and picks included`() = runTest {
        val authScope = CoroutineScope(SupervisorJob())
        val before = viewModel(authScope)
        before.initWithDefaults(defaultProjectId = 1L)
        runCurrent()
        before.setTitle("Return jacket")
        before.setDescription("Receipt is in the drawer")
        before.stagePendingImage("content://com.rendyhd.vicu.fileprovider/camera/IMG_20261118_142233.jpg")
        before.setPriority(3)
        before.setProjectId(2L)
        before.setRecurrence(RecurrenceValue(repeatAfter = 86_400L, repeatMode = 0))
        before.addSubtask("Print the label")
        runCurrent()
        val saved = before.uiState.value

        val json = assertNotNull(before.currentDraftJson())
        val after = viewModel(authScope)
        assertTrue(after.restoreDraft(json))
        runCurrent()
        val restored = after.uiState.value

        assertTrue(after.isInitialized, "the sheet must not start a new draft over it")
        assertEquals("Return jacket", restored.title)
        assertEquals(saved.description, restored.description)
        assertEquals(saved.pendingImages, restored.pendingImages)
        assertEquals(3, restored.priority)
        assertTrue(restored.priorityIsManual)
        assertEquals(2L, restored.projectId)
        assertTrue(restored.projectIsManual)
        assertEquals(RecurrenceValue(86_400L, 0), restored.manualRecurrence)
        assertEquals(listOf("Print the label"), restored.subtasks)
        authScope.cancel()
    }

    @Test
    fun `a photo the camera hands over after the restore joins the restored ones`() = runTest {
        val authScope = CoroutineScope(SupervisorJob())
        val before = viewModel(authScope)
        before.initWithDefaults(defaultProjectId = 1L)
        before.setTitle("Receipts")
        before.stagePendingImage("content://x/camera/IMG_1.jpg")
        runCurrent()
        val json = before.currentDraftJson()

        val after = viewModel(authScope)
        after.restoreDraft(json)
        after.stagePendingImage("content://x/camera/IMG_2.jpg")
        runCurrent()

        assertEquals(
            setOf("content://x/camera/IMG_1.jpg", "content://x/camera/IMG_2.jpg"),
            after.uiState.value.pendingImages.values.toSet(),
        )
        authScope.cancel()
    }

    @Test
    fun `a dismissed chip stays dismissed`() = runTest {
        val authScope = CoroutineScope(SupervisorJob())
        val before = viewModel(authScope)
        before.initWithDefaults(defaultProjectId = 1L)
        before.setTitle("Call mum tomorrow")
        runCurrent()
        assertNotNull(before.uiState.value.parseResult?.dueDate, "the title names a date")
        before.suppressType(TokenType.DATE)
        runCurrent()

        val after = viewModel(authScope)
        after.restoreDraft(before.currentDraftJson())
        runCurrent()

        assertTrue(TokenType.DATE in after.uiState.value.suppressedTypes)
        assertNull(after.uiState.value.parseResult?.dueDate)
        authScope.cancel()
    }

    @Test
    fun `an empty sheet keeps nothing and a new one starts as usual`() = runTest {
        val authScope = CoroutineScope(SupervisorJob())
        val vm = viewModel(authScope)
        vm.initWithDefaults(defaultProjectId = 1L)
        runCurrent()

        assertNull(vm.currentDraftJson())
        val fresh = viewModel(authScope)
        assertFalse(fresh.restoreDraft(null))
        assertFalse(fresh.restoreDraft("not a draft"))
        assertFalse(fresh.isInitialized)
        authScope.cancel()
    }
}
