package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Per-project notification modes: which tasks of a project may notify this device. */
class ProjectNotificationsTest {

    private val me = 7L
    private val partner = 8L

    @Test
    fun `mine allows my tasks and unassigned tasks only`() {
        assertTrue(ProjectNotifyRules.allows(ProjectNotifyMode.MINE, emptyList(), me))
        assertTrue(ProjectNotifyRules.allows(ProjectNotifyMode.MINE, listOf(me), me))
        assertTrue(ProjectNotifyRules.allows(ProjectNotifyMode.MINE, listOf(partner, me), me))
        assertFalse(ProjectNotifyRules.allows(ProjectNotifyMode.MINE, listOf(partner), me))
    }

    @Test
    fun `all and off do not look at assignees`() {
        assertTrue(ProjectNotifyRules.allows(ProjectNotifyMode.ALL, listOf(partner), me))
        assertFalse(ProjectNotifyRules.allows(ProjectNotifyMode.OFF, emptyList(), me))
        assertFalse(ProjectNotifyRules.allows(ProjectNotifyMode.OFF, listOf(me), me))
    }

    @Test
    fun `the gate applies the mode of each project to its tasks`() {
        val house = 1L
        val paul = 2L
        val gate = ReminderGate(mapOf(house to ProjectNotifyMode.MINE, paul to ProjectNotifyMode.OFF), me)

        fun task(project: Long, vararg assignees: Long) =
            Task(id = 1, title = "t", projectId = project, assignees = assignees.map { User(it, "u$it", "") })

        assertTrue(gate.allows(task(house)))
        assertTrue(gate.allows(task(house, me)))
        assertFalse(gate.allows(task(house, partner)))
        assertFalse(gate.allows(task(paul)))
        // A project the device does not know yet rings.
        assertTrue(gate.allows(task(99, partner)))
        assertFalse(gate.allowsEverything)
        assertTrue(ReminderGate.OPEN.allowsEverything)
    }
}
