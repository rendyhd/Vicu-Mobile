package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskOrderSettings
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** The guided sessions (docs/cross-app-semantics-v1.md, section 14.2). */
enum class GuidedQueue(val id: String, val title: String) {
    INBOX("inbox", "Inbox"),
    OVERDUE("overdue", "Overdue"),
    WAITING("waiting", "Waiting"),
    ;

    companion object {
        fun of(id: String): GuidedQueue? = entries.firstOrNull { it.id == id }
    }
}

/** A project's health (section 14.5). */
data class ProjectHealth(
    val open: Int,
    val untouched: Int,
    val overdue: Int,
    val scheduled: Boolean,
    val lastDoneDays: Int?,
    /** Its untouched open tasks, the longest untouched first (section 14.4, step 3). */
    val untouchedIds: List<Long>,
    /** The first open task in the project's task order: the next step offered without a model. */
    val firstOpen: Long?,
) {
    val line: String get() = Guided.healthLine(this)
}

/** A step of the weekly review (section 14.7); [count] is null for `mind`. */
data class WeeklyStep(val id: String, val title: String, val count: Int?, val ids: List<Long> = emptyList())

/** An item of Ask's agenda (section 14.8); [count] is null for `weekly`. */
data class AgendaItem(val id: String, val title: String, val count: Int?, val detail: String?)

/**
 * What the guided rules read: one account's tasks and projects at one moment, with the Inbox, the
 * day and the review settings. Hidden tasks (section 10.1) are left out here.
 */
class GuidedWorld(
    tasks: List<Task>,
    val projects: List<Project>,
    val inboxId: Long?,
    val today: LocalDate,
    val zone: TimeZone,
    val reviewEnabled: Boolean,
    val reviewCadenceDays: Int,
    val reviewExcludeInbox: Boolean,
    val order: TaskOrderSettings = TaskOrderSettings(),
    /** A project's permission (section 9.1); the project's own `maxPermission` when not given. */
    private val permission: (Project) -> Int = { it.maxPermission },
) {
    val tasks: List<Task> = tasks.filterNot { HiddenTasks.isHidden(it.description) }
    val projectById: Map<Long, Project> = projects.associateBy { it.id }

    /** Archived itself or below an archived project. */
    fun isArchived(projectId: Long): Boolean {
        val seen = HashSet<Long>()
        var current: Long? = projectId
        while (current != null && current != 0L && seen.add(current)) {
            val p = projectById[current] ?: return false
            if (p.isArchived) return true
            current = p.parentProjectId
        }
        return false
    }

    /** The person can change the project's tasks: known, not archived, permission above 0. */
    fun canChange(projectId: Long): Boolean {
        val project = projectById[projectId] ?: return false
        return !isArchived(projectId) && permission(project) != 0
    }

    /** A project's titles from the top, joined with ` / `. */
    fun path(projectId: Long): String {
        val titles = mutableListOf<String>()
        val seen = HashSet<Long>()
        var current: Long? = projectId
        while (current != null && current != 0L && seen.add(current)) {
            val p = projectById[current] ?: break
            titles += p.title
            current = p.parentProjectId
        }
        return titles.reversed().joinToString(" / ")
    }

    fun ageDays(task: Task): Int? = Guided.daysSince(task.created, today, zone)

    fun untouchedDays(task: Task): Int? = Guided.daysSince(task.updated, today, zone)
}

/**
 * The rules of guided organizing (docs/cross-app-semantics-v1.md, section 14): the queues, a
 * project's health, the weekly review's steps, Ask's agenda and how long ago something was. The
 * same rules as the desktop's `src/shared/guided.ts`, run against the `guided` vectors.
 */
object Guided {
    const val WAITING_DAYS = 60
    const val WAITING_MAX = 30
    const val REVIEW_UNTOUCHED_DAYS = 30
    const val LOOK_AHEAD_DAYS = 14
    const val WEEKLY_DUE_DAYS = 7
    const val INBOX_AGE_DAYS = 7

    /** Today minus the local date of a server timestamp, or null when there is none. */
    fun daysSince(timestamp: String?, today: LocalDate, zone: TimeZone): Int? {
        val instant = DateUtils.parseIsoDate(timestamp) ?: return null
        return DueDates.localDateOf(instant, zone).daysUntil(today)
    }

    private fun hasDue(task: Task): Boolean = DateUtils.parseIsoDate(task.dueDate) != null

    // --- Queues (14.2) -----------------------------------------------------------------------

