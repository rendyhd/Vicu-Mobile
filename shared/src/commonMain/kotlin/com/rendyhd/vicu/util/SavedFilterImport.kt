package com.rendyhd.vicu.util

import com.rendyhd.vicu.data.remote.api.ProjectViewDto
import com.rendyhd.vicu.data.remote.api.SavedFilterDto
import com.rendyhd.vicu.data.remote.api.SavedFilterQueryDto
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.ImportBase
import com.rendyhd.vicu.domain.model.ListSortKey
import com.rendyhd.vicu.domain.model.ProjectViewRef

/**
 * How a Vikunja saved filter or project view becomes a custom list, and how importing it again
 * compares and updates that list (docs/cross-app-semantics-v1.md, sections 3.7 and 3.9). Desktop
 * has the same in `src/shared/saved-filter-import.ts`; both run the `savedFilterImport` vectors.
 */
object SavedFilterImport {

    /** The pseudo-project id Vikunja gives saved filter [filterId], and back. */
    fun projectIdOf(filterId: Long): Long = -(filterId + 1)

    /** The saved filter a pseudo-project stands for, or null for a real project or Favorites (-1). */
    fun filterIdOf(projectId: Long): Long? = if (projectId <= -2) -projectId - 1 else null

    enum class Status { EXACT, CHANGED, UNREADABLE }

    /** Why an import is not exact, in the contract's order. */
    enum class Reason(val wire: String) {
        KANBAN_COLUMN("kanban_column"),
        OTHER_FIELD("other_field"),
        MANUAL_ORDER("manual_order"),
        SORT_KEY("sort_key"),
    }

    /** The list a source becomes ([list] is null when it is unreadable), and how it went. */
    data class Result(val list: CustomList?, val status: Status, val reasons: List<Reason>)

    /** The icon an imported list gets. */
    const val ICON = "filter_list"

    /** [filter] as a custom list with the id [listId]. */
    fun convert(filter: SavedFilterDto, listId: String): Result {
        val result = convertQuery(filter.filters, filter.title.trim().ifEmpty { "Saved filter ${filter.id}" }, filter.updated, listId)
        return result.copy(list = result.list?.copy(savedFilterId = filter.id))
    }

    /** The project a view belongs to, as section 3.9 reads it. */
    data class ViewProject(val id: Long, val title: String, val archived: Boolean)

    /** Whether the review sheet offers [view] of [project] (section 3.9). */
    fun isOffered(project: ViewProject, view: ProjectViewDto): Boolean {
        if (project.id < 1 || project.archived) return false
        val query = view.filter ?: SavedFilterQueryDto()
        val plain = query.s.isEmpty() && query.sortBy.isNullOrEmpty() && !query.filterIncludeNulls
        return when (view.viewKind) {
            "list" -> !(plain && query.filter.trim() == "done = false")
            "table", "gantt" -> !(plain && query.filter.isEmpty())
            else -> false
        }
    }

    /** [view] of [project] as a custom list with the id [listId] (section 3.9). */
    fun convertView(project: ViewProject, view: ProjectViewDto, listId: String): Result {
        val name = "${project.title.trim()}: ${view.title.trim()}"
        val result = convertQuery(view.filter ?: SavedFilterQueryDto(), name, view.updated, listId)
        val list = result.list ?: return result
        return result.copy(
            list = list.copy(
                filter = list.filter.copy(projectIds = listOf(project.id), projectFilterMode = "include", addToProjectId = project.id),
                projectView = ProjectViewRef(project.id, view.id),
            ),
        )
    }

    private fun convertQuery(query: SavedFilterQueryDto, name: String, updated: String, listId: String): Result {
        val reasons = mutableSetOf<Reason>()
        val text = query.filter.trim().ifEmpty {
            query.s.takeIf { it.isNotBlank() }?.let { search ->
                val value = CustomListRule.writeValue(search, list = false)
                "title like $value || description like $value"
            } ?: ""
        }

        var rule = text
        when (val parsed = CustomListRule.parse(text)) {
            is CustomListRule.Parsed.Error -> return Result(null, Status.UNREADABLE, emptyList())
            is CustomListRule.Parsed.Ok -> {
                CustomListRule.withoutTerms(parsed.root, CustomListRule.SERVER_ONLY_FIELDS)?.let { (root, removed) ->
                    if ("bucket_id" in removed) reasons += Reason.KANBAN_COLUMN
                    if ("position" in removed || "uid" in removed) reasons += Reason.OTHER_FIELD
                    rule = CustomListRule.write(root)
                }
            }
        }
        if (CustomListRule.validate(rule) != null) return Result(null, Status.UNREADABLE, emptyList())

        val keys = mutableListOf<ListSortKey>()
        query.sortBy.orEmpty().forEachIndexed { index, key ->
            val order = query.orderBy?.getOrNull(index)?.takeIf { it == "desc" } ?: "asc"
            when {
                key == "position" -> reasons += Reason.MANUAL_ORDER
                key !in CustomListFilterBuilder.SORT_KEYS -> reasons += Reason.SORT_KEY
                else -> keys += ListSortKey(key, order)
            }
        }
        val first = keys.firstOrNull() ?: ListSortKey("due_date", "asc")
        val ruleText = rule.takeIf { it.isNotBlank() }

        val list = CustomList(
            id = listId,
            name = name,
            icon = ICON,
            filter = CustomListFilter(
                includeDone = true,
                sortBy = first.sortBy,
                orderBy = first.orderBy,
                thenBy = keys.drop(1),
                rule = ruleText,
                ruleIncludeNulls = query.filterIncludeNulls,
            ),
            importBase = ImportBase(
                updated = updated,
                name = name,
                rule = ruleText,
                ruleIncludeNulls = query.filterIncludeNulls,
                sortBy = first.sortBy,
                orderBy = first.orderBy,
                thenBy = keys.drop(1),
            ),
        )
        val ordered = Reason.entries.filter { it in reasons }
        return Result(list, if (ordered.isEmpty()) Status.EXACT else Status.CHANGED, ordered)
    }

