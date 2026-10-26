package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.SyncedPrefsStore
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.sync.CarrierFinder
import com.rendyhd.vicu.data.sync.CarrierRead
import com.rendyhd.vicu.data.sync.CarrierSpec
import com.rendyhd.vicu.data.sync.SyncedDocumentCodec
import com.rendyhd.vicu.data.sync.SyncedDocumentLocal
import com.rendyhd.vicu.data.sync.SyncedDocumentMessages
import com.rendyhd.vicu.data.sync.SyncedDocumentState
import com.rendyhd.vicu.data.sync.SyncedDocumentStatus
import com.rendyhd.vicu.data.sync.SyncedDocumentSync
import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.domain.model.SyncedPrefsDocument
import com.rendyhd.vicu.domain.model.SyncedPrefsLocalState
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.SyncedPrefsEnvelope
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The preferences carrier (docs/cross-app-semantics-v1.md, section 11) for [SyncedDocumentSync]. */
class SyncedPrefsCodec(private val json: Json) : SyncedDocumentCodec<SyncedPrefsDocument> {
    override val spec = CarrierSpec.PREFS
    override val carrierTitle = SyncedPrefsEnvelope.CARRIER_TITLE
    override val messages = SyncedDocumentMessages(
        updateRequired = "Update Vicu to sync your settings",
        malformed = "A damaged settings carrier was ignored; your settings were kept",
        noInbox = "Choose an Inbox project before syncing settings",
        offline = "Settings sync is offline",
        failed = "Settings sync failed",
    )

    override fun parse(description: String?): CarrierRead<SyncedPrefsDocument> {
        val parsed = SyncedPrefsEnvelope.parse(description, json)
        return CarrierRead(parsed.document, parsed.error, (parsed.version ?: 0) > SyncedPrefsEnvelope.CURRENT_VERSION)
    }

    override fun encode(document: SyncedPrefsDocument) = SyncedPrefsEnvelope.encode(document, json)
    override fun normalize(document: SyncedPrefsDocument) = SyncedPrefsEnvelope.normalize(document)
    override fun merge(local: SyncedPrefsDocument, server: SyncedPrefsDocument) = SyncedPrefsEnvelope.merge(local, server)
    override fun pruneTombstones(document: SyncedPrefsDocument, nowMs: Long) = SyncedPrefsEnvelope.pruneTombstones(document, nowMs)
    override fun hasContent(document: SyncedPrefsDocument) = document.entries.isNotEmpty()

    override fun same(left: SyncedPrefsDocument, right: SyncedPrefsDocument): Boolean =
        json.encodeToString(SyncedPrefsDocument.serializer(), SyncedPrefsEnvelope.normalize(left)) ==
            json.encodeToString(SyncedPrefsDocument.serializer(), SyncedPrefsEnvelope.normalize(right))
}

/**
 * The settings that are the same on every device of an account (docs/cross-app-semantics-v1.md,
 * section 11): one entry per setting the user chose, in the preferences carrier.
 *
 * Reading never waits for the network: [entries] is the device's copy. A change goes to that copy
 * at once, is marked unsynced and asks for a sync, which [com.rendyhd.vicu.worker.SyncEngine] runs
 * next to the custom lists. Defaults are never written, so a new device cannot overwrite anyone's
 * choice; null is "back to the default" and reaches every device.
 *
 * The Inbox: the app keeps reading it from [AuthManager] (the session), and this keeps the two in
 * step. Choosing an Inbox ([chooseInbox]) writes both; a sync that brings another device's choice
 * moves this device's Inbox to it and says so. A device still in setup (no Inbox yet) is not moved:
 * setup offers [syncedInboxProjectId] as the preselected answer instead (section 11.4).
 */
