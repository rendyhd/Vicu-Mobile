package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.RoutineDraft
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutinePersonalSettings
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class RoutineParseIssue(
    val taskId: Long,
    val title: String,
    val message: String,
    /** A newer version of Vicu wrote it: nothing is damaged, this version cannot read it. */
    val updateRequired: Boolean = false,
)

/** The routine history as CSV, and whether it covers all of it. */
data class RoutineCsvExport(
    val csv: String,
    /** False when archived history could not be read (offline), so only the routines' own history is in [csv]. */
    val complete: Boolean = true,
)

interface RoutineRepository {
    /** The routines this account reads and has not hidden: its own and the ones shared with it. */
    fun observeActive(): Flow<List<Routine>>
    fun observeArchived(): Flow<List<Routine>>
    fun observeRoutine(routineId: String): Flow<Routine?>
    fun observeDay(date: String): Flow<RoutineDay>
    fun observeHistory(routineId: String): Flow<List<RoutineOccurrenceRecord>>
    fun observeIssues(): Flow<List<RoutineParseIssue>>

    /**
     * A problem with the history archive that did not stop a change from being saved (for
     * example the server refused an archive part), or null. Being offline is not a problem: the
     * history stays in the routine and moves on a later change.
     */
    val archiveWarning: Flow<String?> get() = flowOf(null)

    suspend fun create(draft: RoutineDraft): NetworkResult<Routine>
    suspend fun update(routineId: String, draft: RoutineDraft): NetworkResult<Routine>
    suspend fun setOccurrenceStatus(
        routineId: String,
        date: String,
        slotId: String,
        status: OccurrenceStatus,
        note: String = "",
    ): NetworkResult<Routine>
    suspend fun archive(routineId: String, archived: Boolean): NetworkResult<Routine>
    suspend fun deletePermanently(routineId: String): NetworkResult<Unit>
    suspend fun finalizeAndPrune(): NetworkResult<Unit>
    suspend fun exportCsv(): String

    /** Like [exportCsv], but says when archived history could not be included. */
    suspend fun exportCsvWithStatus(): RoutineCsvExport = RoutineCsvExport(exportCsv())

    /**
     * Uploads history that versions before 1.9 kept only on this phone into archive parts on the
     * server, merged with the parts already there, then forgets the local copy. Safe to run again
     * at any time: it does nothing once the phone-only table is empty, and a run that was cut
     * short or failed offline continues where it stopped. With [carriersAuthoritative] (the
     * carrier cache was just refreshed from the server) history of a routine that no longer
     * exists is dropped instead of kept for later.
     */
    suspend fun migrateLocalArchive(carriersAuthoritative: Boolean = false): NetworkResult<Unit> =
        NetworkResult.Success(Unit)

    // --- Shared routines (docs/cross-app-semantics-v1.md, section 6.9) ---------------------------

    /** Shared routines this account hid; the routine settings list them so they can be shown again. */
    fun observeHidden(): Flow<List<Routine>> = flowOf(emptyList())

    /**
     * The routines to remind this account of: active, not hidden, reminders not turned off on this
     * account, and in a project it can write to (a read-only member gets none).
     */
    fun observeReminderRoutines(): Flow<List<Routine>> = observeActive()

    /**
     * This account's own choices about routines shared with it (reminders off, hidden), by routine
     * id. Empty for its own routines unless it set something while they were someone else's.
     */
    fun observePersonalSettings(): Flow<RoutinePersonalSettings> = flowOf(RoutinePersonalSettings())

    /**
     * Shares the routine through [projectId], or moves a shared one there: its archive parts move
     * first, then the carrier moves and gets the flag in one conditional write. Owner only, online
     * only.
     */
    suspend fun share(routineId: String, projectId: Long): NetworkResult<Routine> =
        NetworkResult.Error("Sharing routines is not available")

    /** Makes the routine private again and moves it back to the Inbox. Owner only, online only. */
    suspend fun stopSharing(routineId: String): NetworkResult<Routine> =
        NetworkResult.Error("Sharing routines is not available")

    /** Turns this account's reminders of a shared routine on or off, on every device of the account. */
    suspend fun setRemindersEnabled(routineId: String, enabled: Boolean) {}

    /** Hides a shared routine on this account, or shows it again. */
    suspend fun setHidden(routineId: String, hidden: Boolean) {}

    /**
     * Reads the routine's carrier from the server into the cache (before one of its reminders
     * rings). Returns false when the server could not be asked; the cache stays then.
     */
    suspend fun refreshRoutine(routineId: String): Boolean = false
}
