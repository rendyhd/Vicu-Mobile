package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A group (section 21.16): a meeting record with a label. */
data class Group(
    /** The meeting record's id. */
    val id: String,
    /** The group's label id. */
    val label: Long,
    val name: String,
    /** The members (person label ids), in order. */
    val people: List<Long>,
    /** The recurring meetings it holds, as stored (each has a text `id` and `kind`). */
    val sessions: List<JsonObject>,
    /** False when checks before due are off for this group. */
    val checks: Boolean,
    val language: String?,
)

/** A part of a group's page: an owner (a person's label id, [Groups.YOU], or null) and their actions. */
data class OwnerActions(val owner: Long?, val tasks: List<Long>)

data class GroupPage(
    /** When the last session ended (a UTC instant), or null. */
    val since: String?,
    /** Your own open actions for the group. */
    val yours: List<Long>,
    val overdue: List<OwnerActions>,
    val due: List<OwnerActions>,
    /** Created since the last session. */
    val fresh: List<OwnerActions>,
    val open: List<OwnerActions>,
    val done: List<OwnerActions>,
    /** Open actions without an owner (they are also in their part). */
    val noOwner: List<Long>,
)

data class Reliability(
    /** How many sessions the counts cover: 1 to 4. */
    val sessions: Int,
    /** The first local day of the window. */
    val from: LocalDate,
    val onTime: Int,
    val late: Int,
    val dropped: Int,
    val takenOver: Int,
    val openPastDue: Int,
    val total: Int,
)

/** A log entry with its id. */
data class LogEntry(val id: String, val value: JsonObject)

/** A round with its id. */
data class RoundEntry(val id: String, val value: JsonObject)

/** A group's label id, or null for promises outside a group, with tasks in order. */
data class PersonPart(val group: Long?, val tasks: List<Long>)

data class PersonPage(val toYou: List<PersonPart>, val toOthers: List<PersonPart>)

/**
 * Groups and their actions (docs/cross-app-semantics-v1.md section 21.16), the same as the desktop's
 * `src/shared/pro/groups.ts`: a group is a meeting with a label; an action is a task that carries
 * that label. The group's page, the reliability counts, the issue a second miss adds, and a person's
 * page split by who a promise was made to (21.5).
 */
object Groups {
    /** You, as an owner (the same value as your card in a meeting round). */
    const val YOU = 0L

    const val RELIABILITY_SESSIONS = 4

    private fun labelId(value: JsonElement?): Long? = value.asWhole()?.takeIf { it > 0 }

