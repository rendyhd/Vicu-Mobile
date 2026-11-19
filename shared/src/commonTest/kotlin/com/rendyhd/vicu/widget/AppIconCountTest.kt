package com.rendyhd.vicu.widget

import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.local.WidgetPrefsStore
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.FakeProjectDao
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.CustomListContext
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FixedTimeSource
import com.rendyhd.vicu.util.TodayBadge
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class AppIconCountTest {
    private val zone = TimeZone.of("Europe/Amsterdam")
    private val time = FixedTimeSource(Instant.parse("2026-10-12T08:00:00Z"), zone)
    private val storage = InMemoryTokenStorage()
    private val prefs = NotificationPrefsStore(InMemoryPreferencesDataStore())

    private val rows = listOf(
        TaskEntity(id = 1, title = "Overdue", projectId = 1, dueDate = "2026-10-10T10:00:00Z"),
        TaskEntity(id = 2, title = "Due today", projectId = 1, dueDate = "2026-10-12T10:00:00Z"),
        TaskEntity(id = 3, title = "Due tomorrow", projectId = 1, dueDate = "2026-10-13T10:00:00Z"),
        TaskEntity(id = 4, title = "Block tomorrow", projectId = 1, startDate = "2026-10-13T08:00:00Z", endDate = "2026-10-13T09:00:00Z"),
        TaskEntity(id = 5, title = "Archived project", projectId = 9, dueDate = "2026-10-12T10:00:00Z"),
        TaskEntity(id = 6, title = "Next week", projectId = 1, dueDate = "2026-10-19T10:00:00Z"),
    )

    // The Today query's superset, as the SQL reads it.
    private val taskDao = object : TaskDao by FakeTaskDao() {
        override suspend fun getTodayTasksSync(startOfToday: String, startOfTomorrow: String): List<TaskEntity> =
            rows.filter { row ->
                (row.dueDate.isNotEmpty() && row.dueDate < startOfTomorrow) ||
                    (row.startDate >= startOfToday && row.startDate < startOfTomorrow)
            }
    }

    private val states = TaskWidgetStates(
        taskDao = taskDao,
        projectDao = FakeProjectDao(
            listOf(
                ProjectEntity(id = 1, title = "Home"),
                ProjectEntity(id = 9, title = "Old", isArchived = true),
            ),
        ),
        taskRepository = FakeTaskRepository(),
        tokenStorage = storage,
        customListStore = CustomListStore(InMemoryPreferencesDataStore()),
        widgetPrefsStore = WidgetPrefsStore(InMemoryPreferencesDataStore()),
        behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
        time = time,
        collaborationContext = { CustomListContext.NONE },
    )

    private suspend fun signIn() {
        storage.storeVikunjaUrl("https://vikunja.test")
        storage.storeApiToken("tk_1", 0L)
    }

    @Test
    fun offByDefault() = runTest {
        signIn()
        val count = AppIconCount(states, prefs, DayClock(backgroundScope, time, ticking = false))
        assertEquals(TodayBadge.Plan.OFF, count.plan())
    }

    @Test
    fun nothingWhileSignedOut() = runTest {
        prefs.setTodayCountBadge(true)
        val count = AppIconCount(states, prefs, DayClock(backgroundScope, time, ticking = false))
        assertEquals(TodayBadge.Plan.OFF, count.plan())
    }

    @Test
    fun countsTodaysListNowAndTomorrowsForMidnight() = runTest {
        signIn()
        prefs.setTodayCountBadge(true)
        val plan = AppIconCount(states, prefs, DayClock(backgroundScope, time, ticking = false)).plan()
        // Overdue and due today; the archived project's task is left out.
        assertEquals(2, plan.count)
        // Tomorrow: today's two (overdue by then), the one due tomorrow and the block starting tomorrow.
        assertEquals(4, plan.atMidnight)
        assertEquals(Instant.parse("2026-10-12T22:00:00Z"), plan.midnight)
    }

    @Test
    fun turningItOffAgainSetsNothing() = runTest {
        signIn()
        prefs.setTodayCountBadge(true)
        prefs.setTodayCountBadge(false)
        val count = AppIconCount(states, prefs, DayClock(backgroundScope, time, ticking = false))
        assertEquals(TodayBadge.Plan.OFF, count.plan())
    }
}
