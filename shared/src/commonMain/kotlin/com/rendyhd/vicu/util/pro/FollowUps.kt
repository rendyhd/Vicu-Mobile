package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One line of the follow-ups (section 21.18); [key] names it for "dismissed for today". */
sealed class FollowUpLine {
    abstract val key: String

    /** A verdict due on a promise. */
    data class Verdict(override val key: String, val taskId: Long, val person: Long?, val due: LocalDate) : FollowUpLine()

    /** Checks before due: one line per owner, their tasks by due day. */
    data class Check(override val key: String, val person: Long, val tasks: List<Long>, val due: LocalDate) : FollowUpLine()

    /** A promise to bring back on its `check_on` day. */
    data class Bring(override val key: String, val taskId: Long, val person: Long?, val checkOn: LocalDate) : FollowUpLine()

    /** A session's recap not copied yet. */
    data class Recap(override val key: String, val round: String, val meeting: String, val ended: String) : FollowUpLine()

    /** The weekly note, due from [day]. */
    data class Note(override val key: String, val day: LocalDate) : FollowUpLine()
}

/** The local days a weekly note covers. */
data class NotePeriod(val from: LocalDate, val to: LocalDate)

/**
 * Follow-ups (docs/cross-app-semantics-v1.md section 21.18), the same as the desktop's
 * `src/shared/pro/follow-ups.ts`: everything that needs a move from you, as one ordered list:
 * verdicts due, checks before due (one line per owner), bring-back dates, recaps not copied, and the
 * weekly note. Nothing here is a notification.
 */
object FollowUps {
    const val RECAP_FOLLOW_UP_DAYS = 14
    const val NOTE_MAX_DAYS = 14
    const val NOTE_DEFAULT_DAYS = 7

    /** The day of the latest [weekday] (ISO, 1 to 7) on or before [today]. */
    fun latestWeekday(today: LocalDate, weekday: Int): LocalDate {
        val back = (today.dayOfWeek.isoDayNumber - weekday + 7) % 7
        return today.plus(-back, DateTimeUnit.DAY)
    }

    fun readNoteDay(value: JsonElement?): Int? = value.asWhole()?.takeIf { it in 1..7 }?.toInt()

    private fun latestDay(marks: JsonElement?, zone: TimeZone): LocalDate? {
        var latest: LocalDate? = null
        for (mark in (marks as? JsonArray).orEmpty()) {
            val day = localDayOf(mark.asText(), zone) ?: continue
            if (latest == null || day > latest) latest = day
        }
        return latest
    }

    /** The day the weekly note is due from, or null when it is not due (or no day is set). */
    fun noteDue(settings: JsonObject?, today: LocalDate, zone: TimeZone): LocalDate? {
        val weekday = readNoteDay(settings?.get("note_day")) ?: return null
        val day = latestWeekday(today, weekday)
        val sent = latestDay(settings?.get("note_sent"), zone)
        return if (sent != null && sent >= day) null else day
    }

    /**
     * The local days a weekly note covers: from the day after the last one was copied (at most 14
     * days back; 7 days without one) through today.
     */
    fun notePeriod(settings: JsonObject?, today: LocalDate, zone: TimeZone): NotePeriod {
        val sent = latestDay(settings?.get("note_sent"), zone)
        val earliest = today.plus(-(NOTE_MAX_DAYS - 1), DateTimeUnit.DAY)
        if (sent == null) return NotePeriod(today.plus(-(NOTE_DEFAULT_DAYS - 1), DateTimeUnit.DAY), today)
        val from = sent.plus(1, DateTimeUnit.DAY)
        return NotePeriod(
            when {
                from < earliest -> earliest
                from > today -> today
                else -> from
            },
            today,
        )
    }

