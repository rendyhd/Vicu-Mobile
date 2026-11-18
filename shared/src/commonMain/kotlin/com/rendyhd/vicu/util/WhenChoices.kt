package com.rendyhd.vicu.util

import kotlin.concurrent.Volatile
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A named time of day (contract section 22.1): "Morning" at 09:00. */
data class TimeOfDay(val name: String, val time: LocalTime)

/** A day choice of the When pickers (section 22.2), with its synced id and its label. */
enum class DayChoiceId(val key: String, val label: String) {
    TODAY("today", "Today"),
    THIS_EVENING("this_evening", "This evening"),
    TOMORROW("tomorrow", "Tomorrow"),
    TOMORROW_MORNING("tomorrow_morning", "Tomorrow morning"),
    THIS_WEEKEND("this_weekend", "This weekend"),
    NEXT_WEEK("next_week", "Next week"),
    IN_2_DAYS("in_2_days", "In 2 days"),
    IN_3_DAYS("in_3_days", "In 3 days"),
    NEXT_MONTH("next_month", "Next month"),
    ;

    companion object {
        fun of(key: String): DayChoiceId? = entries.firstOrNull { it.key == key }
    }
}

/** A Move by step (section 22.3): so many calendar days, or a month. */
enum class MoveStep(val key: String, val label: String, val days: Int, val months: Int) {
    DAY_1("1d", "+1 day", 1, 0),
    DAY_2("2d", "+2 days", 2, 0),
    DAY_3("3d", "+3 days", 3, 0),
    WEEK_1("1w", "+1 week", 7, 0),
    WEEK_2("2w", "+2 weeks", 14, 0),
    MONTH_1("1m", "+1 month", 0, 1),
    ;

    companion object {
        fun of(key: String): MoveStep? = entries.firstOrNull { it.key == key }
    }
}

/** A day choice as offered now: its day, and its time when it sets one (null keeps the picked time). */
data class DayChoice(val id: DayChoiceId, val date: LocalDate, val time: LocalTime?)

/** The account's When choices (sections 22.1 to 22.3), as read from the synced preferences. */
data class WhenChoiceSet(
    val times: List<TimeOfDay> = WhenChoices.DEFAULT_TIMES,
    val days: List<DayChoiceId> = WhenChoices.DEFAULT_DAYS,
    val moveBy: List<MoveStep> = WhenChoices.DEFAULT_MOVE_BY,
)

/**
 * The times of day the app uses now, for code that reads text without a view model to hand them
 * over (the quick-add parser, section 5.6). The app start keeps it in step with the synced setting
 * ([com.rendyhd.vicu.data.repository.WhenChoicesPreferences.follow]); until then it is the default.
 */
object CurrentTimesOfDay {
    @Volatile
    var value: List<TimeOfDay> = WhenChoices.DEFAULT_TIMES
}

/**
 * The When choices (docs/cross-app-semantics-v1.md, section 22): reading and writing the three
 * synced settings, the day choices offered now, and Move by. The desktop has the same in
 * `src/shared/when-choices.ts`.
 */
object WhenChoices {
    const val MAX_TIMES = 5
    const val MAX_DAYS = 6
    const val MAX_MOVE_BY = 4
    const val MAX_NAME_LENGTH = 20

    val DEFAULT_TIMES: List<TimeOfDay> = listOf(
        TimeOfDay("Morning", LocalTime(9, 0)),
        TimeOfDay("Noon", LocalTime(12, 0)),
        TimeOfDay("Afternoon", LocalTime(15, 0)),
        TimeOfDay("Evening", LocalTime(18, 0)),
    )
    val DEFAULT_DAYS: List<DayChoiceId> = listOf(
        DayChoiceId.TODAY, DayChoiceId.THIS_EVENING, DayChoiceId.TOMORROW, DayChoiceId.THIS_WEEKEND, DayChoiceId.NEXT_WEEK,
    )
    val DEFAULT_MOVE_BY: List<MoveStep> = listOf(MoveStep.DAY_1, MoveStep.DAY_2, MoveStep.WEEK_1, MoveStep.MONTH_1)

    private val NOON = LocalTime(12, 0)
    private val EVENING_FROM = LocalTime(17, 0)

    // --- Times of day (22.1) -----------------------------------------------------------------

    /** Whether [name] may be a time of day's name: letters, digits and single spaces, 1 to 20 long. */
    fun isValidName(name: String): Boolean =
        name.length in 1..MAX_NAME_LENGTH && name == name.trim() && !name.contains("  ") &&
            name.all { it.isLetterOrDigit() || it == ' ' }

