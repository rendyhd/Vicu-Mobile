package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A saved filter on the server as the project list names it (cross-app semantics 3.7), with its
 * pseudo-project's `updated`, which moves with every change of the filter.
 */
@Serializable
data class SavedFilterRef(val id: Long, val title: String, val updated: String = "")

/**
 * What this device knows about the account's Vikunja saved filters (cross-app semantics 3.7): the
 * filters the last project refresh saw, whether this device's first connect has imported them,
 * the filters the person said Not now to, the imported lists whose first-open notice was closed,
 * and which changes of imported filters were read, found changed or put off ("Changed in
 * Vikunja"). All per device and per account: sign-out and an account switch clear it.
 */
class SavedFilterStore(private val dataStore: DataStore<Preferences>) {
    private companion object {
        val KEY_KNOWN = stringPreferencesKey("known_filters")
        val KEY_FIRST_CONNECT = booleanPreferencesKey("first_connect_done")
        val KEY_DISMISSED = stringPreferencesKey("dismissed_offer")
        val KEY_NOTICE_CLOSED = stringPreferencesKey("notice_closed")
        val KEY_CHECKED = stringPreferencesKey("checked_updates")
        val KEY_CHANGED = stringPreferencesKey("changed_updates")
        val KEY_CHANGED_DISMISSED = stringPreferencesKey("changed_dismissed")
        val json = Json { ignoreUnknownKeys = true }
        val refs = ListSerializer(SavedFilterRef.serializer())
        val ids = ListSerializer(Long.serializer())
        val strings = ListSerializer(String.serializer())
        val updates = MapSerializer(Long.serializer(), String.serializer())
    }

    private fun decodeUpdates(raw: String?): Map<Long, String> =
        raw?.let { runCatching { json.decodeFromString(updates, it) }.getOrNull() }.orEmpty()

    private suspend fun putUpdate(key: Preferences.Key<String>, filterId: Long, updated: String) {
        dataStore.edit { prefs -> prefs[key] = json.encodeToString(updates, decodeUpdates(prefs[key]) + (filterId to updated)) }
    }

    /** Per imported filter, the `updated` this device last read it at. */
    val checked: Flow<Map<Long, String>> = dataStore.data.map { decodeUpdates(it[KEY_CHECKED]) }

    /** Per imported filter, the `updated` at which a read found it changed in Vikunja. */
    val changed: Flow<Map<Long, String>> = dataStore.data.map { decodeUpdates(it[KEY_CHANGED]) }

    /** Per imported filter, the change the person put off with Not now. */
    val changedDismissed: Flow<Map<Long, String>> = dataStore.data.map { decodeUpdates(it[KEY_CHANGED_DISMISSED]) }

    suspend fun markChecked(filterId: Long, updated: String) = putUpdate(KEY_CHECKED, filterId, updated)

    suspend fun markChanged(filterId: Long, updated: String) = putUpdate(KEY_CHANGED, filterId, updated)

    suspend fun dismissChanged(changes: Map<Long, String>) {
        dataStore.edit { prefs -> prefs[KEY_CHANGED_DISMISSED] = json.encodeToString(updates, decodeUpdates(prefs[KEY_CHANGED_DISMISSED]) + changes) }
    }

    private fun <T> decode(raw: String?, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    /** The saved filters the last project refresh found, in the server's order. */
    val known: Flow<List<SavedFilterRef>> = dataStore.data.map { decode(it[KEY_KNOWN], refs) }

    /** Filters the person put off with Not now; a new filter brings the offer back. */
    val dismissed: Flow<Set<Long>> = dataStore.data.map { decode(it[KEY_DISMISSED], ids).toSet() }

    /** Imported lists whose first-open notice was closed with Got it. */
    val noticeClosed: Flow<Set<String>> = dataStore.data.map { decode(it[KEY_NOTICE_CLOSED], strings).toSet() }

    suspend fun setKnown(filters: List<SavedFilterRef>) {
        dataStore.edit { it[KEY_KNOWN] = json.encodeToString(refs, filters) }
    }

    suspend fun isFirstConnectDone(): Boolean = dataStore.data.first()[KEY_FIRST_CONNECT] == true

    suspend fun markFirstConnectDone() {
        dataStore.edit { it[KEY_FIRST_CONNECT] = true }
    }

    suspend fun dismiss(filterIds: Collection<Long>) {
        dataStore.edit { prefs ->
            val now = decode(prefs[KEY_DISMISSED], ids).toSet() + filterIds
            prefs[KEY_DISMISSED] = json.encodeToString(ids, now.sorted())
        }
    }

    suspend fun closeNotice(listId: String) {
        dataStore.edit { prefs ->
            val now = decode(prefs[KEY_NOTICE_CLOSED], strings).toSet() + listId
            prefs[KEY_NOTICE_CLOSED] = json.encodeToString(strings, now.sorted())
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
