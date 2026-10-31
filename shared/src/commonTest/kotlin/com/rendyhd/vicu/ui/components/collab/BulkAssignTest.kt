package com.rendyhd.vicu.ui.components.collab

import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.ProjectAccess
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.util.Permission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Assign for a selection (docs/cross-app-semantics-v1.md section 9.2). */
class BulkAssignTest {
    private val me = User(1, "me", "Me")
    private val maya = User(7, "maya", "Maya")
    private val sam = User(8, "sam", "Sam")

    private val state = CollabState(
        me = 1,
        projects = mapOf(
            10L to ProjectAccess(shared = true, members = listOf(me, maya, sam), permission = Permission.WRITE),
            11L to ProjectAccess(shared = true, members = listOf(me, maya), permission = Permission.ADMIN),
            12L to ProjectAccess(shared = true, members = listOf(me, maya), permission = Permission.READ),
            13L to ProjectAccess(shared = false, members = listOf(me), permission = Permission.ADMIN),
        ),
    )

    @Test
    fun offeredOnlyWhenEveryProjectIsSharedAndWritable() {
        assertTrue(BulkAssign.canAssign(setOf(10, 11), state))
        assertFalse(BulkAssign.canAssign(setOf(10, 12), state))
        assertFalse(BulkAssign.canAssign(setOf(10, 13), state))
        assertFalse(BulkAssign.canAssign(setOf(99), state))
        assertFalse(BulkAssign.canAssign(emptySet(), state))
    }

    @Test
    fun listsTheMembersOfEveryProjectYouFirst() {
        val tasks = listOf(Task(id = 1, title = "a", projectId = 10), Task(id = 2, title = "b", projectId = 11))
        assertEquals(listOf(me, maya), BulkAssign.people(tasks, state))
        assertEquals(listOf(me, maya, sam), BulkAssign.people(tasks.take(1), state))
    }

    @Test
    fun ticksAndWhatAPickChanges() {
        val a = Task(id = 1, title = "a", projectId = 10, assignees = listOf(maya))
        val b = Task(id = 2, title = "b", projectId = 10)
        val tasks = listOf(a, b)

        assertEquals(BulkAssign.Mark.SOME, BulkAssign.mark(maya, tasks))
        assertEquals(BulkAssign.Mark.NONE, BulkAssign.mark(sam, tasks))

        // Partly ticked: added where missing.
        val add = BulkAssign.changes(maya, tasks)
        assertEquals(listOf(2L to true), add.map { it.first.id to it.second })
        val after = BulkAssign.applied(tasks, maya, add)
        assertEquals(BulkAssign.Mark.ALL, BulkAssign.mark(maya, after))

        // Ticked: removed from all.
        val remove = BulkAssign.changes(maya, after)
        assertEquals(listOf(1L to false, 2L to false), remove.map { it.first.id to it.second })
        assertEquals(BulkAssign.Mark.NONE, BulkAssign.mark(maya, BulkAssign.applied(after, maya, remove)))
    }
}
