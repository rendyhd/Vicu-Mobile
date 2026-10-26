package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Project
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.floor

/** A category of the project list (docs/cross-app-semantics-v1.md, section 13). */
data class ProjectCategory(val id: String, val name: String, val order: Double)

/** One group of the project list: the top-level [items] of [category], or of no category (null). */
data class ProjectGroup<T>(val category: ProjectCategory?, val items: List<T>)

/**
 * The categories as the synced preferences hold them: the usable ones in display order, and the
 * category of every project whose entry names one of them. Empty when the user has none, and the
 * project list is then exactly as it is without categories.
 */
data class ProjectCategoryState(
    val categories: List<ProjectCategory> = emptyList(),
    val assignments: Map<Long, String> = emptyMap(),
) {
    private val byId = categories.associateBy { it.id }

    /** The category [projectId] is in; only a top-level project's counts (section 13.2). */
    fun categoryOf(projectId: Long): ProjectCategory? = assignments[projectId]?.let { byId[it] }

    fun category(id: String): ProjectCategory? = byId[id]

    /** This state with [projectId] in [categoryId] (null: none): a drop on show before it is stored. */
    fun withAssignment(projectId: Long, categoryId: String?): ProjectCategoryState =
        copy(assignments = if (categoryId != null && categoryId in byId) assignments + (projectId to categoryId) else assignments - projectId)

    /**
     * [topLevel] (top-level projects, or their tree nodes, in position order) in groups: the ones
     * without a category first, then one group per category in display order, empty ones too.
     */
    fun <T> group(topLevel: List<T>, idOf: (T) -> Long): List<ProjectGroup<T>> {
        val members = topLevel.groupBy { categoryOf(idOf(it))?.id }
        return listOf(ProjectGroup(null, members[null].orEmpty())) +
            categories.map { ProjectGroup(it, members[it.id].orEmpty()) }
    }

    companion object {
        val EMPTY = ProjectCategoryState()
    }
}

/**
 * The `category.<id>` and `category.project.<id>` entries of the synced preferences
 * (docs/cross-app-semantics-v1.md, section 13): reading them, laying out the project list, and
 * planning each change as the entries to write (null resets a key). The desktop's
 * `src/shared/project-categories.ts` follows the same rules, and both run the fixture's
 * `projectCategories` vectors.
 */
object ProjectCategories {
    /** The gap between the orders apps give categories, the same as between project positions. */
    const val ORDER_STEP = 65_536.0

    /** Two neighbours closer than this leave no room between them: every category is renumbered. */
    private const val MIN_ORDER_GAP = 1.0

    private const val CATEGORY_PREFIX = "category."
    private const val PROJECT_PREFIX = "category.project."
    private const val RESERVED_ID = "project"
    private val CATEGORY_ID = Regex("[A-Za-z0-9_-]{1,64}")

    /** Order, then name lowercased, then id; strings compare by UTF-16 code units. */
    private val DISPLAY_ORDER = compareBy<ProjectCategory>({ it.order }, { it.name.lowercase() }, { it.id })

    fun categoryKey(categoryId: String): String = "$CATEGORY_PREFIX$categoryId"

    fun projectKey(projectId: Long): String = "$PROJECT_PREFIX$projectId"

    fun isCategoryId(id: String): Boolean = CATEGORY_ID.matches(id) && id != RESERVED_ID

    /** The category of a `category.<id>` key, or null for any other key. */
    fun categoryIdOf(key: String): String? =
        key.takeIf { it.startsWith(CATEGORY_PREFIX) }?.removePrefix(CATEGORY_PREFIX)?.takeIf(::isCategoryId)

    /** The project of a `category.project.<id>` key (a server id, above 0), or null. */
    fun projectIdOf(key: String): Long? =
        key.takeIf { it.startsWith(PROJECT_PREFIX) }?.removePrefix(PROJECT_PREFIX)?.toLongOrNull()?.takeIf { it > 0 }

