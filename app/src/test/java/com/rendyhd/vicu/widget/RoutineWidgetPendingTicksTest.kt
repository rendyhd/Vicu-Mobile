package com.rendyhd.vicu.widget

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineWidgetPendingTicksTest {
    @Test
    fun aTickRidesOnItsWorkTagAndComesBack() {
        val tick = tick("routine:1", "slot:9", OccurrenceStatus.COMPLETED, at = NOW)
        val tags = setOf(
            "com.rendyhd.vicu.widget.RoutineWidgetActionWorker",
            RoutineWidgetPendingTicks.TAG,
            RoutineWidgetPendingTicks.tagFor(tick),
        )

        assertEquals(tick, RoutineWidgetPendingTicks.tickIn(tags))
    }

    @Test
    fun workWithoutATickTagHasNoTick() {
        assertNull(RoutineWidgetPendingTicks.tickIn(setOf(RoutineWidgetPendingTicks.TAG)))
        assertNull(RoutineWidgetPendingTicks.tickIn(setOf("routine_widget_tick:{not json")))
    }

    /** The reported flicker: a refresh reads the database before the second tick is saved. */
    @Test
    fun aRefreshKeepsATickTheDatabaseDoesNotHaveYetHidden() {
        val saved = RoutineWidgetState(
            DATE,
            listOf(
                item("feed-cat", "morning", OccurrenceStatus.COMPLETED),
                item("feed-cat", "evening", OccurrenceStatus.PENDING),
            ),
        )
        val pending = listOf(tick("feed-cat", "evening", OccurrenceStatus.COMPLETED, at = NOW - 1_000))

        val shown = RoutineWidgetPendingTicks.applyTo(saved, pending, NOW)

        assertTrue(shown.isAllDone)
        assertEquals(2, shown.completedCount)
    }

    @Test
    fun withNoTickPendingTheDatabaseIsShownAsItIs() {
        val saved = RoutineWidgetState(DATE, listOf(item("feed-cat", "evening", OccurrenceStatus.PENDING)))

        assertEquals(saved, RoutineWidgetPendingTicks.applyTo(saved, emptyList(), NOW))
    }

    @Test
    fun aTickOlderThanTheMaximumAgeIsNoLongerLaidOver() {
        val saved = RoutineWidgetState(DATE, listOf(item("feed-cat", "evening", OccurrenceStatus.PENDING)))
        val stale = tick("feed-cat", "evening", OccurrenceStatus.COMPLETED, at = NOW - RoutineWidgetPendingTicks.MAX_AGE_MILLIS)

        assertEquals(saved, RoutineWidgetPendingTicks.applyTo(saved, listOf(stale), NOW))
    }

    @Test
    fun theLatestTapOnAnOccurrenceWins() {
        val saved = RoutineWidgetState(DATE, listOf(item("feed-cat", "evening", OccurrenceStatus.PENDING)))
        val done = tick("feed-cat", "evening", OccurrenceStatus.COMPLETED, at = NOW - 2_000)
        val undone = tick("feed-cat", "evening", OccurrenceStatus.PENDING, at = NOW - 1_000)

        val shown = RoutineWidgetPendingTicks.applyTo(saved, listOf(undone, done), NOW)

        assertEquals(OccurrenceStatus.PENDING, shown.occurrences.single().status)
    }

    @Test
    fun aTickForAnotherDayChangesNothing() {
        val saved = RoutineWidgetState(DATE, listOf(item("feed-cat", "evening", OccurrenceStatus.PENDING)))
        val yesterday = tick("feed-cat", "evening", OccurrenceStatus.COMPLETED, at = NOW, date = "2026-08-10")

        assertEquals(saved, RoutineWidgetPendingTicks.applyTo(saved, listOf(yesterday), NOW))
    }

    private fun tick(
        routineId: String,
        slotId: String,
        status: OccurrenceStatus,
        at: Long,
        date: String = DATE,
    ) = RoutineWidgetPendingTicks.Tick(routineId, date, slotId, status, at)

    private fun item(routineId: String, slotId: String, status: OccurrenceStatus) = RoutineWidgetItem(
        key = "$routineId:$DATE:$slotId",
        routineId = routineId,
        routineName = "Routine $routineId",
        scheduledDate = DATE,
        slotId = slotId,
        slotLabel = slotId,
        status = status,
    )

    private companion object {
        const val DATE = "2026-08-11"
        const val NOW = 1_786_000_000_000L
    }
}