    // ---- Importing again (section 3.7) ----

    /** The four parts an import takes from Vikunja. */
    enum class Part(val wire: String) { NAME("name"), RULE("rule"), INCLUDE_NULLS("include_nulls"), SORT("sort") }

    /** Who changed a part, or a whole source, since the import. */
    enum class Change(val wire: String) { SAME("same"), VICU("vicu"), VIKUNJA("vikunja"), BOTH("both"), DIFFERS("differs") }

    /** A list compared with a new import of its source. */
    data class Comparison(val parts: Map<Part, Change>, val status: Change) {
        /** The parts that are not [Change.SAME], in the contract's order. */
        val changed: List<Part> get() = Part.entries.filter { parts[it] != Change.SAME }
    }

    private data class Sort(val sortBy: String, val orderBy: String, val thenBy: List<ListSortKey>)

    private fun order(value: String) = if (value == "desc") "desc" else "asc"

    private fun sortOf(sortBy: String, orderBy: String, thenBy: List<ListSortKey>) =
        Sort(sortBy, order(orderBy), thenBy.map { ListSortKey(it.sortBy, order(it.orderBy)) })

    /** A part's value on a list. */
    fun valueOf(list: CustomList, part: Part): Any? = when (part) {
        Part.NAME -> list.name.trim()
        Part.RULE -> list.filter.ruleText
        Part.INCLUDE_NULLS -> list.filter.ruleIncludeNulls
        Part.SORT -> sortOf(list.filter.sortBy, list.filter.orderBy, list.filter.thenBy)
    }

    private fun valueOf(base: ImportBase, part: Part): Any? = when (part) {
        Part.NAME -> base.name.trim()
        Part.RULE -> base.rule?.takeIf { it.isNotBlank() }
        Part.INCLUDE_NULLS -> base.ruleIncludeNulls
        Part.SORT -> sortOf(base.sortBy, base.orderBy, base.thenBy)
    }

    /** Compares [list] with [fresh], a new import of its source. */
    fun compare(list: CustomList, fresh: CustomList): Comparison {
        val base = list.importBase
        val parts = Part.entries.associateWith { part ->
            val mine = valueOf(list, part)
            val theirs = valueOf(fresh, part)
            when {
                mine == theirs -> Change.SAME
                base == null -> Change.DIFFERS
                theirs == valueOf(base, part) -> Change.VICU
                mine == valueOf(base, part) -> Change.VIKUNJA
                else -> Change.BOTH
            }
        }
        val states = parts.values
        val status = when {
            states.all { it == Change.SAME } -> Change.SAME
            Change.DIFFERS in states -> Change.DIFFERS
            Change.BOTH in states || (Change.VIKUNJA in states && Change.VICU in states) -> Change.BOTH
            Change.VIKUNJA in states -> Change.VIKUNJA
            else -> Change.VICU
        }
        return Comparison(parts, status)
    }

    /** Overwrite: the four parts and the base from [fresh], everything else of [list] kept. */
    fun overwrite(list: CustomList, fresh: CustomList): CustomList = list.copy(
        name = fresh.name,
        filter = list.filter.copy(
            rule = fresh.filter.ruleText,
            ruleIncludeNulls = fresh.filter.ruleIncludeNulls,
            sortBy = fresh.filter.sortBy,
            orderBy = fresh.filter.orderBy,
            thenBy = fresh.filter.thenBy,
        ),
        importBase = fresh.importBase,
    )

    /** Skip: [list] as it is, with the base of [fresh], so the same change is not offered again. */
    fun skip(list: CustomList, fresh: CustomList): CustomList = list.copy(importBase = fresh.importBase)

    /** Whether the review sheet ticks a source with this status at first; [Change.SAME] cannot be ticked. */
    fun startsTicked(status: Change): Boolean = status == Change.VIKUNJA || status == Change.BOTH || status == Change.DIFFERS
}
