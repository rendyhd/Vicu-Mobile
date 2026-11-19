package com.rendyhd.vicu.util

import kotlin.time.Instant

/**
 * The count on the app icon (Settings, Notifications, "Count on app icon"; this device only, off
 * by default; rendyhd/Vicu-Mobile#109). It is the number of tasks on the Today list, by the Today
 * list's own rule (contract section 2: every count of the Today list, badges and widgets included),
 * counted by `TaskWidgetStates.todayCount`.
 *
 * iOS puts the number on the icon and sets tomorrow's number ahead for the next local midnight,
 * since no code runs then. Android launchers take their badges from notifications, so Android shows
 * one silent notification that carries the number.
 */
object TodayBadge {

    /** What the icon shows now, and from [midnight] on. */
    data class Plan(
        /** The tasks on the Today list now; 0 shows no count. */
        val count: Int,
        /** The tasks on tomorrow's Today list, as the data is now; null when nothing is set ahead. */
        val atMidnight: Int?,
        /** The next local midnight, when [atMidnight] takes over; null when nothing is set ahead. */
        val midnight: Instant?,
    ) {
        val isOff: Boolean get() = midnight == null

        companion object {
            /** The setting is off or nobody is signed in: no count, nothing set ahead. */
            val OFF = Plan(count = 0, atMidnight = null, midnight = null)
        }
    }

    /**
     * The plan for the local [day]: [today] tasks on its Today list now, [tomorrow] on the next
     * day's (overdue tasks included, so tomorrow's count holds today's open tasks too).
     */
    fun plan(today: Int, tomorrow: Int, day: ClockDay): Plan = Plan(
        count = today.coerceAtLeast(0),
        atMidnight = tomorrow.coerceAtLeast(0),
        midnight = DueDates.startOfTomorrow(day.date, day.zone),
    )

    /** The Android notification's title: "1 task due today", "11 tasks due today". */
    fun title(count: Int): String = if (count == 1) "1 task due today" else "$count tasks due today"

    /** What Android does with its notification. */
    enum class AndroidStep {
        /** Post it (again): a new number, or it is not showing and was not swiped away at this number. */
        POST,

        /** Leave it: it already shows this number, or the user swiped it away at this number. */
        KEEP,

        /** Take it away: no count. */
        CANCEL,
    }

    /**
     * Whether Android posts its notification for [count]. [showing] is the number of the one on
     * screen (null when there is none), [dismissed] the number it had when the user last swiped it
     * away (null when not). A swiped-away notification stays away until the number changes; posting
     * the same number again would bring it back after every sync.
     */
    fun androidStep(count: Int, showing: Int?, dismissed: Int?): AndroidStep = when {
        count <= 0 -> AndroidStep.CANCEL
        showing == count -> AndroidStep.KEEP
        showing == null && dismissed == count -> AndroidStep.KEEP
        else -> AndroidStep.POST
    }
}
