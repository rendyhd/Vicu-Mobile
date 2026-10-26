package com.rendyhd.vicu.ui.screens.shared

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.repository.ProjectCategoryPreferences
import com.rendyhd.vicu.data.repository.SyncedPreferences
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.ProjectShares
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PositionedId
import com.rendyhd.vicu.util.ProjectCategories
import com.rendyhd.vicu.util.ProjectCategory
import com.rendyhd.vicu.util.ProjectCategoryState
import com.rendyhd.vicu.util.ProjectGroup
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.planDropAmong
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/** What a project action came to: a confirmation to show (none for some), or the error. */
sealed interface ProjectActionResult {
    data class Done(val message: String?) : ProjectActionResult
    data class Failed(val error: String) : ProjectActionResult
}

/** What saving a new order of a project's siblings came to. */
sealed interface SiblingMoveResult {
    /** Nothing to save: the project is not among the siblings it was given. */
    data object NotMoved : SiblingMoveResult

    /** Every position was saved, or queued to be sent when the server can be reached. */
    data object Saved : SiblingMoveResult

    /** The server refused a position; [message] is what to tell the user. */
    data class Failed(val message: String) : SiblingMoveResult
}

/** The message for a new order that could not be saved, with the server's reason when there is one. */
fun orderNotSavedMessage(detail: String): String =
    if (detail.isBlank()) "Could not save the new order" else "Could not save the new order: $detail"

/** "Archive" and "Delete" are not offered on the Inbox, and this is said when one gets through anyway. */
const val ARCHIVE_INBOX_REFUSED = "Select another Inbox project before archiving this project"

/**
 * The things a user can do to a project, in one place for every screen that offers them (Settings,
 * the drawer, the project screen): create (a subproject is a create with a parent), edit, make it the
 * Inbox, archive, restore, delete, mark it reviewed, bring it back into review, move it among its
 * siblings, and file it in a category (and the categories themselves). Each answers with the message to show, the same wherever the action was started.
 *
 * Writes go through [ProjectRepository], so edits, archiving and review changes are applied locally
 * at once and queued when the server cannot be reached; creating and deleting need the server.
 */