class SyncedPreferences(
    private val store: SyncedPrefsStore,
    api: VikunjaApiService,
    carrierFinder: CarrierFinder,
    private val authManager: AuthManager,
    private val platformHooks: PlatformRepositoryHooks,
    json: Json,
    /** Names the Inbox in the message after another device moved it; none in tests. */
    private val projectDao: ProjectDao? = null,
    private val messages: AppMessages? = null,
    private val time: TimeSource = SystemTimeSource,
) : SyncedEntries {
    private val _syncStatus = MutableStateFlow<SyncedDocumentStatus>(SyncedDocumentStatus.Idle)
    val syncStatus: StateFlow<SyncedDocumentStatus> = _syncStatus

    /** The value of each key the user (on any device) chose; a key without an entry is at its default. */
    override val entries: Flow<Map<String, JsonElement?>> =
        store.state.map { state -> state?.document?.entries?.mapValues { it.value.value }.orEmpty() }.distinctUntilChanged()

    /** A choice the carrier may not have yet: counted as unsynced work at sign-out. */
    val hasUnsyncedChanges: Flow<Boolean> = store.hasUnsyncedChanges

    private val engine = SyncedDocumentSync(
        codec = SyncedPrefsCodec(json),
        local = object : SyncedDocumentLocal<SyncedPrefsDocument> {
            override suspend fun load() = loadState().let {
                SyncedDocumentState(it.deviceId, it.document, it.dirty, it.carrierTaskId, it.lastSyncedAt)
            }

            override suspend fun save(state: SyncedDocumentState<SyncedPrefsDocument>) =
                store.save(SyncedPrefsLocalState(state.deviceId, state.document, state.dirty, state.carrierTaskId, state.lastSyncedAt))
        },
        api = api,
        carrierFinder = carrierFinder,
        currentUser = authManager,
        serverKey = { authManager.getVikunjaUrl().orEmpty() },
        inboxProjectId = { authManager.getInboxProjectId() },
        time = time,
        onStatus = { _syncStatus.value = it },
        onChanged = { platformHooks.updateWidgets() },
        afterMerge = { moveDeviceSettings(it) },
    )

    private fun now(): Long = time.now().toEpochMilliseconds()

    private suspend fun loadState(): SyncedPrefsLocalState =
        store.load() ?: SyncedPrefsLocalState(deviceId = randomUuid()).also { store.save(it) }

    /** Sets [key] to [value]; null resets it to the default on every device. Nothing when it already is. */
    override suspend fun set(key: String, value: JsonElement?) = setAll(mapOf(key to value))

    /** Sets every key of [values] in one change, so a sync never sends half of it. Keys already at their value are left alone. */
    override suspend fun setAll(values: Map<String, JsonElement?>) {
        val changed = engine.mutate {
            val state = loadState()
            var document = state.document
            for ((key, value) in values) {
                val current = document.entries[key]
                if (current != null && current.value == value) continue
                if (current == null && value == null) continue
                document = SyncedPrefsEnvelope.set(document, key, value, state.deviceId, now())
            }
            if (document == state.document) return@mutate false
            store.save(state.copy(document = document, dirty = true))
            true
        }
        if (changed) {
            _syncStatus.value = SyncedDocumentStatus.Pending
            platformHooks.triggerSync()
            platformHooks.updateWidgets()
        }
    }

    /** Makes [projectId] the Inbox here and on every device of the account. */
    suspend fun chooseInbox(projectId: Long) {
        authManager.onInboxProjectSelected(projectId)
        set(SyncedPrefKeys.INBOX_PROJECT_ID, JsonPrimitive(projectId))
    }

    /** The Inbox the account's other devices chose, as far as this device has synced; null when none. */
    suspend fun syncedInboxProjectId(): Long? =
        SyncedPrefsEnvelope.projectId(store.load()?.document?.entries?.get(SyncedPrefKeys.INBOX_PROJECT_ID)?.value)

    suspend fun sync(): SyncedDocumentStatus {
        val status = engine.sync()
        followSyncedInbox()
        return status
    }

    /** Forgets the device's copy (the account is gone). */
    suspend fun clearLocal() = engine.clear { store.clear() }

    /**
     * The one-time move of section 11.5: once the carrier has been read, a setting this device kept
     * before it synced is offered to it, only when the carrier has no entry for it yet. The Inbox has
     * no default, so the first device to update decides for the others.
     */
    private suspend fun moveDeviceSettings(state: SyncedDocumentState<SyncedPrefsDocument>): SyncedPrefsDocument {
        if (store.isMoved()) return state.document
        var document = state.document
        val localInbox = authManager.getInboxProjectId()?.takeIf { it > 0L }?.let { JsonPrimitive(it) }
        if (SyncedPrefsEnvelope.shouldMove(localInbox, default = null, carrierHasEntry = SyncedPrefKeys.INBOX_PROJECT_ID in document.entries)) {
            document = SyncedPrefsEnvelope.set(document, SyncedPrefKeys.INBOX_PROJECT_ID, localInbox, state.deviceId, now())
        }
        store.setMoved()
        return document
    }

    private suspend fun followSyncedInbox() {
        val synced = syncedInboxProjectId() ?: return
        // Still in setup: setup preselects the synced Inbox instead (section 11.4).
        val current = authManager.getInboxProjectId() ?: return
        if (current == synced) return
        authManager.onInboxProjectSelected(synced)
        val title = projectDao?.getByIdSync(synced)?.title
        messages?.post(if (title != null) "Your Inbox is now $title, as on your other devices" else "Your Inbox now follows your other devices")
    }
}
