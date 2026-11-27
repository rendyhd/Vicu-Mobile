package com.rendyhd.vicu.widget

import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.local.WidgetPrefsStore
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.FakeProjectDao
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.TaskOrder
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.CustomListContext
import com.rendyhd.vicu.util.FixedTimeSource
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TaskWidgetStatesTest {
    private val zone = TimeZone.of("Europe/Amsterdam")
    private val day = ClockDay(LocalDate(2026, 10, 12), zone)
    private val storage = InMemoryTokenStorage()
    private val todayBoundaries = mutableListOf<String>()

    private var today = (1L..25L).map { TaskEntity(id = it, title = "Task $it", projectId = if (it == 25L) 9 else 1, dueDate = "2026-10-12T10:00:00Z") }

    private val taskDao = object : TaskDao by FakeTaskDao() {
        override fun getAllOpenTasks() = flowOf(today.filter { !it.done })

        override suspend fun getTodayTasksSync(startOfToday: String, startOfTomorrow: String): List<TaskEntity> {
            todayBoundaries += startOfTomorrow
            return today
        }

        override suspend fun getByProjectIdSync(projectId: Long, limit: Int): List<TaskEntity> =
            today.filter { it.projectId == projectId }

        override suspend fun getAnytimeTasksSync(inboxProjectId: Long, limit: Int): List<TaskEntity> =
            today.filter { !it.done && it.projectId != inboxProjectId }.let {
                if (limit < 0) it else it.take(limit)
            }
    }

    private val projects = FakeProjectDao(
        listOf(
            ProjectEntity(id = 1, title = "Home"),
            ProjectEntity(id = 9, title = "Old", isArchived = true),
        ),
    )

    private val widgetPrefs = WidgetPrefsStore(InMemoryPreferencesDataStore())

    private val states = TaskWidgetStates(
        taskDao = taskDao,
        projectDao = projects,
        taskRepository = FakeTaskRepository(),
        tokenStorage = storage,
        customListStore = CustomListStore(InMemoryPreferencesDataStore()),
        widgetPrefsStore = widgetPrefs,
        behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
        time = FixedTimeSource(Instant.parse("2026-10-12T08:00:00Z"), zone),
        collaborationContext = { CustomListContext.NONE },
    )

    @Test
    fun aBlockStartingTodayIsOnTheTodayWidgetAndAStartDateAloneIsNot() = runTest {
        val rows = listOf(
            TaskEntity(id = 1, title = "Block", projectId = 1, startDate = "2026-10-12T09:00:00Z", endDate = "2026-10-12T09:30:00Z"),
            TaskEntity(id = 2, title = "Start only", projectId = 1, startDate = "2026-10-12T09:00:00Z"),
            TaskEntity(id = 3, title = "Several days", projectId = 1, startDate = "2026-10-12T09:00:00Z", endDate = "2026-10-14T09:00:00Z"),
        )
        val dao = object : TaskDao by FakeTaskDao() {
            override suspend fun getTodayTasksSync(startOfToday: String, startOfTomorrow: String): List<TaskEntity> = rows
        }
        val widget = TaskWidgetStates(
            taskDao = dao,
            projectDao = projects,
            taskRepository = FakeTaskRepository(),
            tokenStorage = storage,
            customListStore = CustomListStore(InMemoryPreferencesDataStore()),
            widgetPrefsStore = WidgetPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            time = FixedTimeSource(Instant.parse("2026-10-12T08:00:00Z"), zone),
            collaborationContext = { CustomListContext.NONE },
        )

        val state = widget.prepare(day).build(WidgetConfig(WidgetViewType.TODAY, "", "Today"))

        assertEquals(listOf(1L), state.tasks.map { it.id })
    }

    @Test
    fun aWidgetShowsTwentyRowsAndCountsAllWithoutArchivedProjects() = runTest {
        val state = states.prepare(day).build(WidgetConfig(WidgetViewType.TODAY, "", "Today"))
        // Task 25 belongs to an archived project.
        assertEquals(24, state.totalCount)
        assertEquals(TaskWidgetStates.MAX_WIDGET_TASKS, state.tasks.size)
        assertEquals("Home", state.tasks.first().projectName)
        assertNull(state.error)
        // The boundary of the day it was built for: the start of the local tomorrow.
        assertEquals(listOf("2026-10-12T22:00:00Z"), todayBoundaries)
    }

    @Test
    fun groupedWidgetsUseCurrentProjectIdsAndPreserveTheViewOrderWithinEachSection() = runTest {
        val task = today.first()
        today = listOf(
            task,
            task.copy(id = 2, projectId = 2),
            task.copy(id = 3),
            task.copy(id = 4, done = true),
            task.copy(id = 5, projectId = 9),
            task.copy(id = 6, projectId = 2),
        )
        projects.upsert(ProjectEntity(id = 3, title = "Empty"))
        val config = WidgetConfig(groupByProject = true)
        assertEquals(listOf(listOf(1L, 3L)), states.prepare(day).build(config).projectGroups().map { group ->
            group.map { it.id }
        })

        projects.upsert(ProjectEntity(id = 2, title = "Home", parentProjectId = 1))
        val state = states.prepare(day).build(config)
        assertTrue(state.groupByProject)
        assertEquals(listOf(1L, 3L, 2L, 6L), state.tasks.map { it.id })
        assertEquals(listOf(listOf(1L, 3L), listOf(2L, 6L)), state.projectGroups().map { group ->
            group.map { it.id }
        })
        assertEquals(listOf("Home", "Home"), state.projectGroups().map { it.first().projectName })
        assertEquals(4, state.totalCount)
        assertFalse(states.prepare(day).build(WidgetConfig()).groupByProject)

        val afterCompletion = state.copy(tasks = state.tasks.filterNot { it.projectId == 2L })
        assertEquals(listOf(1L), afterCompletion.projectGroups().map { it.first().projectId })
    }

    @Test
    fun savedProjectOrderSurvivesRefreshAndSortsTasksOnlyWithinEachProject() = runTest {
        projects.upsert(ProjectEntity(id = 2, title = "Other"))
        projects.upsert(ProjectEntity(id = 3, title = "No matching tasks"))
        val task = today.first()
        today = listOf(
            task.copy(title = "Zulu"),
            task.copy(id = 2, projectId = 2, title = "Zulu"),
            task.copy(id = 3, title = "Alpha"),
            task.copy(id = 4, projectId = 2, title = "Alpha"),
        )
        val config = WidgetConfig(
            viewType = WidgetViewType.ALL_TASKS,
            groupByProject = true,
            projectOrder = listOf(9L, 2L, 3L, 1L),
            taskOrderKey = TaskOrder.TITLE.key,
        )
        fun TaskWidgetState.idsByProject() = projectGroups().map { group -> group.map { it.id } }
        assertEquals(listOf(listOf(4L, 2L), listOf(3L, 1L)), states.prepare(day).build(config).idsByProject())

        projects.upsert(ProjectEntity(id = 5, title = "A new project"))
        today = listOf(task.copy(id = 5, projectId = 5)) + today.reversed()
        val batch = states.prepare(day)
        assertEquals(
            listOf(listOf(4L, 2L), listOf(3L, 1L), listOf(5L)),
            batch.build(config).idsByProject(),
        )
        assertEquals(
            listOf(5L, 4L, 3L, 2L, 1L),
            batch.build(config.copy(groupByProject = false)).tasks.map { it.id },
        )
        assertEquals(
            listOf(5L, 1L, 2L),
            batch.build(config.copy(projectOrder = emptyList())).projectGroups().map { it.first().projectId },
        )
    }

    @Test
    fun groupedAnytimeAppliesTheGlobalCapAfterFilteringAndReportsTheFullCount() = runTest {
        val task = today.first()
        today = (1L..22L).map { task.copy(id = it, projectId = 9) } +
            (23L..47L).map { task.copy(id = it) }

        val config = WidgetConfig(viewType = WidgetViewType.ANYTIME, groupByProject = true)
        val state = states.prepare(day).build(config)
        assertEquals(25, state.totalCount)
        assertEquals((23L..42L).toList(), state.tasks.map { it.id })
        assertTrue(states.prepare(day).build(config.copy(groupByProject = false)).tasks.isEmpty())
    }

    @Test
    fun allTasksIncludesTheInboxAndUndatedTasksBeyondThePreviewLimit() = runTest {
        storage.storeInboxProjectId(1)
        projects.upsert(ProjectEntity(id = 2, title = "New project"))
        val task = today.first()
        today = (1L..22L).flatMap {
            listOf(task.copy(id = it, dueDate = ""), task.copy(id = it + 100, projectId = 2))
        } + listOf(task.copy(id = 50, done = true), task.copy(id = 51, projectId = 9))

        val config = WidgetConfig(viewType = WidgetViewType.ALL_TASKS, groupByProject = true)
        val batch = states.prepare(day)
        val grouped = batch.build(config)
        assertEquals(44, grouped.totalCount)
        assertEquals(
            listOf((1L..22L).toList(), (101L..122L).toList()),
            grouped.projectGroups().map { tasks -> tasks.map { it.id } },
        )
        val flat = batch.build(config.copy(groupByProject = false))
        assertEquals((1L..22L).flatMap { listOf(it, it + 100L) }, flat.tasks.map { it.id })
    }

    @Test
    fun aWidgetForAnArchivedProjectSaysSo() = runTest {
        val state = states.prepare(day).build(WidgetConfig(WidgetViewType.PROJECT, "9", "Old"))
        assertEquals(TaskWidgetStates.PROJECT_UNAVAILABLE, state.error)
        assertTrue(state.tasks.isEmpty())
    }

    @Test
    fun theWidgetPreferencesAreCarried() = runTest {
        val state = states.prepare(day).build(WidgetConfig(WidgetViewType.PROJECT, "1", "Home"))
        assertTrue(state.smartAdd)
        assertTrue(state.contextNav)
        assertTrue(state.voiceButton)
        assertEquals(0L, state.addToProjectId)
        assertEquals("Home", state.viewName)
    }

    @Test
    fun theVoiceButtonCanBeTurnedOff() = runTest {
        widgetPrefs.setVoiceButton(false)
        val state = states.prepare(day).build(WidgetConfig(WidgetViewType.TODAY, "", "Today"))
        assertFalse(state.voiceButton)
        assertTrue(state.smartAdd)
    }

    @Test
    fun signedOutNeedsAServerAndAToken() = runTest {
        assertFalse(states.isSignedIn())
        storage.storeVikunjaUrl("https://vikunja.test")
        assertFalse(states.isSignedIn())
        storage.storeApiToken("tk_1", 0L)
        assertTrue(states.isSignedIn())
        assertEquals(TaskWidgetStates.SIGNED_OUT, states.signedOut().error)
    }

    @Test
    fun aProjectWidgetListsInTheProjectsSyncedOrderBeforeTakingRows() = runTest {
        val rows = listOf(
            TaskEntity(id = 1, title = "b undated", projectId = 4, position = 200.0),
            TaskEntity(id = 2, title = "a dated later", projectId = 4, dueDate = "2026-10-20T10:00:00Z", position = 100.0),
            TaskEntity(id = 3, title = "c dated sooner", projectId = 4, dueDate = "2026-10-13T10:00:00Z", position = 300.0),
            TaskEntity(id = 4, title = "d undated", projectId = 4, position = 50.0),
        )
        val dao = object : TaskDao by FakeTaskDao() {
            override suspend fun getByProjectIdSync(projectId: Long, limit: Int): List<TaskEntity> {
                assertEquals(-1, limit, "the whole list is read, then sorted")
                return rows
            }
        }
        val entries = com.rendyhd.vicu.data.repository.InMemorySyncedEntries()
        val orders = com.rendyhd.vicu.data.repository.TaskOrderPreferences(entries)
        val widget = TaskWidgetStates(
            taskDao = dao,
            projectDao = FakeProjectDao(listOf(ProjectEntity(id = 4, title = "Garden"))),
            taskRepository = FakeTaskRepository(),
            tokenStorage = storage,
            customListStore = CustomListStore(InMemoryPreferencesDataStore()),
            widgetPrefsStore = WidgetPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            time = FixedTimeSource(Instant.parse("2026-10-12T08:00:00Z"), zone),
            collaborationContext = { CustomListContext.NONE },
            taskOrders = orders,
        )
        val config = WidgetConfig(WidgetViewType.PROJECT, "4", "Garden")

        assertEquals(listOf(3L, 2L, 4L, 1L), widget.prepare(day).build(config).tasks.map { it.id }, "default: dated first, then manual")

        orders.setProjectOrder(4, com.rendyhd.vicu.domain.model.TaskOrder.TITLE)
        assertEquals(listOf(2L, 1L, 3L, 4L), widget.prepare(day).build(config).tasks.map { it.id })
    }
}
