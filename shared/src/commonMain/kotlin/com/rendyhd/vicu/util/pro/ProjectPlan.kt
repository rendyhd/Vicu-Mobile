package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A 90-day plan as stored in `project_plan.<projectId>` (section 21.20). Fields this version does
 * not know are kept in [unknown] and written back as they were.
 */
data class ProjectPlan(
    val start: LocalDate,
    val end: LocalDate,
    /** The group it belongs to (its label id), or null for a plan of your own. */
    val group: Long? = null,
    /** The day it was closed, or null while it runs. */
    val closed: LocalDate? = null,
    /** The project of the plan it carries on from, or null. */
    val from: Long? = null,
    val unknown: JsonObject = JsonObject(emptyMap()),
)

data class PlanEntry(val project: Long, val plan: ProjectPlan)

data class PlanWeek(val from: LocalDate, val to: LocalDate)

/** What happened in one week of one item: counts of facts, never a status. */
data class PlanCell(
    /** Milestones whose date falls in the week. */
    val due: Int = 0,
    /** Milestones delivered in the week (late ones included). */
    val delivered: Int = 0,
    /** Of those, delivered late. */
    val late: Int = 0,
    /** Dates that moved in the week: "not yet" and "new date" verdicts. */
    val slipped: Int = 0,
    /** Milestones dropped in the week. */
    val dropped: Int = 0,
) {
    val isEmpty: Boolean get() = due == 0 && delivered == 0 && late == 0 && slipped == 0 && dropped == 0
}

data class PlanItem(
    val id: Long,
    /** The item's owner: its own promise's, else the one all its milestones share, else nobody. */
    val owner: Long?,
    /** The item's task is done. */
    val done: Boolean,
    /** Milestones that count: every one that was not dropped. */
    val total: Int,
    val delivered: Int,
    val dropped: Int,
    /** Open milestones whose date is before today. */
    val overdue: Int,
    /** The open milestone that is due first (none last, then id), or null. */
    val next: Long?,
    /** The item's milestones, in due order (none last), then id. */
    val milestones: List<Long>,
    /** One cell per week of the plan. */
    val weeks: List<PlanCell>,
)

data class PlanView(
    val weeks: List<PlanWeek>,
    /** The week today falls in (0 for the first), or null before the plan starts or after it ends. */
    val week: Int?,
    val items: List<PlanItem>,
    val total: Int,
    val delivered: Int,
    val overdue: Int,
)

data class PlanCarryOver(val items: List<Long>, val milestones: List<Long>)

/**
 * 90-day plans (docs/cross-app-semantics-v1.md section 21.20), the same as the desktop's
 * `src/shared/pro/project-plan.ts`: a project with a start and an end day. Its top-level tasks are
 * the plan's items and their subtasks the milestones, which are promises like any other. What the
 * plan view shows is worked out from the tasks and the verdicts given on them; nothing about a
 * plan's progress is stored, and no status is ever computed. Both apps run the `pro.plans` vectors.
 */
object ProjectPlans {
    const val PLAN_DEFAULT_WEEKS = 13

    /** A plan longer than this is read as it is but shown with this many weeks at most. */
    const val PLAN_MAX_WEEKS = 53

    private val LOCAL_DAY = Regex("^\\d{4}-\\d{2}-\\d{2}\$")
    private val KNOWN = setOf("start", "end", "group", "closed", "from")

    private fun dayOf(value: JsonElement?): LocalDate? {
        val text = value.asText() ?: return null
        if (!LOCAL_DAY.matches(text)) return null
        return runCatching { LocalDate.parse(text) }.getOrNull()
    }

    private fun wholeAboveZero(value: JsonElement?): Long? = value.asWhole()?.takeIf { it > 0 }

    /** A plan from its stored value, or null when it is not one: both days are needed, in order. */
    fun readPlan(value: JsonElement?): ProjectPlan? {
        val raw = value.asObject() ?: return null
        val start = dayOf(raw["start"]) ?: return null
        val end = dayOf(raw["end"]) ?: return null
        if (end < start) return null
        return ProjectPlan(
            start = start,
            end = end,
            group = wholeAboveZero(raw["group"]),
            closed = dayOf(raw["closed"]),
            from = wholeAboveZero(raw["from"]),
            unknown = JsonObject(raw.filterKeys { it !in KNOWN }),
        )
    }

