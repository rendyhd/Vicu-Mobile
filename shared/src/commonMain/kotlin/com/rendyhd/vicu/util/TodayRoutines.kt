package com.rendyhd.vicu.util

/**
 * Routines on Today (docs/cross-app-semantics-v1.md section 6.10): one row per routine for its
 * earliest open time today, which rows are due now, how many show, and the line behind which the
 * rest wait. Pure, the same rules as the desktop's `today-routines.ts`; the `todayRoutines` vectors
 * of the fixture pin it.
 */
object TodayRoutines {
    /** A row is due now when its time is at most this many minutes away, or has passed. */
    const val SOON_MINUTES = 120

    /** The due-now rows shown unless the user changed it. */
    const val DEFAULT_LIMIT = 2

    /** The limit that shows every due-now row. */
    const val ALL = 0

    /** The limits Settings offers; [ALL] is "All". */
    val LIMIT_CHOICES = listOf(1, 2, 3, 5, ALL)

    /** A routine's row: the occurrence it stands for and how far the routine is today. */
    data class Row<T>(
        val item: T,
        val routineId: String,
        val minutes: Int,
        val finished: Int,
        val total: Int,
        val dueNow: Boolean,
    ) {
        /** `1/3` when the routine has more than one time today. */
        val progress: String? get() = if (total > 1) "$finished/$total" else null
    }

    data class Plan<T>(
        /** Every row, in order. */
        val rows: List<Row<T>>,
        /** The rows shown while the line is closed. */
        val visible: List<Row<T>>,
    ) {
        /** The rows behind the line. */
        val hidden: List<Row<T>> get() = rows.filter { it !in visible }

        /** The closed line: `2 later today` or `Show 3 more`; null when nothing is behind it. */
        val lineText: String?
            get() {
                val behind = hidden
                if (behind.isEmpty()) return null
                return if (behind.none { it.dueNow }) "${behind.size} later today" else "Show ${behind.size} more"
            }
    }

    /**
     * [items] are today's occurrences; [carried] marks an `after_completion` occurrence carried
     * to today from an earlier date. [limit] is a count, or [ALL].
     */
    fun <T : Any> plan(
        items: List<T>,
        nowMinutes: Int,
        limit: Int,
        routineId: (T) -> String,
        name: (T) -> String,
        minutes: (T) -> Int,
        finished: (T) -> Boolean,
        carried: (T) -> Boolean,
    ): Plan<T> {
        val rows = items.groupBy(routineId).mapNotNull { (id, occurrences) ->
            val next = occurrences.filterNot(finished).minByOrNull(minutes) ?: return@mapNotNull null
            Row(
                item = next,
                routineId = id,
                minutes = minutes(next),
                finished = occurrences.count(finished),
                total = occurrences.size,
                dueNow = carried(next) || minutes(next) <= nowMinutes + SOON_MINUTES,
            )
        }.sortedWith(compareBy<Row<T>> { it.minutes }.thenBy { name(it.item).lowercase() })
        val dueNow = rows.filter { it.dueNow }
        val visible = if (limit == ALL) dueNow else dueNow.take(limit)
        return Plan(rows, visible)
    }
}
