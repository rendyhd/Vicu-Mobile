package com.rendyhd.vicu.util

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.data.local.ReminderAlarmRegistry
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.domain.model.User
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Alarms follow the per-project modes: a held-back task gets none and loses the ones it had. */
class ReminderAlarmCoordinatorGateTest {

    private class FakeBackend : ReminderAlarmBackend {
        val live = LinkedHashSet<Int>()
        override fun schedule(alarm: ReminderAlarmSpec, taskTitle: String) { live += alarm.requestCode }
        override fun cancel(requestCode: Int) { live -= requestCode }
    }

    private val me = 7L
    private val partner = 8L
    private val house = 1L
    private val now = Instant.parse("2026-10-06T08:00:00Z").toEpochMilliseconds()
    private val registry = ReminderAlarmRegistry(InMemoryPreferencesDataStore(), authTestJson)
    private val backend = FakeBackend()
    private var modes = mapOf(house to ProjectNotifyMode.MINE)
    private val coordinator = ReminderAlarmCoordinator(
        registry,
        backend,
        nowMillis = { now },
        gate = { ReminderGate(modes, me) },
    )

    private fun task(id: Long, vararg assignees: Long) = Task(
        id = id,
        title = "Task $id",
        projectId = house,
        reminders = listOf(TaskReminder(reminder = "2026-10-06T10:00:00Z")),
        assignees = assignees.map { User(it, "u$it", "") },
    )

    private fun code(taskId: Long) = ReminderAlarms.requestCode(taskId, 0)

    @Test
    fun `a task assigned only to someone else gets no alarm`() = runTest {
        coordinator.reconcileAll(listOf(task(1), task(2, me), task(3, partner)))

        assertEquals(setOf(code(1), code(2)), backend.live)
    }

    @Test
    fun `assigning a task to someone else cancels its alarm`() = runTest {
        coordinator.scheduleForTask(task(1))
        assertEquals(setOf(code(1)), backend.live)

        coordinator.reconcileTasks(changed = listOf(task(1, partner)), goneTaskIds = emptySet())

        assertTrue(backend.live.isEmpty())
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `muting a project cancels its alarms on the next full pass`() = runTest {
        val tasks = listOf(task(1), task(2, me))
        coordinator.reconcileAll(tasks)
        assertEquals(2, backend.live.size)

        modes = mapOf(house to ProjectNotifyMode.OFF)
        coordinator.reconcileAll(tasks)

        assertTrue(backend.live.isEmpty())
    }
}
