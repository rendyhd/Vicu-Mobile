package com.rendyhd.vicu.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class NotificationChannelManager(
    private val context: Context,
) {
    companion object {
        const val CHANNEL_TASK_REMINDERS = "task_reminders"
        const val CHANNEL_DAILY_SUMMARY = "daily_summary"
        const val CHANNEL_ROUTINES = "routine_reminders"
        const val CHANNEL_PLANNER = "planner_nudge"
        const val CHANNEL_TODAY_COUNT = "today_count"
    }

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val taskReminders = NotificationChannel(
            CHANNEL_TASK_REMINDERS,
            "Task Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifications for task reminders"
        }

        val dailySummary = NotificationChannel(
            CHANNEL_DAILY_SUMMARY,
            "Daily Summary",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Daily task summary digest"
        }

        val routines = NotificationChannel(
            CHANNEL_ROUTINES,
            "Routine Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Reminders for your routines"
        }

        val planner = NotificationChannel(
            CHANNEL_PLANNER,
            "Plan tomorrow",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "The evening nudge to plan tomorrow"
        }

        // The count on the app icon (TodayCountNotification): launchers take badges from
        // notifications, so this one is silent and does not peek, but shows its badge.
        val todayCount = NotificationChannel(
            CHANNEL_TODAY_COUNT,
            "Today count",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "The number of tasks due today on the app icon"
            setShowBadge(true)
        }

        manager.createNotificationChannels(listOf(taskReminders, dailySummary, routines, planner, todayCount))
    }
}
