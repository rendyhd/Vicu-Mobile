package com.rendyhd.vicu.ui.screens.taskentry

import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.location.LocationTrigger
import com.rendyhd.vicu.location.SavedPlace
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DayChoiceId
import com.rendyhd.vicu.util.TimeOfDay
import com.rendyhd.vicu.util.WhenChoiceSet
import com.rendyhd.vicu.util.WhenValue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The When tray's tiles and the Remind chip's words (design concept 3). */
class QuickAddChoicesTest {

    private val fmt = DateDisplayFormat(localeTag = "en-US", hour12 = false)

    // Thursday 22 October 2026.
    private val today = LocalDate(2026, 10, 22)

    @Test
    fun `before six the tray offers this evening`() {
        val tiles = whenTiles(WhenValue.EMPTY, today, LocalTime(15, 0), fmt)

        assertEquals(
            listOf(
                DayChoiceId.TODAY,
                DayChoiceId.THIS_EVENING,
                DayChoiceId.TOMORROW,
                DayChoiceId.THIS_WEEKEND,
                DayChoiceId.NEXT_WEEK,
                null,
            ),
            tiles.map { it.choice },
        )
        val evening = tiles.single { it.choice == DayChoiceId.THIS_EVENING }
        assertEquals(WhenValue(today, LocalTime(18, 0)), evening.value)
        assertEquals("18:00", evening.hint)
        assertNull(tiles.single { it.choice == null }.value)
    }

    @Test
    fun `from six this evening is gone`() {
        val tiles = whenTiles(WhenValue.EMPTY, today, LocalTime(18, 0), fmt)

        assertEquals(null, tiles.firstOrNull { it.choice == DayChoiceId.THIS_EVENING })
        assertEquals(5, tiles.size)
    }

    @Test
    fun `the tray follows the account's day choices and evening time`() {
        val choices = WhenChoiceSet(
            times = listOf(TimeOfDay("Morning", LocalTime(7, 30)), TimeOfDay("Evening", LocalTime(20, 0))),
            days = listOf(DayChoiceId.TOMORROW_MORNING, DayChoiceId.THIS_EVENING, DayChoiceId.IN_3_DAYS),
        )
        val tiles = whenTiles(WhenValue.EMPTY, today, LocalTime(19, 0), fmt, choices = choices)

        assertEquals(
            listOf(DayChoiceId.TOMORROW_MORNING, DayChoiceId.THIS_EVENING, DayChoiceId.IN_3_DAYS, null),
            tiles.map { it.choice },
        )
        assertEquals(WhenValue(LocalDate(2026, 10, 23), LocalTime(7, 30)), tiles[0].value)
        assertEquals("Fri 07:30", tiles[0].hint)
        assertEquals(WhenValue(today, LocalTime(20, 0)), tiles[1].value)
        assertEquals(LocalDate(2026, 10, 25), tiles[2].value?.date)
    }

    @Test
    fun `a day tile keeps the time already picked`() {
        val current = WhenValue(today, LocalTime(9, 0))

        val tomorrow = whenTiles(current, today, LocalTime(8, 0), fmt).single { it.choice == DayChoiceId.TOMORROW }

        assertEquals(WhenValue(LocalDate(2026, 10, 23), LocalTime(9, 0)), tomorrow.value)
    }

    @Test
    fun `the tile for the current value is picked`() {
        val tiles = whenTiles(WhenValue.EMPTY, today, LocalTime(8, 0), fmt)
        fun pick(value: WhenValue) = selectedTile(tiles, value)?.choice

        assertEquals(tiles.last(), selectedTile(tiles, WhenValue.EMPTY))
        assertEquals(DayChoiceId.TODAY, pick(WhenValue(today, null)))
        assertEquals(DayChoiceId.THIS_EVENING, pick(WhenValue(today, LocalTime(18, 0))))
        assertEquals(DayChoiceId.TOMORROW, pick(WhenValue(LocalDate(2026, 10, 23), null)))
        // Saturday is this weekend; Monday 26 is next week; a date further out is no tile.
        assertEquals(DayChoiceId.THIS_WEEKEND, pick(WhenValue(LocalDate(2026, 10, 24), null)))
        assertEquals(DayChoiceId.NEXT_WEEK, pick(WhenValue(LocalDate(2026, 10, 26), null)))
        assertNull(selectedTile(tiles, WhenValue(LocalDate(2026, 11, 3), null)))
    }

    @Test
    fun `on a Friday the Saturday tile is tomorrow`() {
        val friday = LocalDate(2026, 10, 23)
        val tiles = whenTiles(WhenValue.EMPTY, friday, LocalTime(8, 0), fmt)

        assertEquals(DayChoiceId.TOMORROW, selectedTile(tiles, WhenValue(LocalDate(2026, 10, 24), null))?.choice)
    }

    @Test
    fun `the remind chip says the one reminder and counts more`() {
        val home = PlacePick(SavedPlace(id = "h", name = "Home"), LocationTrigger.ARRIVE)
        val beforeDue = TaskReminder(relativePeriod = -3600, relativeTo = "due_date")

        assertNull(entryRemindWords(emptyList(), null, fmt))
        assertEquals("Arrive: Home", entryRemindWords(emptyList(), home, fmt))
        assertEquals("1 hour before", entryRemindWords(listOf(beforeDue), null, fmt))
        assertEquals("2 reminders", entryRemindWords(listOf(beforeDue), home, fmt))
        assertEquals("Leave: Work", entryPlaceLabel("Work", LocationTrigger.LEAVE))
    }
}
