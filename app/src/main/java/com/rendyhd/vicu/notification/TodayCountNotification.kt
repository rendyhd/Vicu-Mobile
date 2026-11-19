package com.rendyhd.vicu.notification

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.R
import com.rendyhd.vicu.putViewTarget
import com.rendyhd.vicu.ui.navigation.ViewTarget
import com.rendyhd.vicu.util.TodayBadge
import com.rendyhd.vicu.widget.AppIconCount
import kotlin.time.Instant

/**
 * The count on the app icon on Android ([TodayBadge], "Count on app icon" in Settings,
 * Notifications). Launchers take their badges from notifications, so the count is one silent
 * notification on the `today_count` channel that carries the number ([NotificationCompat.Builder.setNumber]).
 * The user may swipe it away; it comes back when the number changes.
 *
 * [refresh] runs with every widget refresh ([com.rendyhd.vicu.widget.TaskWidgetWorker]: every change
 * of the stored tasks, the periodic refresh, a reboot) and at the next local midnight, through an
 * alarm that asks for a widget refresh ([TodayCountReceiver]).
 */
class TodayCountNotification(
    private val context: Context,
    private val count: AppIconCount,
) {

    suspend fun refresh() {
        val plan = count.plan()
        show(plan.count)
        val midnight = plan.midnight
        if (midnight == null) cancelMidnight() else scheduleMidnight(midnight)
    }

    private fun show(count: Int) {
        val manager = NotificationManagerCompat.from(context)
        val step = TodayBadge.androidStep(count, showing = showingNumber(), dismissed = dismissedNumber())
        when (step) {
            TodayBadge.AndroidStep.KEEP -> Unit
            TodayBadge.AndroidStep.CANCEL -> {
                manager.cancel(NOTIFICATION_ID)
                rememberDismissed(null)
            }
            TodayBadge.AndroidStep.POST -> {
                rememberDismissed(null)
                try {
                    manager.notify(NOTIFICATION_ID, build(count))
                } catch (e: SecurityException) {
                    Log.w(TAG, "Missing POST_NOTIFICATIONS permission", e)
                }
            }
        }
    }

    private fun build(count: Int) = NotificationCompat.Builder(context, NotificationChannelManager.CHANNEL_TODAY_COUNT)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(TodayBadge.title(count))
        .setNumber(count)
        .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setOngoing(false)
        .setShowWhen(false)
        .setContentIntent(openToday())
        .setDeleteIntent(dismissed(count))
        .build()

    private fun openToday(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putViewTarget(ViewTarget.Today)
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dismissed(count: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_DISMISSED,
        Intent(context, TodayCountReceiver::class.java)
            .setAction(TodayCountReceiver.ACTION_DISMISSED)
            .putExtra(TodayCountReceiver.EXTRA_COUNT, count),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The number on the notification on screen; null when there is none. */
    private fun showingNumber(): Int? = try {
        context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.firstOrNull { it.id == NOTIFICATION_ID && it.tag == null }
            ?.notification
            ?.number
    } catch (e: Exception) {
        Log.w(TAG, "Active notifications not readable", e)
        null
    }

    private fun dismissedNumber(): Int? =
        prefs(context).getInt(KEY_DISMISSED, NONE).takeIf { it != NONE }

    private fun rememberDismissed(count: Int?) = rememberDismissed(context, count)

    /** One alarm at the next local midnight, replaced on every refresh; inexact and without waking the device. */
    private fun scheduleMidnight(at: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC, at.toEpochMilliseconds() + MIDNIGHT_SLACK_MS, midnightIntent())
    }

    private fun cancelMidnight() {
        context.getSystemService(AlarmManager::class.java)?.cancel(midnightIntent())
    }

    private fun midnightIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_MIDNIGHT,
        Intent(context, TodayCountReceiver::class.java).setAction(TodayCountReceiver.ACTION_MIDNIGHT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "TodayCountNotification"
        const val NOTIFICATION_ID = 999_990
        private const val REQUEST_DISMISSED = 999_991
        private const val REQUEST_MIDNIGHT = 999_992
        private const val PREFS = "today_count"
        private const val KEY_DISMISSED = "dismissed_count"
        private const val NONE = -1

        /** A few seconds past midnight, so the new day is read. */
        private const val MIDNIGHT_SLACK_MS = 5_000L

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** The number the user swiped away (null: none), so the same number is not posted again. */
        fun rememberDismissed(context: Context, count: Int?) {
            val prefs = prefs(context)
            if (count == null && !prefs.contains(KEY_DISMISSED)) return
            prefs.edit { if (count == null) remove(KEY_DISMISSED) else putInt(KEY_DISMISSED, count) }
        }
    }
}
