package com.rendyhd.vicu.util.pro

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Working days (docs/cross-app-semantics-v1.md section 21.18), the same as the desktop's
 * `src/shared/pro/working-days.ts`: the ISO weekdays the setting `workdays` lists, Monday to Friday
 * unless set. Public holidays are not known.
 */
object WorkingDays {
    val DEFAULT_WORKDAYS: List<Int> = listOf(1, 2, 3, 4, 5)
    const val DEFAULT_CHECK_DAYS = 2
    const val MAX_CHECK_DAYS = 10

    /** The setting as a clean list: whole numbers 1 to 7, once each, in order; the default when none is left. */
    fun readWorkdays(value: JsonElement?): List<Int> {
        val array = value as? JsonArray ?: return DEFAULT_WORKDAYS
        val days = array.mapNotNull { it.asWhole()?.takeIf { day -> day in 1..7 }?.toInt() }.toSet().sorted()
        return days.ifEmpty { DEFAULT_WORKDAYS }
    }

    /** How many working days before its due day a check comes up: 2 unless set, 0 to 10. */
    fun readCheckDays(value: JsonElement?): Int =
        value.asWhole()?.takeIf { it in 0..MAX_CHECK_DAYS }?.toInt() ?: DEFAULT_CHECK_DAYS

    fun isWorkingDay(day: LocalDate, workdays: List<Int> = DEFAULT_WORKDAYS): Boolean =
        day.dayOfWeek.isoDayNumber in workdays

    /** The day [n] working days before [day], counting back from the day before it; 0 is the day itself. */
    fun workingDaysBefore(day: LocalDate, n: Int, workdays: List<Int> = DEFAULT_WORKDAYS): LocalDate {
        val days = workdays.ifEmpty { DEFAULT_WORKDAYS }
        var current = day
        var left = maxOf(0, n)
        while (left > 0) {
            current = current.minus(1, DateTimeUnit.DAY)
            if (current.dayOfWeek.isoDayNumber in days) left--
        }
        return current
    }
}