    fun queue(queue: GuidedQueue, world: GuidedWorld): List<Long> = when (queue) {
        GuidedQueue.INBOX -> inboxQueue(world)
        GuidedQueue.OVERDUE -> overdueQueue(world)
        GuidedQueue.WAITING -> waitingQueue(world)
    }

    fun inboxQueue(world: GuidedWorld): List<Long> {
        val inbox = world.inboxId?.takeIf { world.canChange(it) } ?: return emptyList()
        val open = world.tasks.filter { !it.done && it.projectId == inbox }
        return sortProjectTasks(open, world.order.orderFor(inbox, world.projects)).map { it.id }
    }

    fun overdueQueue(world: GuidedWorld): List<Long> = world.tasks
        .filter { !it.done && world.canChange(it.projectId) && DueDates.isOverdue(it.dueDate, world.today, world.zone) }
        .sortedWith(compareBy({ DateUtils.parseIsoDate(it.dueDate) }, { it.id }))
        .map { it.id }

    fun waitingQueue(world: GuidedWorld): List<Long> = world.tasks
        .asSequence()
        .filter { !it.done && it.projectId != world.inboxId && world.canChange(it.projectId) }
        .filter { !hasDue(it) && !isRecurring(it.repeatAfter, it.repeatMode) }
        .mapNotNull { task -> world.untouchedDays(task)?.takeIf { it >= WAITING_DAYS }?.let { task to it } }
        .sortedWith(compareBy({ -it.second }, { it.first.id }))
        .take(WAITING_MAX)
        .map { it.first.id }
        .toList()

    /** The projects of the review session (14.4), in its order. */
    fun reviewQueue(world: GuidedWorld): List<Long> {
        if (!world.reviewEnabled) return emptyList()
        return world.projects.asSequence()
            .filter { world.canChange(it.id) }
            .filterNot { world.reviewExcludeInbox && it.id == world.inboxId }
            .map { it to ReviewMetadata.computeStatus(ReviewMetadata.parse(it.description), world.reviewCadenceDays, world.today) }
            .filter { (_, status) -> status.metadata.state != ReviewState.EXCLUDED && status.isOverdue }
            .sortedWith(
                compareBy(
                    { (_, status) -> if (status.daysUntilDue == null) 0 else 1 },
                    { (_, status) -> status.daysUntilDue ?: 0L },
                    { (p, _) -> world.path(p.id).lowercase() },
                    { (p, _) -> p.id },
                ),
            )
            .map { (p, _) -> p.id }
            .toList()
    }

    // --- Health (14.5) -----------------------------------------------------------------------

    fun health(world: GuidedWorld, projectId: Long): ProjectHealth {
        val own = world.tasks.filter { it.projectId == projectId }
        val open = own.filter { !it.done }
        val untouched = open
            .mapNotNull { task -> world.untouchedDays(task)?.takeIf { it >= REVIEW_UNTOUCHED_DAYS }?.let { task to it } }
            .sortedWith(compareBy({ -it.second }, { it.first.id }))
            .map { it.first.id }
        val lastDone = own.filter { it.done }
            .mapNotNull { DateUtils.parseIsoDate(it.doneAt) }
            .maxOrNull()
            ?.let { DueDates.localDateOf(it, world.zone).daysUntil(world.today) }
        return ProjectHealth(
            open = open.size,
            untouched = untouched.size,
            overdue = open.count { DueDates.isOverdue(it.dueDate, world.today, world.zone) },
            scheduled = open.any { task -> DueDates.localDateOf(task.dueDate, world.zone)?.let { it >= world.today } == true },
            lastDoneDays = lastDone,
            untouchedIds = untouched,
            firstOpen = sortProjectTasks(open, world.order.orderFor(projectId, world.projects)).firstOrNull()?.id,
        )
    }

    fun healthLine(health: ProjectHealth): String = buildList {
        add(if (health.open == 0) "No open tasks" else "${health.open} open")
        if (health.untouched > 0) add("${health.untouched} untouched for $REVIEW_UNTOUCHED_DAYS+ days")
        if (health.overdue > 0) add("${health.overdue} overdue")
        if (health.open > 0 && !health.scheduled) add("nothing scheduled")
        health.lastDoneDays?.let { add("last done ${ago(it)}") }
    }.joinToString(" · ")

    // --- Ago (14.6) --------------------------------------------------------------------------

