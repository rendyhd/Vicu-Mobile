package com.rendyhd.vicu.ai.dictation

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.datetime.TimeZone

/** What Add of a brain dump created: the new projects' ids in creation order, and the tasks. */
data class AddedPlan(val projectIds: List<Long>, val drafts: AddedDrafts?, val error: String?)

/**
 * Adds a brain dump's plan (`docs/ai-assistant-v1.md`, section 4.8.5): the new projects in order
 * (a parent always comes first), then the drafts into them through [DraftWriter]. Creating a
 * project needs the server, so a project that fails stops Add before any task and removes the
 * projects already created.
 */
class PlanWriter(
    private val projects: ProjectRepository,
    private val drafts: DraftWriter,
) {
    suspend fun add(plan: CheckResult, zone: TimeZone): AddedPlan {
        val ids = HashMap<String, Long>()
        val created = mutableListOf<Long>()
        for (p in plan.projects) {
            val parentId = p.parentRef?.let { ids[it] } ?: p.parentId ?: 0L
            val result = projects.create(Project(id = 0, title = p.title, parentProjectId = parentId))
            if (result is NetworkResult.Success) {
                ids[p.ref] = result.data.id
                created += result.data.id
            } else {
                for (id in created.asReversed()) projects.delete(id)
                val reason = (result as? NetworkResult.Error)?.message
                return AddedPlan(
                    emptyList(),
                    null,
                    "Could not create the project “${p.title}”" + (reason?.let { " ($it)" } ?: "") + ". Nothing was added.",
                )
            }
        }
        val placed = plan.drafts.map { d ->
            val id = d.projectRef?.let { ids[it] }
            if (id != null) d.copy(projectId = id, projectRef = null) else d.copy(projectRef = null)
        }
        return AddedPlan(created, drafts.add(placed, zone), null)
    }

    /** Undo of Add: the tasks, then the projects, newest first. */
    suspend fun undo(added: AddedPlan) {
        added.drafts?.let { drafts.undo(it) }
        for (id in added.projectIds.asReversed()) projects.delete(id)
    }
}