    private fun groupFrom(id: String, value: JsonObject): Group? {
        val label = labelId(value["label"]) ?: return null
        val sessions = value.list("sessions").orEmpty().mapNotNull { entry ->
            entry.asObject()?.takeIf { it.text("id") != null && it.text("kind") != null }
        }
        return Group(
            id = id,
            label = label,
            name = value.text("name").orEmpty(),
            people = value.list("people").orEmpty().mapNotNull { labelId(it) }.distinct(),
            sessions = sessions,
            checks = value["checks"].asFlag() != false,
            language = value.text("language")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * The groups: meetings with a label. Two meetings naming one label: the lower id (as text) is
     * the group. By name (case ignored), then id.
     */
    fun groupsOf(ledger: ProLedger?): List<Group> {
        val byLabel = LinkedHashMap<Long, Group>()
        val meetings = ledger?.values("meetings").orEmpty()
        for (id in meetings.keys.sortedWith(::compareText)) {
            val group = groupFrom(id, meetings.getValue(id)) ?: continue
            if (group.label !in byLabel) byLabel[group.label] = group
        }
        return byLabel.values.sortedWith { a, b ->
            val name = compareText(a.name.lowercase(), b.name.lowercase())
            if (name != 0) name else compareText(a.id, b.id)
        }
    }

    fun groupOfLabel(ledger: ProLedger?, label: Long): Group? = groupsOf(ledger).firstOrNull { it.label == label }

    fun groupOfMeeting(ledger: ProLedger?, meetingId: String): Group? = groupsOf(ledger).firstOrNull { it.id == meetingId }

    private fun hasLabel(task: Task, label: Long): Boolean = task.labels.any { it.id == label }

    /** The tasks that carry the group's label. */
    fun actionsOf(tasks: List<Task>, label: Long): List<Task> = tasks.filter { hasLabel(it, label) }

    /** An action's owner: the promise's person when it is theirs, [YOU] when it is yours, else nobody. */
    fun ownerOf(promise: JsonObject?): Long? {
        promise ?: return null
        if (promise.text("dir") == "mine") return YOU
        if (promise.text("dir") == "theirs") return labelId(promise["person"])
        return null
    }

    /** The day an action is due now: the promise's effective due day, the task's without a promise. */
    fun actionDue(task: Task, promise: JsonObject?, zone: TimeZone): LocalDate? =
        if (promise != null) Promises.effectiveDue(task, promise, zone) else localDayOf(task.dueDate, zone)

    /** Owners in the group's order, then other people by id, then you, then nobody. */
    fun ownerRank(group: Group, owner: Long?): Pair<Int, Long> {
        if (owner == null) return 3 to 0L
        if (owner == YOU) return 2 to 0L
        val index = group.people.indexOf(owner)
        return if (index >= 0) 0 to index.toLong() else 1 to owner
    }

    internal val BY_RANK = Comparator<Pair<Int, Long>> { a, b ->
        val first = a.first.compareTo(b.first)
        if (first != 0) first else a.second.compareTo(b.second)
    }

    /** Due days, none last. */
    internal val BY_DUE = Comparator<LocalDate?> { a, b ->
        when {
            a == b -> 0
            a == null -> 1
            b == null -> -1
            else -> a.compareTo(b)
        }
    }

    private class Row(val id: Long, val owner: Long?, val due: LocalDate?)

    private val byDueThenId = Comparator<Row> { a, b ->
        val due = BY_DUE.compare(a.due, b.due)
        if (due != 0) due else a.id.compareTo(b.id)
    }

    private fun byOwner(group: Group, rows: List<Row>): List<OwnerActions> {
        val owners = LinkedHashMap<Long?, MutableList<Row>>()
        for (row in rows) owners.getOrPut(row.owner) { mutableListOf() }.add(row)
        return owners.entries
            .sortedWith { a, b -> BY_RANK.compare(ownerRank(group, a.key), ownerRank(group, b.key)) }
            .map { (owner, list) -> OwnerActions(owner, list.sortedWith(byDueThenId).map { it.id }) }
    }

    private fun roundsOf(ledger: ProLedger?): List<RoundEntry> =
        ledger?.values("rounds").orEmpty().map { (id, value) -> RoundEntry(id, value) }

    /** When the group's last session ended (a UTC instant), or null when it had none. */
    fun lastSessionEnd(ledger: ProLedger?, meetingId: String): String? {
        var latest: String? = null
        for ((_, round) in roundsOf(ledger)) {
            if (round.text("meeting") != meetingId) continue
            val ended = round.text("ended") ?: continue
            val at = instantOf(ended) ?: continue
            if (latest == null || at > instantOf(latest)!!) latest = ended
        }
        return latest
    }

    /**
     * The group's page: every action in one part (section 21.16). Open ones, the first that fits:
     * yours, overdue, due, new, open; done ones completed after the last session (or in the last 7
     * days without one). [tasks] may hold others; only those with the group's label count.
     */
    fun groupPage(group: Group, tasks: List<Task>, ledger: ProLedger?, today: LocalDate, zone: TimeZone): GroupPage {
        val since = lastSessionEnd(ledger, group.id)
        val sinceAt = since?.let(::instantOf)
        val sinceDay = since?.let { localDayOf(it, zone) }
        val overdue = mutableListOf<Row>()
        val due = mutableListOf<Row>()
        val fresh = mutableListOf<Row>()
        val open = mutableListOf<Row>()
        val done = mutableListOf<Row>()
        val yours = mutableListOf<Row>()
        val noOwner = mutableListOf<Long>()
        val weekAgo = today.plus(-6, DateTimeUnit.DAY)
        for (task in actionsOf(tasks, group.label)) {
            val promise = Promises.promiseOf(ledger, task.id)
            val row = Row(task.id, ownerOf(promise), actionDue(task, promise, zone))
            if (task.done) {
                val doneAt = instantOf(task.doneAt) ?: continue
                val counts = if (sinceAt != null) doneAt > sinceAt else (localDayOf(task.doneAt, zone)?.let { it >= weekAgo } == true)
                if (counts) done.add(row)
                continue
            }
            if (row.owner == null) noOwner.add(task.id)
            val created = instantOf(task.created)
            when {
                row.owner == YOU -> yours.add(row)
                sinceDay != null && row.due != null && row.due <= sinceDay -> overdue.add(row)
                row.due != null && row.due <= today -> due.add(row)
                sinceAt != null && created != null && created > sinceAt -> fresh.add(row)
                else -> open.add(row)
            }
        }
        return GroupPage(
            since = since,
            yours = yours.sortedWith(byDueThenId).map { it.id },
            overdue = byOwner(group, overdue),
            due = byOwner(group, due),
            fresh = byOwner(group, fresh),
            open = byOwner(group, open),
            done = byOwner(group, done),
            noOwner = noOwner.sorted(),
        )
    }

    // --- Reliability -----------------------------------------------------------------------

    /** The group's ended sessions, newest first. */
    fun endedSessions(ledger: ProLedger?, meetingId: String): List<RoundEntry> =
        roundsOf(ledger)
            .filter { it.value.text("meeting") == meetingId && it.value.text("ended") != null }
            .sortedWith { a, b ->
                val left = instantOf(a.value.text("ended"))
                val right = instantOf(b.value.text("ended"))
                val byEnd = when {
                    left == right -> 0
                    left == null -> 1
                    right == null -> -1
                    else -> right.compareTo(left)
                }
                if (byEnd != 0) byEnd else compareText(a.id, b.id)
            }

    private fun roundDay(round: JsonObject): LocalDate? = round.text("at")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /**
     * Counts over the group's last four ended sessions, or null without one. The window runs from the
     * day after the fifth-last session's day, or from the earliest of them, through today.
     */
    fun reliability(group: Group, tasks: List<Task>, ledger: ProLedger?, today: LocalDate, zone: TimeZone): Reliability? {
        val sessions = endedSessions(ledger, group.id)
        if (sessions.isEmpty()) return null
        val covered = sessions.take(RELIABILITY_SESSIONS)
        val before = sessions.getOrNull(RELIABILITY_SESSIONS)
        val from = (if (before != null) roundDay(before.value)?.plus(1, DateTimeUnit.DAY) else roundDay(covered.last().value)) ?: today
        var onTime = 0
        var late = 0
        var dropped = 0
        var takenOver = 0
        var openPastDue = 0
        for (task in actionsOf(tasks, group.label)) {
            val promise = Promises.promiseOf(ledger, task.id)
            if (promise == null || promise.text("dir") != "theirs") continue
            val final = LedgerRules.finalVerdictOf(promise)
            if (final != null) {
                val day = localDayOf(final.text("at"), zone) ?: continue
                if (day < from || day > today) continue
                when (final.text("kind")) {
                    "delivered" -> onTime++
                    "late" -> late++
                    "dropped" -> dropped++
                    "taken_over" -> takenOver++
                }
            } else if (!task.done) {
                val due = Promises.effectiveDue(task, promise, zone)
                if (due != null && due < today) openPastDue++
            }
        }
        return Reliability(
            sessions = covered.size,
            from = from,
            onTime = onTime,
            late = late,
            dropped = dropped,
            takenOver = takenOver,
            openPastDue = openPastDue,
            total = onTime + late + dropped + takenOver + openPastDue,
        )
    }

    // --- Decisions, questions and issues ---------------------------------------------------

    /** A group's log entries of one kind, newest first (`at`, then id). */
    fun groupLog(ledger: ProLedger?, label: Long, kind: String): List<LogEntry> =
        ledger?.values("log").orEmpty()
            .filter { (_, value) -> value.whole("group") == label && value.text("kind") == kind }
            .map { (id, value) -> LogEntry(id, value) }
            .sortedWith { a, b ->
                val at = compareText(b.value.text("at").orEmpty(), a.value.text("at").orEmpty())
                if (at != 0) at else compareText(a.id, b.id)
            }

    private fun truthy(value: JsonElement?): Boolean = when {
        value == null || value is JsonNull -> false
        value is JsonPrimitive && value.isString -> value.content.isNotEmpty()
        value is JsonPrimitive -> value.content != "false" && value.content != "0"
        else -> true
    }

    /** The group's open issues: by rank (lowest first, without one last), then `at`, then id. */
    fun openIssues(ledger: ProLedger?, label: Long): List<LogEntry> =
        groupLog(ledger, label, "issue")
            .filter { !truthy(it.value["resolved"]) }
            .sortedWith { a, b ->
                val left = a.value.number("rank")
                val right = b.value.number("rank")
                when {
                    left != right && (left == null || right == null) -> if (left == null) 1 else -1
                    left != null && right != null && left != right -> left.compareTo(right)
                    else -> {
                        val at = compareText(a.value.text("at").orEmpty(), b.value.text("at").orEmpty())
                        if (at != 0) at else compareText(a.id, b.id)
                    }
                }
            }

    /**
     * The issue a second miss adds (section 21.16): when the action has exactly [threshold] misses
     * and no open issue of the group names it. Null otherwise.
     */
    fun missIssue(ledger: ProLedger?, taskId: Long, title: String, label: Long, today: LocalDate, threshold: Int): JsonObject? {
        val promise = Promises.promiseOf(ledger, taskId)
        if (promise == null || promise.text("dir") != "theirs" || LedgerRules.finalVerdictOf(promise) != null) return null
        if (Promises.missesOf(promise) != threshold) return null
        if (openIssues(ledger, label).any { it.value.whole("task") == taskId }) return null
        return JsonObject(
            mapOf(
                "kind" to JsonPrimitive("issue"),
                "text" to JsonPrimitive(title.trim()),
                "at" to JsonPrimitive(today.toString()),
                "group" to JsonPrimitive(label),
                "task" to JsonPrimitive(taskId),
            ),
        )
    }

    // --- A person's page (section 21.5) ----------------------------------------------------

    /**
     * A person's open promises of theirs, split by who they were made to, and each by group (the
     * promise's `to.group`, else the lowest group label on the task, else none): without a group
     * first, then by label id; tasks by effective due day (none last), then id.
     */
    fun personPage(personId: Long, tasks: List<Task>, ledger: ProLedger?, groupLabels: Set<Long>, zone: TimeZone): PersonPage {
        val toYou = LinkedHashMap<Long?, MutableList<Row>>()
        val toOthers = LinkedHashMap<Long?, MutableList<Row>>()
        for (task in tasks) {
            if (task.done) continue
            val promise = Promises.promiseOf(ledger, task.id) ?: continue
            if (promise.text("dir") != "theirs" || promise.whole("person") != personId || LedgerRules.finalVerdictOf(promise) != null) continue
            val requester = Promises.requesterOf(promise)
            val onTask = task.labels.map { it.id }.filter { it in groupLabels }.sorted()
            val group = if (requester is Requester.Group) requester.group else onTask.firstOrNull()
            val target = if (requester == Requester.You) toYou else toOthers
            target.getOrPut(group) { mutableListOf() }.add(Row(task.id, personId, Promises.effectiveDue(task, promise, zone)))
        }
        fun parts(map: Map<Long?, List<Row>>): List<PersonPart> =
            map.entries
                .sortedWith { a, b ->
                    val left = a.key
                    val right = b.key
                    when {
                        left == null -> if (right == null) 0 else -1
                        right == null -> 1
                        else -> left.compareTo(right)
                    }
                }
                .map { (group, rows) -> PersonPart(group, rows.sortedWith(byDueThenId).map { it.id }) }
        return PersonPage(parts(toYou), parts(toOthers))
    }

    /** Verdicts on a promise given from [from] through [to] (UTC instants), in order. */
    fun verdictsBetween(promise: JsonObject?, from: String, to: String): List<JsonObject> {
        val start = instantOf(from) ?: return emptyList()
        val end = instantOf(to) ?: return emptyList()
        return LedgerRules.activeVerdicts(promise).filter { verdict ->
            val at = instantOf(verdict.text("at"))
            at != null && at >= start && at <= end
        }
    }
}
