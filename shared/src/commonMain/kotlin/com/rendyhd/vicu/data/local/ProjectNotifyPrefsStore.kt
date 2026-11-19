package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.rendyhd.vicu.util.ProjectNotifyMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The notification mode chosen for each project on this device (a device setting, not synced:
 * people who share a project each pick their own). Entries are `<projectId>=<MODE>`; a project
 * without one rings for everything. The ids are the account's, so a sign-out clears them.
 */
class ProjectNotifyPrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    private companion object {
        val KEY_MODES = stringSetPreferencesKey("project_notify_modes")
    }

    val chosen: Flow<Map<Long, ProjectNotifyMode>> =
        dataStore.data.map { prefs -> parse(prefs[KEY_MODES].orEmpty()) }

    suspend fun chosenNow(): Map<Long, ProjectNotifyMode> = chosen.first()

    suspend fun setMode(projectId: Long, mode: ProjectNotifyMode) {
        val prefix = "$projectId="
        dataStore.edit { prefs ->
            val rest = prefs[KEY_MODES].orEmpty().filterNot { it.startsWith(prefix) }
            prefs[KEY_MODES] = rest.toSet() + (prefix + mode.name)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY_MODES) }
    }

    private fun parse(entries: Set<String>): Map<Long, ProjectNotifyMode> =
        entries.mapNotNull { entry ->
            val parts = entry.split('=', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            val mode = ProjectNotifyMode.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            id to mode
        }.toMap()
}
