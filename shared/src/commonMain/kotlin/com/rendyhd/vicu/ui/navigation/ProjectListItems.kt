package com.rendyhd.vicu.ui.navigation

import com.rendyhd.vicu.util.ProjectCategory
import com.rendyhd.vicu.util.ProjectCategoryState

/**
 * One item of a project list that can have category headers (docs/cross-app-semantics-v1.md,
 * section 13): the drawer's and the Projects tab of Settings.
 */
sealed interface ProjectListItem {
    /** A category's header. [count] is how many top-level projects it holds. */
    data class Header(val category: ProjectCategory, val count: Int, val collapsed: Boolean) : ProjectListItem

    /** A project's row, in the group of [categoryId] (null: the projects without a category). */
    data class Row(val row: ProjectRow, val categoryId: String?) : ProjectListItem
}

/**
 * The items of a project list: the top-level projects of [tree] without a category first, with no
 * header, then each category's header and, unless it is in [collapsedCategories], its projects.
 * Every project brings the rows below it, as [visibleProjectRows] shows them. Without categories
 * these are the rows of [visibleProjectRows], in the same order.
 */
fun projectListItems(
    tree: List<ProjectNode>,
    collapsedProjects: Set<Long>,
    categories: ProjectCategoryState,
    collapsedCategories: Set<String> = emptySet(),
): List<ProjectListItem> = buildList {
    for (group in categories.group(tree) { it.project.id }) {
        val category = group.category
        if (category != null) {
            val collapsed = category.id in collapsedCategories
            add(ProjectListItem.Header(category, group.items.size, collapsed))
            if (collapsed) continue
        }
        visibleProjectRows(group.items, collapsedProjects).forEach { add(ProjectListItem.Row(it, category?.id)) }
    }
}

/**
 * The top-level projects of [tree] in the group [projectId] is in, in display order; empty when it
 * is not a top-level project of [tree].
 */
fun topLevelGroupOf(tree: List<ProjectNode>, categories: ProjectCategoryState, projectId: Long): List<Long> =
    categories.group(tree) { it.project.id }
        .firstOrNull { group -> group.items.any { it.project.id == projectId } }
        ?.items?.map { it.project.id }
        .orEmpty()

/**
 * [items] with the rows below [projectId] left out and its own row shown closed: how a project is
 * carried while it is dragged, one row that the rows of other groups can pass. The rows come back
 * below it from the stored list after the drop.
 */
fun List<ProjectListItem>.withoutDescendants(projectId: Long): List<ProjectListItem> {
    val index = indexOfProject(projectId)
    if (index < 0) return this
    val row = this[index] as ProjectListItem.Row
    val end = blockEnd(this, index)
    if (end == index + 1) return this
    return subList(0, index) + row.copy(row = row.row.copy(expanded = false)) + subList(end, size)
}

/** The project rows of [items], in order. */
fun List<ProjectListItem>.projectRows(): List<ProjectRow> = mapNotNull { (it as? ProjectListItem.Row)?.row }

fun List<ProjectListItem>.indexOfProject(projectId: Long): Int =
    indexOfFirst { it is ProjectListItem.Row && it.row.project.id == projectId }

fun List<ProjectListItem>.indexOfCategory(categoryId: String): Int =
    indexOfFirst { it is ProjectListItem.Header && it.category.id == categoryId }

/**
 * The items with the project [movedId] (and the rows on show below it) dragged over the item at
 * [targetIndex], or null when that is no move.
 *
 * A subproject only moves among its siblings, as [moveProjectRow] has it. A top-level project moves
 * among the top-level projects of every group: over a header it goes past it, into the group below
 * (first) when it came from above and into the group above (last) when it came from below; over a
 * row it lands before that row's top-level project when it came from below and after it when it
 * came from above. The group a project lands in is the header above it, or none.
 */
