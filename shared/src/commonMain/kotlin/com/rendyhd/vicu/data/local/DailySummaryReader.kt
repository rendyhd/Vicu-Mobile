package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.remote.api.UserDto
import com.rendyhd.vicu.util.ReminderGate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.DailySummary
import com.rendyhd.vicu.util.TimeBlocks

/** What a daily summary notification contains. */
data class DailySummaryContent(
    val counts: DailySummary.Counts,
    /** Up to a few titles of tasks due today (not overdue), earliest first. */
    val dueTodayTitles: List<String>,
) {
    /**
     * The lines of the expanded notification: today's titles, how many more are due today, then
     * the overdue and tomorrow counts.
     */
    fun lines(): List<String> = buildList {
        addAll(dueTodayTitles)
        val remaining = counts.dueToday - dueTodayTitles.size
        if (remaining > 0) add("+$remaining more today")
        if (counts.overdue > 0) add("${counts.overdue} overdue tasks need attention")
        if (counts.dueTomorrow > 0) add("${counts.dueTomorrow} due tomorrow")
    }
}

/** Reads the counts of the daily summary from the local database. */
class DailySummaryReader(
    private val taskDao: TaskDao,
    /** Per-project notification modes: tasks held back are not counted or named. */
    private val gate: suspend () -> ReminderGate = { ReminderGate.OPEN },
) {

    suspend fun read(
        day: ClockDay,
        includeOverdue: Boolean,
        includeDueToday: Boolean,
        includeTomorrow: Boolean,
    ): DailySummaryContent {
        val b = DailySummary.boundaries(day.date, day.zone)
        val allowed = gate()
        if (!allowed.allowsEverything) return readGated(day, b, allowed, includeOverdue, includeDueToday, includeTomorrow)
        // A block starting today is on the Today list whatever its due date (contract section 2).
        val blocksToday = if (includeDueToday) {
            taskDao.getStartingTodayWithoutDueToday(b.startOfToday, b.startOfTomorrow)
                .filter { TimeBlocks.isBlock(it.startDate, it.endDate, day.zone) }
        } else {
            emptyList()
        }
        val counts = DailySummary.Counts(
            overdue = if (includeOverdue) taskDao.countOverdue(b.startOfToday) else 0,
            dueToday = if (includeDueToday) taskDao.countDueToday(b.startOfToday, b.startOfTomorrow) + blocksToday.size else 0,
            dueTomorrow = if (includeTomorrow) taskDao.countDueTomorrow(b.startOfTomorrow, b.startOfDayAfterTomorrow) else 0,
        )
        val titles = if (counts.dueToday > 0) {
            val due = taskDao.getDueTodaySync(b.startOfToday, b.startOfTomorrow, TITLE_LIMIT)
            (due.map { it.title } + blocksToday.map { it.title }).take(TITLE_LIMIT)
        } else {
            emptyList()
        }
        return DailySummaryContent(counts, titles)
    }

    /**
     * The same counts as [read], taken from the rows themselves so the per-project modes can
     * hold tasks back (the SQL counts cannot see assignees).
     */
    private suspend fun readGated(
        day: ClockDay,
        b: DailySummary.Boundaries,
        allowed: ReminderGate,
        includeOverdue: Boolean,
        includeDueToday: Boolean,
        includeTomorrow: Boolean,
    ): DailySummaryContent {
        fun TaskEntity.passes() = allowed.allows(projectId, assigneeIds())
        // Every open task due before the day after tomorrow, plus those starting today or tomorrow.
        val rows = taskDao.getTodayTasksSync(b.startOfToday, b.startOfDayAfterTomorrow)
            .filter { it.passes() && it.dueDate.isNotEmpty() && it.dueDate != NO_DATE }
        val overdue = rows.filter { it.dueDate < b.startOfToday }
        val dueToday = rows.filter { it.dueDate >= b.startOfToday && it.dueDate < b.startOfTomorrow }.sortedBy { it.dueDate }
        val dueTomorrow = rows.filter { it.dueDate >= b.startOfTomorrow && it.dueDate < b.startOfDayAfterTomorrow }
        val blocksToday = if (includeDueToday) {
            taskDao.getStartingTodayWithoutDueToday(b.startOfToday, b.startOfTomorrow)
                .filter { it.passes() && TimeBlocks.isBlock(it.startDate, it.endDate, day.zone) }
        } else {
            emptyList()
        }
        val counts = DailySummary.Counts(
            overdue = if (includeOverdue) overdue.size else 0,
            dueToday = if (includeDueToday) dueToday.size + blocksToday.size else 0,
            dueTomorrow = if (includeTomorrow) dueTomorrow.size else 0,
        )
        val titles = if (counts.dueToday > 0) {
            (dueToday.map { it.title } + blocksToday.map { it.title }).take(TITLE_LIMIT)
        } else {
            emptyList()
        }
        return DailySummaryContent(counts, titles)
    }

    private fun TaskEntity.assigneeIds(): List<Long> =
        runCatching { assigneeJson.decodeFromString(ASSIGNEES, assigneesJson).map { it.id } }.getOrDefault(emptyList())

    companion object {
        private const val NO_DATE = "0001-01-01T00:00:00Z"

        private val ASSIGNEES = ListSerializer(UserDto.serializer())
        private val assigneeJson = Json { ignoreUnknownKeys = true }
        const val TITLE_LIMIT = 3
    }
}
