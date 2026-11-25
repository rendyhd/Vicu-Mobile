package com.rendyhd.vicu.ui.screens.taskentry

import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.location.LocationTrigger
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DayChoice
import com.rendyhd.vicu.util.DayChoiceId
import com.rendyhd.vicu.util.ReminderFormat
import com.rendyhd.vicu.util.WeekStart
import com.rendyhd.vicu.util.WhenChoiceSet
import com.rendyhd.vicu.util.WhenChoices
import com.rendyhd.vicu.util.WhenLogic
import com.rendyhd.vicu.util.WhenValue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

// What the trays of the new-task sheet offer (design concept 3): the When tiles and the words of
// the Remind chip. Pure, so the day and the clock are passed in.

/** One tile of the When tray: a day choice, or No date when [choice] is null. [value] is what a tap picks; null clears the date. */
internal data class WhenTile(val choice: DayChoiceId?, val label: String, val hint: String, val value: WhenValue?)

/**
 * The When tray's tiles at [now] on [today]: the account's day choices (contract section 23.2,
 * [WhenChoices.days]) and No date. A day tile keeps the time of day of [current] ("tomorrow, same
 * time"); This evening and Tomorrow morning set their own time. Hints are phrased with [fmt].
 */
internal fun whenTiles(
    current: WhenValue,
    today: LocalDate,
    now: LocalTime,
    fmt: DateDisplayFormat,
    weekStart: WeekStart = WeekStart.DEFAULT,
    choices: WhenChoiceSet = WhenChoiceSet(),
): List<WhenTile> {
    val keepTime = current.time?.takeIf { current.date != null }
    val days = WhenChoices.days(choices.days, LocalDateTime(today, now), choices.times, weekStart).map { choice ->
        WhenTile(choice.id, choice.id.label, dayChoiceHint(choice, fmt), WhenValue(choice.date, choice.time ?: keepTime))
    }
    return days + WhenTile(null, "No date", "", null)
}

/** What a day choice says next to its label: "Wed", "Mon 12" for next week and next month, "18:00", "Thu 9:00". */
internal fun dayChoiceHint(choice: DayChoice, fmt: DateDisplayFormat): String {
    val time = choice.time
    return when {
        time != null && choice.id == DayChoiceId.THIS_EVENING -> WhenLogic.timeLabel(time, fmt)
        time != null -> "${DateDisplay.formatWeekdayShort(choice.date, fmt)} ${WhenLogic.timeLabel(time, fmt)}"
        choice.id == DayChoiceId.NEXT_WEEK || choice.id == DayChoiceId.NEXT_MONTH -> DateDisplay.formatWeekdayDay(choice.date, fmt)
        else -> DateDisplay.formatWeekdayShort(choice.date, fmt)
    }
}

/**
 * The tile that stands for [current], or null when none does (a date picked on the calendar). A
 * tile with its own time matches that day and time; otherwise the first tile of that day does,
 * so a Saturday that is both Tomorrow and This weekend is Tomorrow.
 */
internal fun selectedTile(tiles: List<WhenTile>, current: WhenValue): WhenTile? {
    val date = current.date ?: return tiles.firstOrNull { it.choice == null }
    return tiles.firstOrNull { it.choice != null && hasOwnTime(it.choice) && it.value == current }
        ?: tiles.firstOrNull { it.choice != null && !hasOwnTime(it.choice) && it.value?.date == date }
}

private fun hasOwnTime(choice: DayChoiceId): Boolean =
    choice == DayChoiceId.THIS_EVENING || choice == DayChoiceId.TOMORROW_MORNING

/**
 * The Remind chip: the one reminder in words ("Tomorrow, 9:00 AM", "1 hour before", "Arrive:
 * Home"), "3 reminders" when there are more, null when there are none (the chip then says
 * "Remind"). The place reminder counts as one.
 */
internal fun entryRemindWords(reminders: List<TaskReminder>, place: PlacePick?, fmt: DateDisplayFormat): String? =
    ReminderFormat.summary(reminders, fmt, place?.let { entryPlaceLabel(it.place.name, it.trigger) }).ifEmpty { null }

/** "Arrive: Home" or "Leave: Work", as a location reminder reads everywhere else. */
internal fun entryPlaceLabel(name: String, trigger: LocationTrigger): String = when (trigger) {
    LocationTrigger.ARRIVE -> "Arrive: $name"
    LocationTrigger.LEAVE -> "Leave: $name"
}