fun moveProjectItem(items: List<ProjectListItem>, movedId: Long, targetIndex: Int): List<ProjectListItem>? {
    val movedIndex = items.indexOfProject(movedId)
    if (movedIndex < 0 || targetIndex !in items.indices || movedIndex == targetIndex) return null
    val moved = items[movedIndex] as ProjectListItem.Row

    if (moved.row.parentId != 0L) {
        val target = items[targetIndex] as? ProjectListItem.Row ?: return null
        val start = (movedIndex downTo 0).firstOrNull { items[it] is ProjectListItem.Header }?.plus(1) ?: 0
        val end = (movedIndex until items.size).firstOrNull { items[it] is ProjectListItem.Header } ?: items.size
        if (targetIndex !in start until end) return null
        val rows = items.subList(start, end).projectRows()
        val reordered = moveProjectRow(rows, movedId, target.row.project.id) ?: return null
        return items.subList(0, start) + reordered.map { ProjectListItem.Row(it, moved.categoryId) } + items.subList(end, items.size)
    }

    val movedEnd = blockEnd(items, movedIndex)
    if (targetIndex in movedIndex until movedEnd) return null
    val block = items.subList(movedIndex, movedEnd)
    val rest = items.subList(0, movedIndex) + items.subList(movedEnd, items.size)
    val insertAt = when (val target = items[targetIndex]) {
        is ProjectListItem.Header -> {
            val at = rest.indexOfFirst { it === target }
            if (targetIndex > movedIndex) at + 1 else at
        }
        is ProjectListItem.Row -> {
            // The top-level project the target row is in: itself, or the one above a subproject.
            var rootIndex = targetIndex
            while (rootIndex > 0 && (items[rootIndex] as? ProjectListItem.Row)?.row?.parentId != 0L) rootIndex--
            val root = items[rootIndex] as? ProjectListItem.Row ?: return null
            val at = rest.indexOfFirst { it === root }
            if (rootIndex < movedIndex) at else at + (blockEnd(items, rootIndex) - rootIndex)
        }
    }
    val categoryId = rest.subList(0, insertAt).lastOrNull { it is ProjectListItem.Header }
        ?.let { (it as ProjectListItem.Header).category.id }
    val placed = block.map { (it as ProjectListItem.Row).copy(categoryId = categoryId) }
    val result = rest.toMutableList().apply { addAll(insertAt, placed) }
    return result.takeUnless { it == items }
}

/**
 * Where a project dropped in [items] ended up: the category of its group (null: none) and the ids
 * of the projects of its own level there in display order (for a top-level project, the top-level
 * projects of its group). Null when it is not listed.
 */
fun droppedPlace(items: List<ProjectListItem>, projectId: Long): Pair<String?, List<Long>>? {
    val index = items.indexOfProject(projectId)
    if (index < 0) return null
    val moved = items[index] as ProjectListItem.Row
    val start = (index downTo 0).firstOrNull { items[it] is ProjectListItem.Header }?.plus(1) ?: 0
    val end = (index until items.size).firstOrNull { items[it] is ProjectListItem.Header } ?: items.size
    val level = items.subList(start, end).projectRows().filter { it.parentId == moved.row.parentId }.map { it.project.id }
    return moved.categoryId to level
}

/**
 * For every project row of [items], the ids of the rows of its level in its group, in display
 * order: what Move up and Move down move it by. A project never moves out of its category that way.
 */
fun levelSiblings(items: List<ProjectListItem>): Map<Long, List<Long>> {
    val result = HashMap<Long, List<Long>>()
    var group = ArrayList<ProjectRow>()
    fun close() {
        for (level in group.groupBy({ it.parentId }, { it.project.id }).values) level.forEach { result[it] = level }
        group = ArrayList()
    }
    for (item in items) {
        when (item) {
            is ProjectListItem.Header -> close()
            is ProjectListItem.Row -> group += item.row
        }
    }
    close()
    return result
}

/** The index after the last row of the block that starts at [start]: the row and the deeper rows after it. */
private fun blockEnd(items: List<ProjectListItem>, start: Int): Int {
    val depth = (items[start] as ProjectListItem.Row).row.depth
    var end = start + 1
    while (end < items.size && ((items[end] as? ProjectListItem.Row)?.row?.depth ?: -1) > depth) end++
    return end
}
