package com.rendyhd.vicu.ui.screens.guided

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.ReviewPrefs
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.repository.TaskOrderPreferences
import com.rendyhd.vicu.data.repository.WorkModeSource
import com.rendyhd.vicu.data.repository.WorkState
import com.rendyhd.vicu.data.repository.stateOrOff
import com.rendyhd.vicu.util.WorkProject
import com.rendyhd.vicu.util.WorkScopeRules
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
    /** Work and Personal (contract 22.4): the queues and the projects reviewed follow the mode; none in tests. */
    private val workMode: WorkModeSource? = null,
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
        combine(dayClock.day, workMode.stateOrOff()) { day, work -> day to work },
    ) { all, projectList, s, (day, work) -> build(all, projectList, s, day, work) }

    /** The world as it is now. */
    suspend fun now(): GuidedWorld {
        dayClock.refresh()
        return world.first()
    }

    private fun build(all: List<Task>, projectList: List<Project>, s: Settings, day: ClockDay, work: WorkState) = GuidedWorld(
        tasks = work.filter(all),
        projects = if (!work.filtering) {
            projectList
        } else {
            val shown = WorkScopeRules.projectsShownInMode(
                projectList.map { WorkProject(it.id, it.parentProjectId.takeIf { parent -> parent > 0 }) },
                work.scope.projects,
                work.mode,
                s.inboxId,
            )
            projectList.filter { it.id in shown }
        },
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
