package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.util.TodayRoutines
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Where routines show on this device. Both are on unless the user turned them off. */
data class RoutineVisibility(
    /** The routines feature: drawer entry, Today section and reminders. */
    val enabled: Boolean = true,
    /** Whether Today lists the routines still open today (only while [enabled]). */
    val showInToday: Boolean = true,
    /** How many due-now routines Today shows (cross-app semantics 6.10); [TodayRoutines.ALL] for all. */
    val todayLimit: Int = TodayRoutines.DEFAULT_LIMIT,
    /** Today's routine section is folded to its header. */
    val todayFolded: Boolean = false,
) {
    val inToday: Boolean get() = enabled && showInToday
}

class RoutinePrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("routine_device_id")
        private val KEY_REMINDERS_ENABLED = booleanPreferencesKey("routine_reminders_enabled")
        private val KEY_ENABLED = booleanPreferencesKey("routines_enabled")
        private val KEY_SHOW_IN_TODAY = booleanPreferencesKey("routines_show_in_today")
        private val KEY_TODAY_LIMIT = intPreferencesKey("routines_today_limit")
        private val KEY_TODAY_FOLDED = booleanPreferencesKey("routines_today_folded")
    }

    val remindersEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_REMINDERS_ENABLED] ?: true }

    val visibility: Flow<RoutineVisibility> = dataStore.data.map {
        RoutineVisibility(
            enabled = it[KEY_ENABLED] ?: true,
            showInToday = it[KEY_SHOW_IN_TODAY] ?: true,
            todayLimit = it[KEY_TODAY_LIMIT]?.takeIf { limit -> limit in TodayRoutines.LIMIT_CHOICES } ?: TodayRoutines.DEFAULT_LIMIT,
            todayFolded = it[KEY_TODAY_FOLDED] ?: false,
        )
    }.distinctUntilChanged()

    val enabled: Flow<Boolean> = visibility.map { it.enabled }.distinctUntilChanged()

    suspend fun getOrCreateDeviceId(): String {
        dataStore.data.first()[KEY_DEVICE_ID]?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = randomUuid()
        dataStore.edit { prefs ->
            if (prefs[KEY_DEVICE_ID].isNullOrBlank()) prefs[KEY_DEVICE_ID] = generated
        }
        return dataStore.data.first()[KEY_DEVICE_ID] ?: generated
    }

    suspend fun setRemindersEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_REMINDERS_ENABLED] = enabled }
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setShowInToday(show: Boolean) {
        dataStore.edit { it[KEY_SHOW_IN_TODAY] = show }
    }

    suspend fun setTodayLimit(limit: Int) {
        dataStore.edit { it[KEY_TODAY_LIMIT] = limit }
    }

    suspend fun setTodayFolded(folded: Boolean) {
        dataStore.edit { it[KEY_TODAY_FOLDED] = folded }
    }

    /**
     * Back to defaults, with a new device id for the next account's routine history. Whether
     * routines show at all is a preference of this device, like the review settings, and is kept.
     */
    suspend fun clear() {
        dataStore.edit { prefs ->
            val enabled = prefs[KEY_ENABLED]
            val showInToday = prefs[KEY_SHOW_IN_TODAY]
            val todayLimit = prefs[KEY_TODAY_LIMIT]
            val todayFolded = prefs[KEY_TODAY_FOLDED]
            prefs.clear()
            enabled?.let { prefs[KEY_ENABLED] = it }
            showInToday?.let { prefs[KEY_SHOW_IN_TODAY] = it }
            todayLimit?.let { prefs[KEY_TODAY_LIMIT] = it }
            todayFolded?.let { prefs[KEY_TODAY_FOLDED] = it }
        }
    }
}