class ProjectActions(
    private val projectRepository: ProjectRepository,
    private val authManager: AuthManager,
    private val repositoryHooks: PlatformRepositoryHooks,
    private val dayClock: DayClock,
    /** Sharing (cross-app semantics 9.7); null where the app is built without it (tests). */
    private val collaboration: CollaborationRepository? = null,
    /** Where the Inbox choice reaches the other devices; null in tests that do not sync it. */
    private val syncedPreferences: SyncedPreferences? = null,
    /** The project categories (section 13); null in tests that do not use them. */
    private val categoryPreferences: ProjectCategoryPreferences? = null,
    /** Where a deleted category's Undo is offered, and the scope that outlives the screen to run it. */
    private val appMessages: AppMessages? = null,
    private val appScope: CoroutineScope? = null,
) {
    /** The Inbox project's id, null until one is chosen. */
    val inboxProjectId: Flow<Long?> get() = authManager.inboxProjectId

    /** Sibling moves are saved one at a time, so a quick second drop cannot overtake the first. */
    private val moveMutex = Mutex()

    /** Creates a project; with a [parentProjectId] other than 0 it is a subproject of that one. */
    suspend fun create(title: String, hexColor: String, parentProjectId: Long): ProjectActionResult {
        val project = Project(id = 0, title = title, hexColor = hexColor, parentProjectId = parentProjectId)
        return projectRepository.create(project).toResult("Project created")
    }

    suspend fun edit(project: Project, title: String, hexColor: String, parentProjectId: Long): ProjectActionResult {
        val updated = project.copy(title = title, hexColor = hexColor, parentProjectId = parentProjectId)
        return projectRepository.update(updated).toResult("Project updated")
    }

    /** Makes [projectId] the Inbox, on every device of the account: the same as the Inbox project picker. */
    suspend fun setInbox(projectId: Long): ProjectActionResult {
        syncedPreferences?.chooseInbox(projectId) ?: authManager.onInboxProjectSelected(projectId)
        return ProjectActionResult.Done("Inbox project updated")
    }

    /** Archives [project]. The Inbox cannot be archived until another project is the Inbox. */
    suspend fun archive(project: Project): ProjectActionResult {
        if (project.id == authManager.getInboxProjectId()) return ProjectActionResult.Failed(ARCHIVE_INBOX_REFUSED)
        return setArchived(project, archived = true)
    }

    suspend fun restore(project: Project): ProjectActionResult = setArchived(project, archived = false)

    private suspend fun setArchived(project: Project, archived: Boolean): ProjectActionResult {
        val result = projectRepository.update(project.copy(isArchived = archived))
        // A widget set to this project shows it gone, or back.
        if (result !is NetworkResult.Error) repositoryHooks.updateWidgets()
        return result.toResult(if (archived) "Project archived" else "Project restored")
    }

    /** Deletes the project and its tasks. */
    suspend fun delete(projectId: Long): ProjectActionResult =
        projectRepository.delete(projectId).toResult("Project deleted")

    /**
     * Records a review of [project] today, keeping a cadence of its own. The confirmation is the
     * text of the snackbar that offers to undo it ([undoReview]).
     */
    suspend fun markReviewed(project: Project): ProjectActionResult {
        val today = dayClock.day.value.date
        val updated = project.copy(description = ReviewMetadata.reviewedDescription(project.description, today))
        return projectRepository.update(updated).toResult(markedReviewedMessage(project))
    }

    /** Puts [previous] (the project as it was before [markReviewed]) back. Nothing to say when it worked. */
    suspend fun undoReview(previous: Project): ProjectActionResult =
        projectRepository.update(previous).toResult(null)

    /**
     * Brings an excluded project back into review: its footer is rewritten as never reviewed, its own
     * cadence kept, the same rewrite the review screen's exclude path makes in reverse (and the
     * desktop's `excludeFromReviewRequest(project, false)`).
     */
    suspend fun includeInReview(project: Project): ProjectActionResult {
        val description = ReviewMetadata.excludedDescription(project.description, excluded = false)
        return projectRepository.update(project.copy(description = description))
            .toResult("Included \"${project.title}\" in review")
    }

    /**
     * Saves the order of a project's siblings after [movedId] was dragged (or moved a place by a
     * screen reader): [siblingsInNewOrder] is every project of that level, in the order now shown.
     * The positions are planned like the desktop app's: one position between the new neighbours, or
     * a renumbering of the level when they leave no room. The first refusal stops the rest.
     */
    suspend fun moveAmongSiblings(movedId: Long, siblingsInNewOrder: List<Project>): SiblingMoveResult {
        val plan = planDropAmong(siblingsInNewOrder.map { PositionedId(it.id, it.position) }, movedId)
            ?: return SiblingMoveResult.NotMoved
        val byId = siblingsInNewOrder.associateBy { it.id }
        return moveMutex.withLock {
            for (update in plan.updates) {
                val project = byId[update.id] ?: continue
                val result = projectRepository.update(project.copy(position = update.position))
                if (result is NetworkResult.Error) return@withLock SiblingMoveResult.Failed(orderNotSavedMessage(result.message))
            }
            SiblingMoveResult.Saved
        }
    }

    // --- Categories (cross-app semantics 13): the user's own, synced through the preferences -----

    /** The categories and the projects in them; none where the app is built without them (tests). */
    val categories: Flow<ProjectCategoryState>
        get() = categoryPreferences?.state ?: flowOf(ProjectCategoryState.EMPTY)

    /** A new category [name], last. With [project], that project moves into it. */
    suspend fun createCategory(name: String, project: Project? = null): ProjectActionResult {
        val preferences = categoryPreferences ?: return ProjectActionResult.Failed(CATEGORIES_UNAVAILABLE)
        val id = randomUuid()
        val written = preferences.change { entries ->
            val created = ProjectCategories.planNew(entries, id, name)
            if (created.isEmpty() || project == null) created else created + ProjectCategories.planAssign(entries + created, project.id, id)
        }
        if (written.isEmpty()) return ProjectActionResult.Failed(ENTER_A_NAME)
        return ProjectActionResult.Done(project?.let { movedToCategoryMessage(it, name.trim()) })
    }

    suspend fun renameCategory(categoryId: String, name: String): ProjectActionResult {
        if (name.isBlank()) return ProjectActionResult.Failed(ENTER_A_NAME)
        val preferences = categoryPreferences ?: return ProjectActionResult.Failed(CATEGORIES_UNAVAILABLE)
        preferences.change { ProjectCategories.planRename(it, categoryId, name) }
        return ProjectActionResult.Done(null)
    }

    /** Moves [categoryId] [by] places in the display order: -1 up, 1 down. */
    suspend fun moveCategory(categoryId: String, by: Int): ProjectActionResult {
        val preferences = categoryPreferences ?: return ProjectActionResult.Failed(CATEGORIES_UNAVAILABLE)
        preferences.change { entries ->
            val index = ProjectCategories.state(entries).categories.indexOfFirst { it.id == categoryId }
            if (index < 0 || index + by < 0) emptyMap() else ProjectCategories.planMove(entries, categoryId, index + by)
        }
        return ProjectActionResult.Done(null)
    }

    /**
     * Deletes [categoryId]; its projects stay, without a category. Answers the values it replaced,
     * which [restoreCategories] puts back (the Undo of [deletedCategoryMessage]); empty when nothing changed.
     */
    suspend fun deleteCategory(categoryId: String): Map<String, JsonElement?> {
        val preferences = categoryPreferences ?: return emptyMap()
        var previous = emptyMap<String, JsonElement?>()
        preferences.change { entries ->
            ProjectCategories.planDelete(entries, categoryId).also { writes -> previous = writes.keys.associateWith { entries[it] } }
        }
        return previous
    }

    suspend fun restoreCategories(previous: Map<String, JsonElement?>) {
        categoryPreferences?.write(previous)
    }

    /** Deletes [category] (its projects stay) and offers Undo on the app-wide snackbar, from whichever screen. */
    suspend fun deleteCategoryOfferingUndo(category: ProjectCategory) {
        val previous = deleteCategory(category.id)
        if (previous.isEmpty()) return
        appMessages?.post(deletedCategoryMessage(category.name), "Undo") {
            appScope?.launch { restoreCategories(previous) }
        }
    }

    /** Moves [project] into [category] (null: out of its category) from a menu; its position stays. */
    suspend fun assignCategory(project: Project, category: ProjectCategory?): ProjectActionResult {
        val preferences = categoryPreferences ?: return ProjectActionResult.Failed(CATEGORIES_UNAVAILABLE)
        var before: ProjectCategory? = null
        val written = preferences.change { entries ->
            before = ProjectCategories.state(entries).categoryOf(project.id)
            ProjectCategories.planAssign(entries, project.id, category?.id)
        }
        if (written.isEmpty()) return ProjectActionResult.Done(null)
        val message = if (category != null) movedToCategoryMessage(project, category.name) else before?.let { movedOutOfCategoryMessage(project, it.name) }
        return ProjectActionResult.Done(message)
    }

    /**
     * "Add category above" [projectId] (section 13.4): a new category [name] that starts at that
     * project and holds it and every project after it in its group.
     */
    suspend fun addCategoryAbove(projectId: Long, name: String): ProjectActionResult {
        if (name.isBlank()) return ProjectActionResult.Failed(ENTER_A_NAME)
        val preferences = categoryPreferences ?: return ProjectActionResult.Failed(CATEGORIES_UNAVAILABLE)
        val id = randomUuid()
        preferences.change { entries -> ProjectCategories.planSplit(entries, layout(entries), projectId, id, name) }
        return ProjectActionResult.Done(null)
    }

    /**
     * Saves a drag that took the top-level project [movedId] into the group of [categoryId] (null:
     * the projects without one): its category, then its position among [groupInNewOrder], the
     * top-level projects of that group in the order now shown. Into an empty group it goes after
     * every top-level project (section 13.4).
     */
    suspend fun moveIntoCategory(movedId: Long, categoryId: String?, groupInNewOrder: List<Project>): SiblingMoveResult {
        val preferences = categoryPreferences ?: return SiblingMoveResult.NotMoved
        if (groupInNewOrder.none { it.id == movedId }) return SiblingMoveResult.NotMoved
        preferences.change { entries -> ProjectCategories.planAssign(entries, movedId, categoryId) }
        val siblings = if (groupInNewOrder.size > 1) {
            groupInNewOrder
        } else {
            val projects = projectRepository.getAll().first()
            val listed = projects.filter { !it.isArchived && it.id != authManager.getInboxProjectId() }
            val ids = listed.mapTo(HashSet()) { it.id }
            listed.filter { it.id != movedId && (it.parentProjectId == 0L || it.parentProjectId !in ids) }
                .sortedWith(compareBy({ it.position }, { it.id })) + groupInNewOrder
        }
        return moveAmongSiblings(movedId, siblings)
    }

    /** The project list's groups as section 13.2 lays them out, from the projects as stored now. */
    private suspend fun layout(entries: Map<String, JsonElement?>): List<ProjectGroup<Long>> =
        ProjectCategories.layout(projectRepository.getAll().first(), authManager.getInboxProjectId(), entries)

    // --- Sharing (admins only, online only) ------------------------------------------------------

    /** Who [projectId] is shared with: its owner, the people and the teams. */
    suspend fun shares(projectId: Long): NetworkResult<ProjectShares> =
        collaboration?.shares(projectId) ?: NetworkResult.Error(SHARING_UNAVAILABLE)

    /** The person with exactly this username, or null when there is none. */
    suspend fun findUser(username: String): NetworkResult<User?> =
        collaboration?.findUser(username) ?: NetworkResult.Error(SHARING_UNAVAILABLE)

    /** Shares [project] with [person] at [permission] (0 can view, 1 can edit, 2 admin). */
    suspend fun share(project: Project, person: User, permission: Int): ProjectActionResult =
        (collaboration?.share(project.id, person.username, permission) ?: NetworkResult.Error(SHARING_UNAVAILABLE))
            .toResult("Shared \"${project.title}\" with ${displayName(person)}")

    suspend fun updateShare(project: Project, person: User, permission: Int): ProjectActionResult =
        (collaboration?.updateShare(project.id, person.username, permission) ?: NetworkResult.Error(SHARING_UNAVAILABLE))
            .toResult(null)

    /** Takes [person]'s access away; the message is the one the Undo sits beside. */
    suspend fun unshare(project: Project, person: User): ProjectActionResult =
        (collaboration?.removeShare(project.id, person.username) ?: NetworkResult.Error(SHARING_UNAVAILABLE))
            .toResult(noLongerHasAccessMessage(person))

    companion object {
        fun markedReviewedMessage(project: Project): String = "Marked \"${project.title}\" reviewed"

        fun noLongerHasAccessMessage(person: User): String = "${displayName(person)} no longer has access."

        fun movedToCategoryMessage(project: Project, category: String): String = "Moved \"${project.title}\" to $category"

        fun movedOutOfCategoryMessage(project: Project, category: String): String =
            "Moved \"${project.title}\" out of $category"

        /** The message the Undo of [deleteCategory] sits beside. */
        fun deletedCategoryMessage(category: String): String = "Deleted category \"$category\""

        const val ENTER_A_NAME = "Enter a name"

        private const val CATEGORIES_UNAVAILABLE = "Categories are not available"

        private fun displayName(person: User): String = person.name.trim().ifEmpty { person.username }

        private const val SHARING_UNAVAILABLE = "Sharing is not available"
    }
}

private fun NetworkResult<*>.toResult(success: String?): ProjectActionResult = when (this) {
    is NetworkResult.Error -> ProjectActionResult.Failed(message)
    else -> ProjectActionResult.Done(success)
}