    /** Every field of a plan, the unset ones as null (the shape [readPlan] gives on the desktop). */
    fun planFields(plan: ProjectPlan): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>(plan.unknown)
        fields["start"] = JsonPrimitive(plan.start.toString())
        fields["end"] = JsonPrimitive(plan.end.toString())
        fields["group"] = plan.group?.let(::JsonPrimitive) ?: JsonNull
        fields["closed"] = plan.closed?.let { JsonPrimitive(it.toString()) } ?: JsonNull
        fields["from"] = plan.from?.let(::JsonPrimitive) ?: JsonNull
        return JsonObject(fields)
    }

    /** The value to store: a field that is not set is left out, unknown fields stay. */
    fun planValue(plan: ProjectPlan): JsonObject = JsonObject(planFields(plan).filterValues { it !is JsonNull })

    /** The last day of a plan of the default length that starts on [start]. */
    fun defaultPlanEnd(start: LocalDate): LocalDate = start.plus(PLAN_DEFAULT_WEEKS * 7 - 1, DateTimeUnit.DAY)

    /** The plans in the synced values, by project id: open ones first by end day, then closed ones, newest first. */
    fun plansOf(values: Map<String, JsonElement?>): List<PlanEntry> {
        val list = mutableListOf<PlanEntry>()
        for ((key, value) in values) {
            val project = ProPrefs.idOfKey(key, ProPrefs.PROJECT_PLAN_PREFIX) ?: continue
            val plan = readPlan(value) ?: continue
            list.add(PlanEntry(project, plan))
        }
        return list.sortedWith { a, b ->
            val left = a.plan.closed
            val right = b.plan.closed
            when {
                (left != null) != (right != null) -> if (left != null) 1 else -1
                left == null && a.plan.end != b.plan.end -> a.plan.end.compareTo(b.plan.end)
                left != null && right != null && left != right -> right.compareTo(left)
                else -> a.project.compareTo(b.project)
            }
        }
    }

    /** The plan of one project, or null when it has none. */
    fun planOf(values: Map<String, JsonElement?>, projectId: Long): ProjectPlan? =
        readPlan(values[ProPrefs.projectPlanKey(projectId)])

    /** The plan's weeks: seven days each from its first day, the last one ending on its last day. */
    fun planWeeks(start: LocalDate, end: LocalDate): List<PlanWeek> {
        val weeks = mutableListOf<PlanWeek>()
        var from = start
        while (from <= end && weeks.size < PLAN_MAX_WEEKS) {
            val to = from.plus(6, DateTimeUnit.DAY)
            weeks.add(PlanWeek(from, if (to > end || weeks.size == PLAN_MAX_WEEKS - 1) end else to))
            from = from.plus(7, DateTimeUnit.DAY)
        }
        return weeks
    }

    fun planWeeks(plan: ProjectPlan): List<PlanWeek> = planWeeks(plan.start, plan.end)

    /** The week a day falls in (0 for the first), or null outside the plan. */
    fun planWeekOf(start: LocalDate, end: LocalDate, day: LocalDate): Int? {
        if (day < start || day > end) return null
        return minOf(start.daysUntil(day) / 7, PLAN_MAX_WEEKS - 1)
    }

    fun planWeekOf(plan: ProjectPlan, day: LocalDate): Int? = planWeekOf(plan.start, plan.end, day)

    /** The task a task is a subtask of, or null for a top-level task. */
    fun parentOf(task: Task): Long? = task.relatedTasks["parenttask"]?.firstOrNull()?.id?.takeIf { it > 0 }

    /**
     * The ids of the plan's items: the tasks that are no subtask of another task of the plan. A
     * subtask whose parent is elsewhere (an item that moved on to the next plan) is an item of its
     * own, so what was delivered here stays in view.
     */
    fun planItemIds(tasks: List<Task>, parentOf: (Task) -> Long? = ::parentOf): Set<Long> {
        val ids = tasks.map { it.id }.toSet()
        return tasks.filter { task -> parentOf(task).let { it == null || it !in ids } }.map { it.id }.toSet()
    }

    private class Row(val id: Long, val due: LocalDate?, val open: Boolean)

    /**
     * The plan view (section 21.20): one row per item, one cell per week. A milestone is a subtask
     * of an item; an item without subtasks is its own one milestone. A milestone is delivered by a
     * final verdict of delivered, late or done by you, or by its task being done; a dropped one
     * leaves the count. [tasks] are the project's tasks, open and done, in the order the items are
     * shown in.
     */
    fun planView(
        start: LocalDate,
        end: LocalDate,
        tasks: List<Task>,
        ledger: ProLedger?,
        today: LocalDate,
        zone: TimeZone,
        parentOf: (Task) -> Long? = ::parentOf,
    ): PlanView {
        val weeks = planWeeks(start, end)
        val itemIds = planItemIds(tasks, parentOf)
        val children = LinkedHashMap<Long, MutableList<Task>>()
        for (task in tasks) {
            // A milestone is a direct subtask of an item; what hangs under a milestone is its own detail.
            val parent = parentOf(task) ?: continue
            if (task.id !in itemIds && parent in itemIds) children.getOrPut(parent) { mutableListOf() }.add(task)
        }
        val items = mutableListOf<PlanItem>()
        for (task in tasks) {
            if (task.id !in itemIds) continue
            val own = children[task.id].orEmpty()
            val milestones = own.ifEmpty { listOf(task) }
            val cells = MutableList(weeks.size) { PlanCell() }
            fun change(day: LocalDate?, update: (PlanCell) -> PlanCell) {
                val index = day?.let { planWeekOf(start, end, it) } ?: return
                cells[index] = update(cells[index])
            }
            val rows = mutableListOf<Row>()
            val owners = LinkedHashSet<Long?>()
            var delivered = 0
            var dropped = 0
            var overdue = 0
            for (milestone in milestones) {
                val promise = Promises.promiseOf(ledger, milestone.id)
                if (own.isNotEmpty() && promise != null) owners.add(Groups.ownerOf(promise))
                val final = LedgerRules.finalVerdictOf(promise)
                val due = Promises.effectiveDue(milestone, promise, zone)
                for (verdict in LedgerRules.activeVerdicts(promise)) {
                    val kind = verdict.text("kind")
                    if (kind != "not_yet" && kind != "renegotiated") continue
                    change(localDayOf(verdict.text("at"), zone)) { it.copy(slipped = it.slipped + 1) }
                }
                if (final?.text("kind") == "dropped") {
                    dropped++
                    change(localDayOf(final.text("at"), zone)) { it.copy(dropped = it.dropped + 1) }
                    rows.add(Row(milestone.id, due, open = false))
                    continue
                }
                change(due) { it.copy(due = it.due + 1) }
                val finalKind = final?.text("kind")
                val isDelivered = if (final != null) {
                    finalKind == "delivered" || finalKind == "late" || finalKind == "taken_over"
                } else {
                    milestone.done
                }
                if (isDelivered) {
                    delivered++
                    val day = if (final != null) localDayOf(final.text("at"), zone) else localDayOf(milestone.doneAt, zone)
                    change(day) { it.copy(delivered = it.delivered + 1, late = it.late + if (finalKind == "late") 1 else 0) }
                } else if (due != null && due < today) {
                    overdue++
                }
                rows.add(Row(milestone.id, due, open = !isDelivered))
            }
            rows.sortWith { a, b ->
                val due = Groups.BY_DUE.compare(a.due, b.due)
                if (due != 0) due else a.id.compareTo(b.id)
            }
            val ownPromise = Promises.promiseOf(ledger, task.id)
            val shared = if (owners.size == 1) owners.first() else null
            items.add(
                PlanItem(
                    id = task.id,
                    owner = if (ownPromise != null) Groups.ownerOf(ownPromise) else shared,
                    done = task.done,
                    total = milestones.size - dropped,
                    delivered = delivered,
                    dropped = dropped,
                    overdue = overdue,
                    next = rows.firstOrNull { it.open }?.id,
                    milestones = rows.map { it.id },
                    weeks = cells.toList(),
                ),
            )
        }
        return PlanView(
            weeks = weeks,
            week = planWeekOf(start, end, today),
            items = items,
            total = items.sumOf { it.total },
            delivered = items.sumOf { it.delivered },
            overdue = items.sumOf { it.overdue },
        )
    }

    fun planView(plan: ProjectPlan, tasks: List<Task>, ledger: ProLedger?, today: LocalDate, zone: TimeZone): PlanView =
        planView(plan.start, plan.end, tasks, ledger, today, zone)

    /** The plan closed on [today]. */
    fun closePlan(plan: ProjectPlan, today: LocalDate): ProjectPlan = plan.copy(closed = today)

    /**
     * The plan that carries on from one that closes: it starts the day after the old one ended, or
     * today when that has passed, runs the default length, keeps the group and names where it came
     * from.
     */
    fun nextPlan(plan: ProjectPlan, project: Long, today: LocalDate): ProjectPlan {
        val after = plan.end.plus(1, DateTimeUnit.DAY)
        val start = if (after < today) today else after
        return ProjectPlan(start = start, end = defaultPlanEnd(start), group = plan.group, closed = null, from = project)
    }

    /**
     * What moves to the next plan: the items that are not done, each with its milestones that are
     * not done. Tasks move, so their promises and history go with them.
     */
    fun carryOver(tasks: List<Task>, parentOf: (Task) -> Long? = ::parentOf): PlanCarryOver {
        val itemIds = planItemIds(tasks, parentOf)
        val items = tasks.filter { it.id in itemIds && !it.done }.map { it.id }
        val open = items.toSet()
        val milestones = tasks.filter { task ->
            val parent = parentOf(task)
            task.id !in itemIds && parent != null && parent in open && !task.done
        }.map { it.id }
        return PlanCarryOver(items, milestones)
    }
}
