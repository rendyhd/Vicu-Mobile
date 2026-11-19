package com.rendyhd.vicu.widget

import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.TodayBadge
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus

/**
 * Reads what the app icon's count should be ([TodayBadge]) from the data on the device: the
 * setting, the session, and the Today list of today and tomorrow by the widgets' own rule
 * ([TaskWidgetStates.todayCount]). Android's notification and the iOS badge both apply it.
 */
class AppIconCount(
    private val states: TaskWidgetStates,
    private val notificationPrefs: NotificationPrefsStore,
    private val dayClock: DayClock,
) {
    suspend fun plan(): TodayBadge.Plan {
        if (!notificationPrefs.getPrefs().first().todayCountBadge || !states.isSignedIn()) return TodayBadge.Plan.OFF
        // Re-read first: this can run from a worker or a background task long after the last tick.
        dayClock.refresh()
        val today = dayClock.day.value
        val tomorrow = today.copy(date = today.date.plus(1, DateTimeUnit.DAY))
        return TodayBadge.plan(states.todayCount(today), states.todayCount(tomorrow), today)
    }
}
