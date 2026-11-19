package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class NotificationPrefs(
    val taskRemindersEnabled: Boolean = true,
    val dailySummaryEnabled: Boolean = false,
    val dailySummaryHour: Int = 8,
    val dailySummaryMinute: Int = 0,
    val soundEnabled: Boolean = true,
    // Afternoon summary
    val afternoonSummaryEnabled: Boolean = false,
    val afternoonSummaryHour: Int = 16,
    val afternoonSummaryMinute: Int = 0,
    // Per-type digest gating
    val notifyOverdueEnabled: Boolean = true,
    val notifyDueTodayEnabled: Boolean = true,
    val notifyUpcomingEnabled: Boolean = false,
    // Default per-task reminder offset (seconds before due; 0 = off, -1 = at due time)
    val defaultReminderOffset: Int = 0,
    val defaultReminderRelativeTo: String = "due_date",
    /**
     * The number of tasks on the Today list on the app icon ("Count on app icon"; off by default,
     * this device only). See `TodayBadge`.
     */
    val todayCountBadge: Boolean = false,
)

class NotificationPrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_TASK_REMINDERS_ENABLED = booleanPreferencesKey("task_reminders_enabled")
        private val KEY_DAILY_SUMMARY_ENABLED = booleanPreferencesKey("daily_summary_enabled")
        private val KEY_DAILY_SUMMARY_HOUR = intPreferencesKey("daily_summary_hour")
        private val KEY_DAILY_SUMMARY_MINUTE = intPreferencesKey("daily_summary_minute")
        private val KEY_SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        private val KEY_AFTERNOON_ENABLED = booleanPreferencesKey("afternoon_summary_enabled")
        private val KEY_AFTERNOON_HOUR = intPreferencesKey("afternoon_summary_hour")
        private val KEY_AFTERNOON_MINUTE = intPreferencesKey("afternoon_summary_minute")
        private val KEY_NOTIFY_OVERDUE = booleanPreferencesKey("notify_overdue_enabled")
        private val KEY_NOTIFY_DUE_TODAY = booleanPreferencesKey("notify_due_today_enabled")
        private val KEY_NOTIFY_UPCOMING = booleanPreferencesKey("notify_upcoming_enabled")
        private val KEY_DEFAULT_REMINDER_OFFSET = intPreferencesKey("default_reminder_offset")
        private val KEY_DEFAULT_REMINDER_RELATIVE_TO = stringPreferencesKey("default_reminder_relative_to")
        private val KEY_TODAY_COUNT_BADGE = booleanPreferencesKey("today_count_badge")
    }

    fun getPrefs(): Flow<NotificationPrefs> =
        dataStore.data.map { prefs ->
            NotificationPrefs(
                taskRemindersEnabled = prefs[KEY_TASK_REMINDERS_ENABLED] ?: true,
                dailySummaryEnabled = prefs[KEY_DAILY_SUMMARY_ENABLED] ?: false,
                dailySummaryHour = prefs[KEY_DAILY_SUMMARY_HOUR] ?: 8,
                dailySummaryMinute = prefs[KEY_DAILY_SUMMARY_MINUTE] ?: 0,
                soundEnabled = prefs[KEY_SOUND_ENABLED] ?: true,
                afternoonSummaryEnabled = prefs[KEY_AFTERNOON_ENABLED] ?: false,
                afternoonSummaryHour = prefs[KEY_AFTERNOON_HOUR] ?: 16,
                afternoonSummaryMinute = prefs[KEY_AFTERNOON_MINUTE] ?: 0,
                notifyOverdueEnabled = prefs[KEY_NOTIFY_OVERDUE] ?: true,
                notifyDueTodayEnabled = prefs[KEY_NOTIFY_DUE_TODAY] ?: true,
                notifyUpcomingEnabled = prefs[KEY_NOTIFY_UPCOMING] ?: false,
                defaultReminderOffset = prefs[KEY_DEFAULT_REMINDER_OFFSET] ?: 0,
                defaultReminderRelativeTo = prefs[KEY_DEFAULT_REMINDER_RELATIVE_TO] ?: "due_date",
                todayCountBadge = prefs[KEY_TODAY_COUNT_BADGE] ?: false,
            )
        }

    suspend fun setTaskRemindersEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_TASK_REMINDERS_ENABLED] = enabled }
    }

    suspend fun setDailySummaryEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_DAILY_SUMMARY_ENABLED] = enabled }
    }

    suspend fun setDailySummaryTime(hour: Int, minute: Int) {
        dataStore.edit {
            it[KEY_DAILY_SUMMARY_HOUR] = hour
            it[KEY_DAILY_SUMMARY_MINUTE] = minute
        }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SOUND_ENABLED] = enabled }
    }

    suspend fun setAfternoonSummaryEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_AFTERNOON_ENABLED] = enabled }
    }

    suspend fun setAfternoonSummaryTime(hour: Int, minute: Int) {
        dataStore.edit {
            it[KEY_AFTERNOON_HOUR] = hour
            it[KEY_AFTERNOON_MINUTE] = minute
        }
    }

    suspend fun setNotifyOverdueEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NOTIFY_OVERDUE] = enabled }
    }

    suspend fun setNotifyDueTodayEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NOTIFY_DUE_TODAY] = enabled }
    }

    suspend fun setNotifyUpcomingEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NOTIFY_UPCOMING] = enabled }
    }

    suspend fun setDefaultReminderOffset(seconds: Int) {
        dataStore.edit { it[KEY_DEFAULT_REMINDER_OFFSET] = seconds }
    }

    suspend fun setDefaultReminderRelativeTo(value: String) {
        dataStore.edit { it[KEY_DEFAULT_REMINDER_RELATIVE_TO] = value }
    }

    suspend fun setTodayCountBadge(enabled: Boolean) {
        dataStore.edit { it[KEY_TODAY_COUNT_BADGE] = enabled }
    }
}
