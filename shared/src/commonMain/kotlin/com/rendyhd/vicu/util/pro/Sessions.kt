package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.WeekStart
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class SessionKind(val key: String) {
    WEEKLY("weekly"),
    STANDUP("standup"),
}

enum class SessionStep(val key: String) {
    ACTIONS("actions"),
    ISSUES("issues"),
    NEW("new"),
    READBACK("readback"),
}

data class SessionActions(
    /** Open actions that need a verdict, in order. */
    val verdicts: List<Long>,
    /** The other open actions, in order. */
    val others: List<Long>,
)

/** The last verdict given in a session on one action. */
data class SessionVerdict(val taskId: Long, val verdict: JsonObject)

data class SessionContents(
    val verdicts: List<SessionVerdict>,
    /** Actions created in the session, by id. */
    val created: List<Long>,
    /** Log entry ids captured in the session, by kind, in `at` then id order. */
    val decisions: List<String>,
    val questions: List<String>,
    val issues: List<String>,
    /** Issues solved in the session. */
    val solved: List<String>,
    /** Blockers, commitments and stops captured in the session. */
    val blockers: List<String>,
    val commitments: List<String>,
    val stops: List<String>,
)

/**
 * Sessions (docs/cross-app-semantics-v1.md section 21.17), the same as the desktop's
 * `src/shared/pro/sessions.ts`: one meeting of a group, of a kind. The steps of a kind, the order of
 * the actions step, what a session held (worked out from its times, never stored), and the round
 * written at the end. The session itself runs on the desktop only; the phone reads its records.
 */
object Sessions {
    val SESSION_STEPS: Map<SessionKind, List<SessionStep>> = mapOf(
        SessionKind.WEEKLY to listOf(SessionStep.ACTIONS, SessionStep.ISSUES, SessionStep.NEW, SessionStep.READBACK),
        SessionKind.STANDUP to listOf(SessionStep.ACTIONS, SessionStep.NEW, SessionStep.READBACK),
    )

    /** Other kinds are kept in the record and run as `weekly`. */
    fun sessionKindOf(kind: String?): SessionKind = if (kind == "standup") SessionKind.STANDUP else SessionKind.WEEKLY

    /**
     * The actions step: the group's open actions, first those that need a verdict, then the others;
     * in a stand-up only those due on or before the last day of this week. Each list in the owner
     * order (the group's people, other people by id, you, nobody), then due day (none last), then id.
     */
    fun sessionActions(
        group: Group,
        tasks: List<Task>,
        ledger: ProLedger?,
        today: LocalDate,
        kind: SessionKind,
        weekStart: WeekStart,
        zone: TimeZone,
    ): SessionActions {
        class Row(val id: Long, val owner: Long?, val due: LocalDate?, val verdict: Boolean)

        val weekEnd = DueDates.endOfWeek(today, weekStart)
        val rows = mutableListOf<Row>()
        for (task in Groups.actionsOf(tasks, group.label)) {
            if (task.done) continue
            val promise = Promises.promiseOf(ledger, task.id)
            val due = Groups.actionDue(task, promise, zone)
            if (kind == SessionKind.STANDUP && (due == null || due > weekEnd)) continue
            rows.add(Row(task.id, Groups.ownerOf(promise), due, Promises.needsVerdict(task, promise, today, null, zone)))
        }
        rows.sortWith { a, b ->
            val rank = Groups.BY_RANK.compare(Groups.ownerRank(group, a.owner), Groups.ownerRank(group, b.owner))
            if (rank != 0) return@sortWith rank
            val due = Groups.BY_DUE.compare(a.due, b.due)
            if (due != 0) due else a.id.compareTo(b.id)
        }
        return SessionActions(rows.filter { it.verdict }.map { it.id }, rows.filter { !it.verdict }.map { it.id })
    }

