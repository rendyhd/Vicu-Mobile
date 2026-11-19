package com.rendyhd.vicu.ai.notes

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a page is in being read (section 4.9.2). */
@Serializable
enum class PageState { UNREAD, READING, READ, FAILED }

/** One page of open notes: what it is, its text, and its picture while it has one. */
@Serializable
data class NotesSourcePage(
    val id: String,
    val kind: PageKind,
    /** The file (or "Typed text") the page came from. */
    val name: String,
    val text: String = "",
    val imagePath: String? = null,
    val pdfPath: String? = null,
    val pdfIndex: Int? = null,
    val textLayer: String? = null,
    val state: PageState = PageState.READ,
    val error: String? = null,
) {
    /** A picture that still has to become text. */
    val needsReading: Boolean get() = kind.isImage && state != PageState.READ
}

/** A card's own choice (section 4.9.9); null fields follow the default and the filter. */
@Serializable
data class NotesChoice(val action: OwnerAction? = null, val ticked: Boolean? = null)

enum class NotesPhase { PAGES, ORGANIZING, REVIEW }

/**
 * Open notes (section 4.9.11): kept until they are added or discarded, so they survive the app
 * closing; the pictures stay in the app's own folder ([NotesFiles]) for as long.
 */
@Serializable
data class NotesSession(
    val title: String = "",
    /** The notes' date, `yyyy-mm-dd`. */
    val date: String,
    val pages: List<NotesSourcePage> = emptyList(),
    /** The plan so far, once Organize ran. */
    val plan: NotesPlan? = null,
    /** How many batches the transcript had, and how many went into the plan. */
    val batches: Int = 0,
    val batchesDone: Int = 0,
    /** The drafts the checks kept over the batches so far (the next batch numbers from here). */
    val checkedDrafts: Int = 0,
    val organizing: Boolean = false,
    val organizeError: String? = null,
    val choices: Map<String, NotesChoice> = emptyMap(),
    val filter: NotesFilter = NotesFilter.MINE_AND_WAITING,
    val agreed: Boolean = false,
    val toLog: Boolean = false,
    val reviewing: Boolean = false,
    /** The list the notes were opened from, for new tasks' default project. */
    val projectId: Long? = null,
    val listName: String? = null,
) {
    val phase: NotesPhase
        get() = when {
            organizing -> NotesPhase.ORGANIZING
            reviewing && plan != null -> NotesPhase.REVIEW
            else -> NotesPhase.PAGES
        }
}

class NotesSessionStore(private val dataStore: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _forgotten = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Sign-out or another account: the open notes name the old account's projects and people. */
    val forgotten: SharedFlow<Unit> = _forgotten.asSharedFlow()

    suspend fun forget() {
        save(null)
        _forgotten.tryEmit(Unit)
    }

    suspend fun load(): NotesSession? = dataStore.data.first()[KEY]?.let {
        runCatching { json.decodeFromString(NotesSession.serializer(), it) }.getOrNull()
    }

    suspend fun save(session: NotesSession?) {
        dataStore.edit { prefs ->
            if (session == null) prefs.remove(KEY) else prefs[KEY] = json.encodeToString(NotesSession.serializer(), session)
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("notes_session")
    }
}
