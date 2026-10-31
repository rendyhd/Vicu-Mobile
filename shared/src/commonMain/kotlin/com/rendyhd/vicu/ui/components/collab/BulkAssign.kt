package com.rendyhd.vicu.ui.components.collab

import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.User

/**
 * Assign for a selection (cross-app semantics 9.2): when it is offered, who is listed, how each
 * person is ticked and what picking them changes. Pure; the sheet sends each change through the
 * single task's assign.
 */
object BulkAssign {
    enum class Mark { NONE, SOME, ALL }

    /** Every selected task is in a shared project where you may write. */
    fun canAssign(projectIds: Set<Long>, state: CollabState): Boolean =
        projectIds.isNotEmpty() && projectIds.all { id -> state.access(id).let { it.shared && it.canWrite } }

    /** The members of every project of [tasks], in the first project's order (you first, 9.3). */
    fun people(tasks: List<Task>, state: CollabState): List<User> {
        val lists = tasks.map { it.projectId }.distinct().map { state.access(it).members }
        if (lists.isEmpty()) return emptyList()
        val common = lists.drop(1).fold(lists.first().mapTo(mutableSetOf()) { it.id }) { ids, members ->
            ids.apply { retainAll(members.mapTo(mutableSetOf()) { it.id }) }
        }
        return lists.first().filter { it.id in common }
    }

    fun mark(person: User, tasks: List<Task>): Mark {
        val assigned = tasks.count { task -> task.assignees.any { it.id == person.id } }
        return when {
            assigned == 0 -> Mark.NONE
            assigned == tasks.size -> Mark.ALL
            else -> Mark.SOME
        }
    }

    /** Picking [person]: assigned to all, removed from all; otherwise added where missing. */
    fun changes(person: User, tasks: List<Task>): List<Pair<Task, Boolean>> =
        if (mark(person, tasks) == Mark.ALL) {
            tasks.map { it to false }
        } else {
            tasks.filter { task -> task.assignees.none { it.id == person.id } }.map { it to true }
        }

    /** [tasks] with [changes] made, so the ticks follow at once. */
    fun applied(tasks: List<Task>, person: User, changes: List<Pair<Task, Boolean>>): List<Task> {
        val change = changes.associate { (task, assign) -> task.id to assign }
        return tasks.map { task ->
            when (change[task.id]) {
                true -> task.copy(assignees = task.assignees + person)
                false -> task.copy(assignees = task.assignees.filterNot { it.id == person.id })
                null -> task
            }
        }
    }
}
