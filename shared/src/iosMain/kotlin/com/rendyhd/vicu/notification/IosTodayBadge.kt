package com.rendyhd.vicu.notification

import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.TodayBadge
import com.rendyhd.vicu.widget.AppIconCount
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSDateComponents
import platform.Foundation.NSNumber
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * The count on the app icon on iOS ([TodayBadge], "Count on app icon" in Settings, Notifications):
 * the number is set on the icon with `setBadgeCount` (iOS 16 and later; the app needs iOS 17).
 *
 * No code runs at midnight while the app is not running, so one pending notification with a fixed
 * id ([ID]) is kept for the next local midnight: it has no title, text or sound, only the badge,
 * set to tomorrow's count as the data is now. It is replaced on every [update] and removed, with
 * the badge set to 0, when the setting is off or nobody is signed in.
 *
 * [update] runs with every widget snapshot write ([com.rendyhd.vicu.widget.IosWidgetSnapshots]):
 * every change of the stored tasks, the app coming to the front or going to the background, a new
 * day, a background refresh. The notification center is reached only when used: it exists only in
 * a running app.
 */
class IosTodayBadge(
    private val count: AppIconCount,
    private val time: TimeSource,
) {
    private val center: UNUserNotificationCenter get() = UNUserNotificationCenter.currentNotificationCenter()

    suspend fun update() {
        try {
            val plan = count.plan()
            showCount(plan.count)
            val midnight = plan.midnight
            val atMidnight = plan.atMidnight
            if (midnight == null || atMidnight == null) {
                center.removePendingNotificationRequestsWithIdentifiers(listOf(ID))
                center.removeDeliveredNotificationsWithIdentifiers(listOf(ID))
            } else {
                add(midnightRequest(atMidnight, midnight.toLocalDateTime(time.zone())))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w(TAG, "App icon count not updated: ${e.message}")
        }
    }

    private suspend fun showCount(count: Int) = suspendCancellableCoroutine { continuation ->
        center.setBadgeCount(count.toLong()) { error ->
            // Refused while badges are not allowed; Settings says so.
            if (error != null) Logger.d(TAG, "Badge not set: ${error.localizedDescription}")
            continuation.resume(Unit)
        }
    }

    /** At the wall-clock midnight (no zone: midnight wherever the phone is then), only the badge. */
    private fun midnightRequest(badge: Int, at: kotlinx.datetime.LocalDateTime): UNNotificationRequest {
        val content = UNMutableNotificationContent().apply {
            setBadge(NSNumber(int = badge))
        }
        val components = NSDateComponents().apply {
            setYear(at.year.toLong())
            setMonth(at.month.ordinal.toLong() + 1)
            setDay(at.day.toLong())
            setHour(at.hour.toLong())
            setMinute(at.minute.toLong())
            setSecond(at.second.toLong())
        }
        val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = false)
        return UNNotificationRequest.requestWithIdentifier(ID, content, trigger)
    }

    private suspend fun add(request: UNNotificationRequest) = suspendCancellableCoroutine { continuation ->
        center.addNotificationRequest(request) { error ->
            if (error != null) Logger.d(TAG, "Midnight count not set: ${error.localizedDescription}")
            continuation.resume(Unit)
        }
    }

    companion object {
        private const val TAG = "IosTodayBadge"

        /** The pending midnight notification; the delegate presents it with the badge only. */
        const val ID = "today-count"
    }
}
