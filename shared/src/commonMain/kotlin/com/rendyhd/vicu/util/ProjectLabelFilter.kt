package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task

/** One label the project page's filter bar offers, and how many of the page's tasks carry it. */
data class LabelChoice(val label: Label, val count: Int)

/**
 * The label filter of a project page (`docs/cross-app-semantics-v1.md`, section 17.2). It lasts while
 * the page is open: nothing here is saved or synced. The desktop implements the same rules
 * (`src/shared/project-label-filter.ts`) and both run `projectLabelFilter` from
 * `test-fixtures/cross-app-semantics-v1.json`.
 */
object ProjectLabelFilter {

    /** By title in lowercase, compared by UTF-16 code units (not the locale), then by id. */
    private val order = Comparator<Label> { a, b ->
        val byTitle = a.title.lowercase().compareTo(b.title.lowercase())
        if (byTitle != 0) byTitle else a.id.compareTo(b.id)
    }

    /**
     * The labels the bar offers: those of [tasks] (the page's own and its sections', as listed), each
     * with how many tasks carry it, plus every [picked] label no task carries any more, with 0. A
     * picked label is looked up in [known] (every label of the account); one not found is left out.
     */
    fun choices(tasks: List<Task>, picked: Set<Long>, known: List<Label> = emptyList()): List<LabelChoice> {
        val labels = LinkedHashMap<Long, Label>()
        val counts = HashMap<Long, Int>()
        for (task in tasks) {
            for (label in task.labels.distinctBy { it.id }) {
                labels.getOrPut(label.id) { label }
                counts[label.id] = (counts[label.id] ?: 0) + 1
            }
        }
        for (id in picked) {
            if (id in labels) continue
            known.firstOrNull { it.id == id }?.let { labels[id] = it }
        }
        return labels.values.sortedWith(order).map { LabelChoice(it, counts[it.id] ?: 0) }
    }

    /**
     * The tasks of a page the [picked] labels show, in the page's order. [parentOf] names the task a
     * row is nested under on the page: a row under a shown row is shown with it, whatever it
     * carries. With nothing picked every task shows.
     */
    fun shown(tasks: List<Task>, picked: Set<Long>, parentOf: ((Task) -> Long?)? = null): List<Task> {
        if (picked.isEmpty()) return tasks
        val byId = tasks.associateBy { it.id }
        val decided = HashMap<Long, Boolean>()
        fun isShown(task: Task, trail: MutableSet<Long>): Boolean {
            decided[task.id]?.let { return it }
            var result = task.labels.any { it.id in picked }
            if (!result && parentOf != null && trail.add(task.id)) {
                val parent = parentOf(task)?.let { byId[it] }
                result = parent != null && isShown(parent, trail)
            }
            decided[task.id] = result
            return result
        }
        return tasks.filter { isShown(it, mutableSetOf()) }
    }
}