    /** The stored `when.times`, in time order; any value that breaks the rules is the default. */
    fun readTimes(value: JsonElement?): List<TimeOfDay> {
        val array = value as? JsonArray ?: return DEFAULT_TIMES
        val times = array.map { entry ->
            val obj = entry as? JsonObject ?: return DEFAULT_TIMES
            val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return DEFAULT_TIMES
            val time = (obj["time"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::parseTime) ?: return DEFAULT_TIMES
            TimeOfDay(name, time)
        }
        return if (isValidTimes(times)) times.sortedBy { it.time } else DEFAULT_TIMES
    }

    /** Whether [times] keeps the rules of 22.1 (any order). */
    fun isValidTimes(times: List<TimeOfDay>): Boolean =
        times.size in 1..MAX_TIMES &&
            times.all { isValidName(it.name) && it.time.second == 0 && it.time.nanosecond == 0 } &&
            times.map { it.name.lowercase() }.toSet().size == times.size &&
            times.map { it.time }.toSet().size == times.size

    /** What to write for [times]: null for the default, else the entries in time order. */
    fun writeTimes(times: List<TimeOfDay>): JsonElement? {
        val sorted = times.sortedBy { it.time }
        if (sorted == DEFAULT_TIMES) return null
        return JsonArray(
            sorted.map { buildJsonObject { put("name", it.name); put("time", formatTime(it.time)) } },
        )
    }

    /** The earliest time before 12:00, or null. */
    fun morningTime(times: List<TimeOfDay>): LocalTime? = times.map { it.time }.filter { it < NOON }.minOrNull()

    /** The latest time at 17:00 or later, or null. */
    fun eveningTime(times: List<TimeOfDay>): LocalTime? = times.map { it.time }.filter { it >= EVENING_FROM }.maxOrNull()

    /** "HH:mm", 24-hour, two digits each; null for anything else. */
    fun parseTime(text: String): LocalTime? {
        val match = TIME_RE.matchEntire(text) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return if (hour < 24 && minute < 60) LocalTime(hour, minute) else null
    }

    fun formatTime(time: LocalTime): String =
        "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"

    private val TIME_RE = Regex("""(\d{2}):(\d{2})""")

    // --- Day choices and Move by (22.2, 22.3) --------------------------------------------------

    fun readDays(value: JsonElement?): List<DayChoiceId> =
        readIds(value, MAX_DAYS, DEFAULT_DAYS, DayChoiceId::of)

    fun readMoveBy(value: JsonElement?): List<MoveStep> =
        readIds(value, MAX_MOVE_BY, DEFAULT_MOVE_BY, MoveStep::of)

    /** What to write for [days]: null for the default. Unknown ids are not carried along. */
    fun writeDays(days: List<DayChoiceId>): JsonElement? =
        if (days == DEFAULT_DAYS) null else JsonArray(days.map { JsonPrimitive(it.key) })

    fun writeMoveBy(steps: List<MoveStep>): JsonElement? =
        if (steps == DEFAULT_MOVE_BY) null else JsonArray(steps.map { JsonPrimitive(it.key) })

    private fun <T> readIds(value: JsonElement?, max: Int, default: List<T>, of: (String) -> T?): List<T> {
        val array = value as? JsonArray ?: return default
        val keys = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return default }
        if (keys.size > max || keys.toSet().size != keys.size) return default
        return keys.mapNotNull(of)
    }

    /**
     * The day choices of [ids] offered at [now], in order: This evening only while there is an
     * evening time still ahead, Tomorrow morning only while there is a morning time.
     */
    fun days(
        ids: List<DayChoiceId>,
        now: LocalDateTime,
        times: List<TimeOfDay> = DEFAULT_TIMES,
        weekStart: WeekStart = WeekStart.DEFAULT,
    ): List<DayChoice> {
        val today = now.date
        val morning = morningTime(times)
        val evening = eveningTime(times)
        return ids.mapNotNull { id ->
            when (id) {
                DayChoiceId.TODAY -> DayChoice(id, today, null)
                DayChoiceId.THIS_EVENING -> evening?.takeIf { now.time < it }?.let { DayChoice(id, today, it) }
                DayChoiceId.TOMORROW -> DayChoice(id, today.plus(1, DateTimeUnit.DAY), null)
                DayChoiceId.TOMORROW_MORNING -> morning?.let { DayChoice(id, today.plus(1, DateTimeUnit.DAY), it) }
                DayChoiceId.THIS_WEEKEND -> DayChoice(id, WhenLogic.comingSaturday(today), null)
                DayChoiceId.NEXT_WEEK -> DayChoice(id, DueDates.nextWeekDay(today, weekStart), null)
                DayChoiceId.IN_2_DAYS -> DayChoice(id, today.plus(2, DateTimeUnit.DAY), null)
                DayChoiceId.IN_3_DAYS -> DayChoice(id, today.plus(3, DateTimeUnit.DAY), null)
                DayChoiceId.NEXT_MONTH -> DayChoice(id, today.plus(1, DateTimeUnit.MONTH), null)
            }
        }
    }

    /** The day [step] lands on for a due date on [dueDay]: an overdue task moves from [today]. */
    fun moveDay(dueDay: LocalDate, step: MoveStep, today: LocalDate): LocalDate =
        maxOf(dueDay, today).plus(step.days, DateTimeUnit.DAY).plus(step.months, DateTimeUnit.MONTH)

    /**
     * [due] moved by [step] (section 22.3): from its local day, or [today] when that is earlier;
     * a date-only due date stays date-only and a timed one keeps its local clock time.
     */
    fun moveBy(due: Instant, step: MoveStep, today: LocalDate, zone: TimeZone): Instant {
        val local = due.toLocalDateTime(zone)
        val day = moveDay(local.date, step, today)
        return if (DueDates.isDateOnly(due, zone)) {
            DueDates.dateOnlyDue(day, zone)
        } else {
            LocalDateTime(day, local.time).toInstant(zone)
        }
    }

    // --- The parser (5.6) ----------------------------------------------------------------------

    /** Words of section 5.1 that are never read as a time of day's name. */
    private val DATE_WORDS = setOf(
        "today", "tomorrow", "tonight", "now", "next", "this", "last", "in", "at", "on", "by", "due",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun",
        "january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )

    /** Whether the parser reads [name] as a time of day: a valid name that is no date word. */
    fun isReadableName(name: String): Boolean = isValidName(name) && name.lowercase() !in DATE_WORDS
}
