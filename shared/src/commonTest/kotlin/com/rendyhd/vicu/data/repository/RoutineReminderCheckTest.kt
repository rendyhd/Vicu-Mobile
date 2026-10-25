package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.repository.RoutineRig.Companion.payload
import com.rendyhd.vicu.data.repository.RoutineRig.Companion.record
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.util.RoutineEnvelope
import com.rendyhd.vicu.util.RoutineReminderCheck
import com.rendyhd.vicu.util.RoutineScheduleEngine
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A routine reminder rings only for an occurrence nobody has done or skipped: a tick on another
 * device, or one the phone's copy is missing, keeps it quiet ([RoutineReminderCheck]).
 */
class RoutineReminderCheckTest {

    private val today = "2026-10-06"
    private val key = RoutineScheduleEngine.occurrenceKey(RoutineRig.ROUTINE_ID, today, RoutineRig.SLOT_ID)

    /**
     * The check with its real wait for the server, on a real dispatcher: the fake server answers
     * on another thread, and under virtual time the wait would end before the answer came.
     */
    private suspend fun RoutineRig.stillDue(routineId: String = RoutineRig.ROUTINE_ID): Boolean {
        val key = RoutineScheduleEngine.occurrenceKey(routineId, today, RoutineRig.SLOT_ID)
        return withContext(Dispatchers.Default) { RoutineReminderCheck(repository, prefs).stillDue(routineId, today, key) }
    }

    /** The server cannot be reached for the carrier [taskId]. */
    private fun RoutineRig.serverDown(taskId: Long) {
        server.failure = { request ->
            if (request.path == "/tasks/$taskId") HttpStatusCode.ServiceUnavailable else null
        }
    }

    @Test
    fun `an open occurrence rings`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload())

        assertTrue(rig.stillDue())
    }

    @Test
    fun `an occurrence ticked on another device stays quiet before this phone has synced`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload())
        // Ticked on the desktop this morning; the phone's copy still has it open.
        rig.server.row(100).description = RoutineEnvelope.upsert("", payload(record(today, modifiedBy = "desktop")), rig.json)

        assertFalse(rig.stillDue())
        assertEquals(OccurrenceStatus.COMPLETED, rig.cachedPayload(100).occurrences.getValue(key).status)
    }

    @Test
    fun `offline the phone's copy decides`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        rig.seedCarrier(100, payload())
        rig.seedCarrier(200, payload(record(today, routineId = "r2"), definition = RoutineRig.definition().copy(id = "r2")))
        rig.serverDown(100)
        rig.serverDown(200)

        assertTrue(rig.stillDue())
        assertFalse(rig.stillDue("r2"))
    }

    @Test
    fun `a tick made while missed days are written from an older copy keeps its reminder quiet`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn() }
        // Yesterday was missed: the day's maintenance writes it as a placeholder.
        rig.seedCarrier(100, payload(definition = RoutineRig.definition(activeFrom = "2026-10-05")))
        // Today opens and starts that maintenance with every carrier as it was; the user ticks
        // today's occurrence straight away, before it gets to this one. Its write cannot reach
        // the server and is queued.
        rig.taskDao.afterRoutineCarriersRead = {
            rig.taskDao.afterRoutineCarriersRead = {}
            rig.repository.setOccurrenceStatus(RoutineRig.ROUTINE_ID, today, RoutineRig.SLOT_ID, OccurrenceStatus.COMPLETED)
            rig.serverDown(100)
        }

        rig.repository.finalizeAndPrune()

        val cached = rig.cachedPayload(100).occurrences.values.associate { it.scheduledDate to it.status }
        assertEquals(mapOf("2026-10-05" to OccurrenceStatus.NOT_LOGGED, today to OccurrenceStatus.COMPLETED), cached)
        assertTrue(rig.pendingActionDao.hasWaitingForTask(100))
        assertFalse(rig.stillDue())
    }
}