    fun ago(days: Int): String = when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        days < 14 -> "$days days ago"
        days < 60 -> "${days / 7} weeks ago"
        days < 730 -> "${days / 30} months ago"
        else -> "${days / 365} years ago"
    }

    /** "Added 3 weeks ago" on a card, or null without a created date. */
    fun added(task: Task, world: GuidedWorld): String? = world.ageDays(task)?.let { "Added ${ago(it)}" }

    // --- Weekly review (14.7) ----------------------------------------------------------------

    /** Tasks done in the last 7 days, the newest first. */
    fun recentlyDone(world: GuidedWorld): List<Task> {
        val since = world.today.minus(6, DateTimeUnit.DAY)
        return world.tasks.asSequence()
            .filter { it.done && !world.isArchived(it.projectId) }
            .mapNotNull { task -> DateUtils.parseIsoDate(task.doneAt)?.let { task to it } }
            .filter { (_, at) -> DueDates.localDateOf(at, world.zone) >= since }
            .sortedWith(compareByDescending<Pair<Task, kotlin.time.Instant>> { it.second }.thenBy { it.first.id })
            .map { it.first }
            .toList()
    }

    /** Open tasks due from tomorrow to today + [LOOK_AHEAD_DAYS], by due date. */
    fun lookAhead(world: GuidedWorld): List<Task> {
        val last = world.today.plus(LOOK_AHEAD_DAYS, DateTimeUnit.DAY)
        return world.tasks
            .filter { !it.done && !world.isArchived(it.projectId) }
            .filter { task -> DueDates.localDateOf(task.dueDate, world.zone)?.let { it > world.today && it <= last } == true }
            .sortedWith(compareBy({ DateUtils.parseIsoDate(it.dueDate) }, { it.id }))
    }

    fun weeklySteps(world: GuidedWorld): List<WeeklyStep> = buildList {
        add(WeeklyStep(STEP_INBOX, "Sort the Inbox", inboxQueue(world).size))
        add(WeeklyStep(STEP_MIND, "Anything on your mind?", null))
        recentlyDone(world).let { done -> add(WeeklyStep(STEP_DONE, "Look back", done.size, done.map { it.id })) }
        add(WeeklyStep(STEP_OVERDUE, "Overdue", overdueQueue(world).size))
        lookAhead(world).let { ahead -> add(WeeklyStep(STEP_UPCOMING, "Look ahead", ahead.size, ahead.map { it.id })) }
        if (world.reviewEnabled) add(WeeklyStep(STEP_REVIEW, "Review projects", reviewQueue(world).size))
        add(WeeklyStep(STEP_WAITING, "Waiting a long time", waitingQueue(world).size))
    }

    /** Days since the last weekly review, or null when it was never done (or the value cannot be read). */
    fun weeklyReviewAge(last: String?, today: LocalDate): Int? =
        last?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.daysUntil(today)

    fun isWeeklyReviewDue(last: String?, today: LocalDate): Boolean =
        weeklyReviewAge(last, today)?.let { it >= WEEKLY_DUE_DAYS } ?: true

    // --- Ask's agenda (14.8) -----------------------------------------------------------------

    fun agenda(world: GuidedWorld, weeklyReviewLast: String?): List<AgendaItem> = buildList {
        val inbox = inboxQueue(world)
        if (inbox.isNotEmpty()) {
            val waiting = inbox.count { id -> world.tasks.firstOrNull { it.id == id }?.let { world.ageDays(it) }?.let { it >= INBOX_AGE_DAYS } == true }
            add(AgendaItem(STEP_INBOX, "Sort the Inbox", inbox.size, if (waiting > 0) "$waiting waiting over a week" else null))
        }
        val overdue = overdueQueue(world)
        if (overdue.isNotEmpty()) add(AgendaItem(STEP_OVERDUE, "Sort out overdue", overdue.size, null))
        val review = reviewQueue(world)
        if (review.isNotEmpty()) add(AgendaItem(STEP_REVIEW, "Review projects", review.size, null))
        if (isWeeklyReviewDue(weeklyReviewLast, world.today)) {
            val age = weeklyReviewAge(weeklyReviewLast, world.today)
            add(AgendaItem(AGENDA_WEEKLY, "Weekly review", null, if (age == null) "Not done yet" else "Last done ${ago(age)}"))
        }
    }

    const val STEP_INBOX = "inbox"
    const val STEP_MIND = "mind"
    const val STEP_DONE = "done"
    const val STEP_OVERDUE = "overdue"
    const val STEP_UPCOMING = "upcoming"
    const val STEP_REVIEW = "review"
    const val STEP_WAITING = "waiting"
    const val AGENDA_WEEKLY = "weekly"
}
