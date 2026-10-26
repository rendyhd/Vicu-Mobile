package com.rendyhd.vicu.ai.dictation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A transcript whose dictation failed, kept until it is added or discarded (section 4.6). */
@Serializable
data class KeptTranscript(
    val transcript: String,
    val listName: String? = null,
    val projectId: Long? = null,
    val dueDate: String? = null,
    /** Break into steps: the drafts become subtasks of this task. */
    val parentTaskId: Long? = null,
    /** A brain dump (section 4.8.6): it comes back with Retry and Edit. */
    val brainDump: Boolean = false,
)

class KeptTranscriptStore(private val dataStore: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }

    val kept: Flow<KeptTranscript?> = dataStore.data.map { prefs ->
        prefs[KEY]?.let { runCatching { json.decodeFromString(KeptTranscript.serializer(), it) }.getOrNull() }
    }

    suspend fun current(): KeptTranscript? = kept.first()

    suspend fun keep(transcript: KeptTranscript) {
        dataStore.edit { it[KEY] = json.encodeToString(KeptTranscript.serializer(), transcript) }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("kept_transcript")
    }
}
