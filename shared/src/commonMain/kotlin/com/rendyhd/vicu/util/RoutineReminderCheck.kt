package com.rendyhd.vicu.util

import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.domain.model.isFinished
import com.rendyhd.vicu.domain.repository.RoutineRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether a routine reminder still rings when its alarm fires (Android; iOS plans its
 * notifications ahead and runs no code then). An occurrence done or skipped stays quiet, wherever
 * it was ticked: the routine's carrier is read from the server first, so a tick made on another
 * device, or one this phone's copy is missing, counts. For a shared routine that is also a tick
 * by another person (cross-app semantics 6.9). Offline, or without an answer within
 * [serverWait], the phone's copy decides.
 */
class RoutineReminderCheck(
    private val repository: RoutineRepository,
    private val prefsStore: RoutinePrefsStore,
    private val serverWait: Duration = SERVER_WAIT,
) {
    companion object {
        /** How long a reminder waits for the server before it rings anyway. */
        val SERVER_WAIT = 8.seconds
    }

    /** True while occurrence [occurrenceKey] of [routineId] on [date] is open and this account is to be reminded of it. */
    suspend fun stillDue(routineId: String, date: String, occurrenceKey: String): Boolean {
        // An alarm left over from before routines were turned off stays quiet.
        if (!prefsStore.enabled.first()) return false
        withTimeoutOrNull(serverWait) { repository.refreshRoutine(routineId) }
        // Hidden, reminders turned off on this account, or read only for it.
        if (repository.observeReminderRoutines().first().none { it.definition.id == routineId }) return false
        val occurrence = repository.observeDay(date).first().occurrences.firstOrNull { it.key == occurrenceKey }
            ?: return false
        return !occurrence.status.isFinished
    }
}