    /** The category a `category.<id>` value holds, or null when it has not the shape of section 13.1. */
    fun category(id: String, value: JsonElement?): ProjectCategory? {
        val obj = value as? JsonObject ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null
        val order = (obj["order"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
        return ProjectCategory(id, name, order)
    }

    /** The category id a `category.project.<id>` value names, or null. */
    fun assignment(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(::isCategoryId)

    fun state(entries: Map<String, JsonElement?>): ProjectCategoryState {
        val categories = entries.mapNotNull { (key, value) -> categoryIdOf(key)?.let { category(it, value) } }
            .sortedWith(DISPLAY_ORDER)
        val usable = categories.mapTo(HashSet()) { it.id }
        val assignments = buildMap {
            for ((key, value) in entries) {
                val projectId = projectIdOf(key) ?: continue
                assignment(value)?.takeIf { it in usable }?.let { put(projectId, it) }
            }
        }
        return ProjectCategoryState(categories, assignments)
    }

    /**
     * The value an app uses for [key] in [entries], as the fixture's `projectCategories.read`
     * vectors give it: the category's name and order, the id an assignment names, or null for none.
     */
    fun read(entries: Map<String, JsonElement?>, key: String): JsonElement {
        projectIdOf(key)?.let { return assignment(entries[key])?.let(::JsonPrimitive) ?: JsonNull }
        val id = categoryIdOf(key) ?: return JsonNull
        val category = category(id, entries[key]) ?: return JsonNull
        return JsonObject(mapOf("name" to JsonPrimitive(category.name), "order" to orderValue(category.order)))
    }

    /**
     * The groups of the project list (section 13.2 and 13.3) as top-level project ids. The drawer
     * and Settings group the roots of their own trees with [ProjectCategoryState.group] instead.
     */
    fun layout(projects: List<Project>, inboxProjectId: Long?, entries: Map<String, JsonElement?>): List<ProjectGroup<Long>> =
        layout(projects, inboxProjectId, state(entries))

    fun layout(projects: List<Project>, inboxProjectId: Long?, state: ProjectCategoryState): List<ProjectGroup<Long>> {
        val listed = projects.filter { !it.isArchived && it.id != inboxProjectId }
        val ids = listed.mapTo(HashSet()) { it.id }
        val topLevel = listed
            .filter { it.parentProjectId == 0L || it.parentProjectId !in ids }
            .sortedWith(compareBy({ it.position }, { it.id }))
            .map { it.id }
        return state.group(topLevel) { it }
    }

    /**
     * Whether [project] is top-level in the project list (section 13.2), so it can be in a category:
     * listed (not archived, not the Inbox), with no parent or one that is not listed.
     */
    fun isTopLevel(project: Project, projects: List<Project>, inboxProjectId: Long?): Boolean {
        if (project.isArchived || project.id == inboxProjectId) return false
        if (project.parentProjectId == 0L) return true
        val parent = projects.firstOrNull { it.id == project.parentProjectId }
        return parent == null || parent.isArchived || parent.id == inboxProjectId
    }

    /** A new category [id] named [name], last. Nothing for a blank name or an id that is not one. */
    fun planNew(entries: Map<String, JsonElement?>, id: String, name: String): Map<String, JsonElement?> {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !isCategoryId(id)) return emptyMap()
        val highest = state(entries).categories.maxOfOrNull { it.order }
        val order = if (highest == null) ORDER_STEP else highest + ORDER_STEP
        return mapOf(categoryKey(id) to categoryValue(trimmed, order))
    }

    /** [categoryId] moved to index [to] of the new display order. Nothing when it stays where it is. */
    fun planMove(entries: Map<String, JsonElement?>, categoryId: String, to: Int): Map<String, JsonElement?> {
        val categories = state(entries).categories
        val current = categories.map { it.id }
        if (categoryId !in current) return emptyMap()
        val order = current.filter { it != categoryId }.toMutableList()
        order.add(to.coerceIn(0, order.size), categoryId)
        if (order == current) return emptyMap()
        return placed(entries, categories.associateBy { it.id }, order, categoryId, newName = null)
    }

    /** [categoryId] with the new [name], trimmed; its order and other fields stay. */
    fun planRename(entries: Map<String, JsonElement?>, categoryId: String, name: String): Map<String, JsonElement?> {
        val trimmed = name.trim()
        val raw = entries[categoryKey(categoryId)] as? JsonObject ?: return emptyMap()
        val current = category(categoryId, raw) ?: return emptyMap()
        if (trimmed.isEmpty() || trimmed == current.name) return emptyMap()
        return mapOf(categoryKey(categoryId) to JsonObject(raw + ("name" to JsonPrimitive(trimmed))))
    }

    /** [categoryId] deleted, with every assignment that names it, whatever project that is. */
    fun planDelete(entries: Map<String, JsonElement?>, categoryId: String): Map<String, JsonElement?> = buildMap {
        put(categoryKey(categoryId), null)
        for ((key, value) in entries) {
            if (projectIdOf(key) == null) continue
            val named = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (named == categoryId) put(key, null)
        }
    }

    /** [projectId] moved to [categoryId] (null: no category) from a menu. Nothing when it is already there. */
    fun planAssign(entries: Map<String, JsonElement?>, projectId: Long, categoryId: String?): Map<String, JsonElement?> {
        val state = state(entries)
        if (categoryId != null && state.category(categoryId) == null) return emptyMap()
        if (state.categoryOf(projectId)?.id == categoryId) return emptyMap()
        return mapOf(projectKey(projectId) to categoryId?.let(::JsonPrimitive))
    }

    /**
     * Whether "Add category above" is offered on [projectId], a top-level project of [groups]: not on
     * the first project of a category, which would only leave that category empty.
     */
    fun canSplitAt(groups: List<ProjectGroup<Long>>, projectId: Long): Boolean {
        val group = groups.firstOrNull { projectId in it.items } ?: return false
        return group.category == null || group.items.first() != projectId
    }

    /**
     * "Add category above" [projectId]: a new category [id] named [name] right after the project's
     * own category (before every category when it has none), holding the project and every project
     * after it in its group. [groups] is the layout as the user sees it.
     */
    fun planSplit(
        entries: Map<String, JsonElement?>,
        groups: List<ProjectGroup<Long>>,
        projectId: Long,
        id: String,
        name: String,
    ): Map<String, JsonElement?> {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !isCategoryId(id) || !canSplitAt(groups, projectId)) return emptyMap()
        val group = groups.first { projectId in it.items }
        val categories = state(entries).categories
        val order = categories.map { it.id }.toMutableList()
        order.add(group.category?.let { order.indexOf(it.id) + 1 } ?: 0, id)
        val moved = group.items.drop(group.items.indexOf(projectId))
        return placed(entries, categories.associateBy { it.id }, order, id, newName = trimmed) +
            moved.associate { projectKey(it) to JsonPrimitive(id) }
    }

    /**
     * The writes that give [movedId] its place in [order] (the new display order): between its
     * neighbours, or a renumbering of every category when they leave no room. With [newName] the
     * moved category is a new one; the others keep every field but the order.
     */
    private fun placed(
        entries: Map<String, JsonElement?>,
        existing: Map<String, ProjectCategory>,
        order: List<String>,
        movedId: String,
        newName: String?,
    ): Map<String, JsonElement?> {
        val index = order.indexOf(movedId)
        val previous = order.getOrNull(index - 1)?.let { existing.getValue(it).order }
        val next = order.getOrNull(index + 1)?.let { existing.getValue(it).order }
        val single = when {
            previous == null -> if (next == null) ORDER_STEP else next - ORDER_STEP
            next == null -> previous + ORDER_STEP
            next - previous >= MIN_ORDER_GAP -> (previous + next) / 2
            else -> null
        }
        val orders = if (single != null) {
            mapOf(movedId to single)
        } else {
            order.withIndex().associate { (i, id) -> id to (i + 1) * ORDER_STEP }
        }
        return buildMap {
            for ((id, newOrder) in orders) {
                if (id == movedId && newName != null) {
                    put(categoryKey(id), categoryValue(newName, newOrder))
                } else if (existing.getValue(id).order != newOrder) {
                    val raw = entries[categoryKey(id)] as? JsonObject ?: continue
                    put(categoryKey(id), JsonObject(raw + ("order" to orderValue(newOrder))))
                }
            }
        }
    }

    private fun categoryValue(name: String, order: Double): JsonObject =
        JsonObject(mapOf("name" to JsonPrimitive(name), "order" to orderValue(order)))

    /** A whole order is written as a whole number (`65536`, as the desktop writes it), any other as it is. */
    private fun orderValue(order: Double): JsonPrimitive =
        if (order == floor(order) && abs(order) < 9.0E15) JsonPrimitive(order.toLong()) else JsonPrimitive(order)
}
