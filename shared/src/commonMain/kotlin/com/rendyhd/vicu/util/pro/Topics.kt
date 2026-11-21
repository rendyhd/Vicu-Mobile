package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

data class PersonTopics(
    /** Topics for the next 1:1: without a date, or dated up to seven days from today. */
    val now: List<Long>,
    /** Topics dated later. */
    val later: List<Long>,
    /** The topics of [now] and [later] that carry a private label: left out of copied text. */
    val hidden: List<Long>,
)

/**
 * 1:1 topics (docs/cross-app-semantics-v1.md section 21.21), the same as the desktop's
 * `src/shared/pro/topics.ts`: something to discuss with a person, not something anyone delivers. A
 * topic is an ordinary open task that carries the person's label and is no promise, so it shows on
 * every device, with or without Pro. Both apps run the `pro.topics` vectors.
 */
object Topics {
    /** A topic with a date further away than this waits: a repeating topic that is not up yet. */
    const val TOPIC_SOON_DAYS = 7

    private class Row(val id: Long, val due: LocalDate?, val created: String, val hidden: Boolean)

    /**
     * A person's topics: open tasks with the person's label that have no promise record and are not
     * a "confirm with" task. Dated ones first by day, then undated ones oldest first, then id.
     * [privateLabels] are the labels marked private (`label_kind` = `private`).
     */
    fun personTopics(
        person: Long,
        tasks: List<Task>,
        ledger: ProLedger?,
        today: LocalDate,
        zone: TimeZone,
        privateLabels: Set<Long> = emptySet(),
    ): PersonTopics {
        val soon = today.plus(TOPIC_SOON_DAYS, DateTimeUnit.DAY)
        val rows = mutableListOf<Row>()
        for (task in tasks) {
            if (task.done) continue
            val labels = task.labels.map { it.id }
            if (person !in labels) continue
            // With a promise record it is a promise's task, whatever its verdicts; a tombstone is no record.
            if (Promises.promiseOf(ledger, task.id) != null) continue
            if (Promises.readConfirmTitle(task.title) != null) continue
            rows.add(
                Row(
                    id = task.id,
                    due = localDayOf(task.dueDate, zone),
                    created = task.created,
                    hidden = labels.any { it in privateLabels },
                ),
            )
        }
        rows.sortWith { a, b ->
            val due = Groups.BY_DUE.compare(a.due, b.due)
            when {
                due != 0 -> due
                a.created != b.created -> compareText(a.created, b.created)
                else -> a.id.compareTo(b.id)
            }
        }
        return PersonTopics(
            now = rows.filter { it.due == null || it.due <= soon }.map { it.id },
            later = rows.filter { it.due != null && it.due > soon }.map { it.id },
            hidden = rows.filter { it.hidden }.map { it.id },
        )
    }
}
