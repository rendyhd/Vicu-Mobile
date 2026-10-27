package com.rendyhd.vicu.ui.screens.guided

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.ReviewPrefs
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.repository.TaskOrderPreferences
import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskOrderSettings
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.GuidedWorld
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * The account as the guided rules read it (docs/cross-app-semantics-v1.md, section 14): every
 * task and project, the Inbox, the day, the review settings, the task orders and who may change
 * what. Ask's agenda, the sessions and the weekly review all read it from here.
 */
class GuidedWorldSource(
    private val tasks: TaskRepository,
    private val projects: ProjectRepository,
    private val auth: AuthManager,
    private val reviewPrefs: ReviewPrefsStore,
    private val taskOrder: TaskOrderPreferences,
    /** Who may change what (the collaboration repository's state). */
    private val collabState: Flow<CollabState>,
    private val dayClock: DayClock,
) {
    private class Settings(val inboxId: Long?, val review: ReviewPrefs, val order: TaskOrderSettings, val collab: CollabState)

    private val settings: Flow<Settings> = combine(
        auth.inboxProjectId,
        reviewPrefs.getPrefs(),
        taskOrder.settings,
        collabState,
    ) { inbox, review, order, collab -> Settings(inbox?.takeIf { it > 0 }, review, order, collab) }

    val world: Flow<GuidedWorld> = combine(
        tasks.getAllTasksFlat(),
        projects.getAllIncludingArchived(),
        settings,
        dayClock.day,
    ) { all, projectList, s, day -> build(all, projectList, s, day) }

    /** The world as it is now. */
    suspend fun now(): GuidedWorld {
        dayClock.refresh()
        return world.first()
    }

    private fun build(all: List<Task>, projectList: List<Project>, s: Settings, day: ClockDay) = GuidedWorld(
        tasks = all,
        projects = projectList,
        inboxId = s.inboxId,
        today = day.date,
        zone = day.zone,
        reviewEnabled = s.review.enabled,
        reviewCadenceDays = s.review.defaultCadenceDays,
        reviewExcludeInbox = s.review.excludeInbox,
        order = s.order,
        permission = { p -> s.collab.projects[p.id]?.permission ?: p.maxPermission },
    )
}