    /**
     * The lines, in the order of section 21.18, without the ones dismissed for today. [snoozes] is
     * "Ask me tomorrow" per task id; [dismissed] is the day each dismissed line's key comes back.
     */
    fun followUps(
        tasks: List<Task>,
        ledger: ProLedger?,
        today: LocalDate,
        zone: TimeZone,
        snoozes: Map<String, VerdictSnooze> = emptyMap(),
        dismissed: Map<String, String> = emptyMap(),
    ): List<FollowUpLine> {
        val settings = ledger?.let { LedgerRules.settings(it).value }
        val checkDays = WorkingDays.readCheckDays(settings?.get("check_days"))
        val workdays = WorkingDays.readWorkdays(settings?.get("workdays"))
        val groups = Groups.groupsOf(ledger)
        val groupByLabel = groups.associateBy { it.label }
        val groupMeetings = groups.map { it.id }.toSet()

        val verdicts = mutableListOf<FollowUpLine.Verdict>()
        val checks = LinkedHashMap<Long, MutableList<Pair<Long, LocalDate>>>()
        val brings = mutableListOf<FollowUpLine.Bring>()
        val inLine = mutableSetOf<Long>()

        for (task in tasks) {
            if (task.done) continue
            val promise = Promises.promiseOf(ledger, task.id) ?: continue
            if (LedgerRules.finalVerdictOf(promise) != null) continue
            val due = Promises.effectiveDue(task, promise, zone)
            if (Promises.needsVerdict(task, promise, today, snoozes[task.id.toString()], zone) && due != null) {
                verdicts.add(FollowUpLine.Verdict("verdict:${task.id}", task.id, promise.whole("person"), due))
                inLine.add(task.id)
                continue
            }
            val person = promise.whole("person")
            if (promise.text("dir") != "theirs" || person == null || promise["no_check"].asFlag() == true) continue
            if (due == null || due <= today) continue
            val ofGroups = task.labels.mapNotNull { groupByLabel[it.id] }
            if (ofGroups.isEmpty() || ofGroups.none { it.checks }) continue
            val checkDay = WorkingDays.workingDaysBefore(due, checkDays, workdays)
            if (today < checkDay || Promises.checkedSince(promise, checkDay, zone)) continue
            checks.getOrPut(person) { mutableListOf() }.add(task.id to due)
            inLine.add(task.id)
        }

        for (task in tasks) {
            if (task.done || task.id in inLine) continue
            val promise = Promises.promiseOf(ledger, task.id) ?: continue
            if (LedgerRules.finalVerdictOf(promise) != null) continue
            val checkOn = Promises.checkOnOf(promise) ?: continue
            if (checkOn > today || Promises.checkedSince(promise, checkOn, zone)) continue
            brings.add(FollowUpLine.Bring("bring:${task.id}", task.id, promise.whole("person"), checkOn))
        }

        val lines = mutableListOf<FollowUpLine>()
        lines.addAll(verdicts.sortedWith(compareBy<FollowUpLine.Verdict> { it.due }.thenBy { it.taskId }))
        val checkLines = checks.map { (person, list) ->
            val sorted = list.sortedWith(compareBy<Pair<Long, LocalDate>> { it.second }.thenBy { it.first })
            FollowUpLine.Check("check:$person", person, sorted.map { it.first }, sorted.first().second)
        }
        lines.addAll(checkLines.sortedWith(compareBy<FollowUpLine.Check> { it.due }.thenBy { it.person }))
        lines.addAll(brings.sortedWith(compareBy<FollowUpLine.Bring> { it.checkOn }.thenBy { it.taskId }))

        class RecapRow(val line: FollowUpLine.Recap, val ended: Instant)

        val recaps = mutableListOf<RecapRow>()
        for ((id, round) in ledger?.values("rounds").orEmpty()) {
            val ended = round.text("ended") ?: continue
            val meeting = round.text("meeting") ?: continue
            if (meeting !in groupMeetings) continue
            if (round.list("recap_sent").orEmpty().isNotEmpty()) continue
            val endedAt = instantOf(ended) ?: continue
            val endedDay = localDayOf(ended, zone) ?: continue
            val age = endedDay.daysUntil(today)
            if (age < 0 || age >= RECAP_FOLLOW_UP_DAYS) continue
            recaps.add(RecapRow(FollowUpLine.Recap("recap:$id", id, meeting, ended), endedAt))
        }
        recaps.sortWith { a, b ->
            val at = a.ended.compareTo(b.ended)
            if (at != 0) at else compareText(a.line.round, b.line.round)
        }
        lines.addAll(recaps.map { it.line })

        noteDue(settings, today, zone)?.let { lines.add(FollowUpLine.Note("note:$it", it)) }

        val day = today.toString()
        return lines.filter { line ->
            val until = dismissed[line.key]
            !(until != null && day < until)
        }
    }

    /** The dismissals that still hide something: entries whose day has come are dropped. */
    fun pruneDismissed(dismissed: Map<String, String>?, today: LocalDate): Map<String, String> {
        val day = today.toString()
        return dismissed.orEmpty().filterValues { day < it }
    }
}
