package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task

/**
 * Which reminders of a project this device shows (a device setting, never synced: people who
 * share a project each pick their own).
 */
enum class ProjectNotifyMode {
    /** Every reminder of the project (also every project nobody chose a mode for). */
    ALL,

    /** Only tasks assigned to you, or to nobody. A task assigned only to others stays quiet. */
    MINE,

    /** No reminders from this project on this device. */
    OFF,
}

/** The pure rules of per-project notifications. */
object ProjectNotifyRules {
    /** Whether a task in a project with [mode] may notify you. */
    fun allows(mode: ProjectNotifyMode, assigneeIds: Collection<Long>, me: Long?): Boolean = when (mode) {
        ProjectNotifyMode.ALL -> true
        ProjectNotifyMode.OFF -> false
        ProjectNotifyMode.MINE -> me == null || assigneeIds.isEmpty() || me in assigneeIds
    }
}

/**
 * A snapshot of the per-project modes, asked by everything that notifies about a task: reminder
 * alarms (Android and iOS), the daily summary and location reminders.
 */
class ReminderGate(
    private val modes: Map<Long, ProjectNotifyMode>,
    private val me: Long?,
) {
    /** A project without a chosen mode rings for everything, as before this setting existed. */
    fun modeOf(projectId: Long): ProjectNotifyMode = modes[projectId] ?: ProjectNotifyMode.ALL

    fun allows(projectId: Long, assigneeIds: Collection<Long>): Boolean =
        ProjectNotifyRules.allows(modeOf(projectId), assigneeIds, me)

    fun allows(task: Task): Boolean = allows(task.projectId, task.assignees.map { it.id })

    /** True when no project is limited: callers may keep their unfiltered fast path. */
    val allowsEverything: Boolean get() = modes.values.all { it == ProjectNotifyMode.ALL }

    companion object {
        /** Lets everything through (tests, and before the modes are known). */
        val OPEN = ReminderGate(emptyMap(), null)
    }
}