    /** What a session held (section 21.17): worked out from its times and the log's `session`. */
    fun sessionContents(
        round: String,
        started: String,
        ended: String,
        group: Group,
        tasks: List<Task>,
        ledger: ProLedger?,
    ): SessionContents {
        val start = instantOf(started)
        val end = instantOf(ended)
        val verdicts = mutableListOf<SessionVerdict>()
        val created = mutableListOf<Long>()
        for (task in Groups.actionsOf(tasks, group.label)) {
            val given = Groups.verdictsBetween(Promises.promiseOf(ledger, task.id), started, ended)
            if (given.isNotEmpty()) verdicts.add(SessionVerdict(task.id, given.last()))
            val at = instantOf(task.created)
            if (at != null && start != null && end != null && at >= start && at <= end) created.add(task.id)
        }
        val byKind = mapOf<String, MutableList<Pair<String, String>>>(
            "decision" to mutableListOf(),
            "unknown" to mutableListOf(),
            "issue" to mutableListOf(),
            "blocker" to mutableListOf(),
            "commitment" to mutableListOf(),
            "stop" to mutableListOf(),
        )
        val solved = mutableListOf<Pair<String, String>>()
        for ((id, value) in ledger?.values("log").orEmpty()) {
            val at = value.text("at").orEmpty()
            if (value.text("session") == round) value.text("kind")?.let { byKind[it] }?.add(id to at)
            if (value.text("kind") == "issue" && value.text("solved_in") == round) solved.add(id to at)
        }
        fun ids(list: List<Pair<String, String>>): List<String> =
            list.sortedWith { a, b ->
                val at = compareText(a.second, b.second)
                if (at != 0) at else compareText(a.first, b.first)
            }.map { it.first }
        return SessionContents(
            verdicts = verdicts.sortedBy { it.taskId },
            created = created.sorted(),
            decisions = ids(byKind.getValue("decision")),
            questions = ids(byKind.getValue("unknown")),
            issues = ids(byKind.getValue("issue")),
            solved = ids(solved),
            blockers = ids(byKind.getValue("blocker")),
            commitments = ids(byKind.getValue("commitment")),
            stops = ids(byKind.getValue("stop")),
        )
    }

    /** A whole number from 1 to 10, or null. */
    fun readScore(value: JsonElement?): Int? = value.asWhole()?.takeIf { it in 1..10 }?.toInt()

    /**
     * The round written when a session ends. [kind] is kept as given; [at] is the local day the
     * session started on; [score] is private and left out unless it is 1 to 10.
     */
    fun endSession(
        meeting: String,
        kind: String,
        at: LocalDate,
        started: String,
        ended: String,
        absent: List<Long>,
        readBack: Boolean,
        score: Int? = null,
    ): JsonObject {
        val empty = JsonArray(emptyList())
        val round = linkedMapOf<String, JsonElement>(
            "meeting" to JsonPrimitive(meeting),
            "at" to JsonPrimitive(at.toString()),
            "discussed" to empty,
            "skipped" to empty,
            "absent" to JsonArray(absent.distinct().map(::JsonPrimitive)),
            "not_discussed" to empty,
            "kind" to JsonPrimitive(kind),
            "started" to JsonPrimitive(started),
            "ended" to JsonPrimitive(ended),
            "read_back" to JsonPrimitive(readBack),
        )
        score?.takeIf { it in 1..10 }?.let { round["score"] = JsonPrimitive(it) }
        return JsonObject(round)
    }

    /** The round with its recap copied at [at]. */
    fun withRecapSent(round: JsonObject, at: String): JsonObject =
        round.with("recap_sent", JsonArray((round.texts("recap_sent") + at).toSet().sorted().map(::JsonPrimitive)))

    /** Undo of the copy. */
    fun withoutRecapSent(round: JsonObject, at: String): JsonObject {
        val left = round.list("recap_sent").orEmpty().filter { it.asText() != at }
        return if (left.isNotEmpty()) round.with("recap_sent", JsonArray(left)) else round.without("recap_sent")
    }

    /** True when any verdict on the promise is still active (a helper for session views). */
    fun hasActiveVerdict(ledger: ProLedger?, taskId: Long): Boolean =
        LedgerRules.activeVerdicts(Promises.promiseOf(ledger, taskId)).isNotEmpty()
}
