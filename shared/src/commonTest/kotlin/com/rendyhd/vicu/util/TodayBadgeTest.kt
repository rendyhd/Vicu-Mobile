package com.rendyhd.vicu.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TodayBadgeTest {

    @Test
    fun theTitleCountsTasksDueToday() {
        assertEquals("1 task due today", TodayBadge.title(1))
        assertEquals("11 tasks due today", TodayBadge.title(11))
        assertEquals("150 tasks due today", TodayBadge.title(150))
    }

    @Test
    fun thePlanSetsTomorrowsCountForTheNextLocalMidnight() {
        val day = ClockDay(LocalDate(2026, 11, 18), TimeZone.of("Pacific/Auckland"))
        val plan = TodayBadge.plan(today = 3, tomorrow = 5, day = day)
        assertEquals(3, plan.count)
        assertEquals(5, plan.atMidnight)
        // 19 November 00:00 in Auckland (UTC+13 in summer).
        assertEquals(Instant.parse("2026-11-18T11:00:00Z"), plan.midnight)
        assertFalse(plan.isOff)
    }

    @Test
    fun theNextMidnightFollowsTheZone() {
        val day = ClockDay(LocalDate(2026, 11, 18), TimeZone.of("America/New_York"))
        assertEquals(Instant.parse("2026-11-19T05:00:00Z"), TodayBadge.plan(0, 0, day).midnight)
    }

    @Test
    fun offShowsNoCountAndSetsNothingAhead() {
        val off = TodayBadge.Plan.OFF
        assertEquals(0, off.count)
        assertNull(off.atMidnight)
        assertNull(off.midnight)
        assertTrue(off.isOff)
    }

    @Test
    fun aCountIsNeverNegative() {
        val plan = TodayBadge.plan(-1, -2, ClockDay(LocalDate(2026, 11, 18), TimeZone.UTC))
        assertEquals(0, plan.count)
        assertEquals(0, plan.atMidnight)
    }

    @Test
    fun androidPostsANewNumber() {
        assertEquals(TodayBadge.AndroidStep.POST, TodayBadge.androidStep(count = 4, showing = null, dismissed = null))
        assertEquals(TodayBadge.AndroidStep.POST, TodayBadge.androidStep(count = 4, showing = 3, dismissed = null))
    }

    @Test
    fun androidLeavesTheSameNumberAlone() {
        assertEquals(TodayBadge.AndroidStep.KEEP, TodayBadge.androidStep(count = 4, showing = 4, dismissed = null))
    }

    @Test
    fun aSwipedAwayNotificationComesBackOnlyWithAnotherNumber() {
        assertEquals(TodayBadge.AndroidStep.KEEP, TodayBadge.androidStep(count = 4, showing = null, dismissed = 4))
        assertEquals(TodayBadge.AndroidStep.POST, TodayBadge.androidStep(count = 5, showing = null, dismissed = 4))
        assertEquals(TodayBadge.AndroidStep.POST, TodayBadge.androidStep(count = 3, showing = null, dismissed = 4))
    }

    @Test
    fun androidCancelsAtZero() {
        assertEquals(TodayBadge.AndroidStep.CANCEL, TodayBadge.androidStep(count = 0, showing = 2, dismissed = null))
        assertEquals(TodayBadge.AndroidStep.CANCEL, TodayBadge.androidStep(count = 0, showing = null, dismissed = 2))
    }
}
