package com.rendyhd.vicu.widget

import android.content.Context
import androidx.work.WorkManager
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Ticks made on the routine widget that [RoutineWidgetActionWorker] has not saved yet. A tap hides
 * the row at once, but a refresh rebuilds the widget from the database, which only has the tick
 * once its worker has written it: a refresh in between (another tick's worker finishing, a sync, the
 * widget's own update) brought the row back until the tick was saved. So each tick rides on its work
 * request as a tag, a refresh lays the ticks of work still queued or running over the database, and
 * a tick leaves on its own when its work ends: saved, refused or cancelled.
 */
internal object RoutineWidgetPendingTicks {
    /** Carried by every widget action, to find them. */
    const val TAG = "routine_widget_action"

    /**
     * How long a tick is laid over the database. Saving one takes seconds; this only keeps a worker
     * that fails and retries for hours from keeping the row hidden all that time.
     */
    const val MAX_AGE_MILLIS = 10 * 60 * 1000L

    private const val TICK_PREFIX = "routine_widget_tick:"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Tick(
        val routineId: String,
        val date: String,
        val slotId: String,
        val status: OccurrenceStatus,
        /** When it was tapped, in epoch milliseconds. */
        val at: Long,
    )

    fun tagFor(tick: Tick): String = TICK_PREFIX + json.encodeToString(Tick.serializer(), tick)

    fun tickIn(tags: Set<String>): Tick? = tags.firstNotNullOfOrNull { tag ->
        if (!tag.startsWith(TICK_PREFIX)) return@firstNotNullOfOrNull null
        runCatching { json.decodeFromString(Tick.serializer(), tag.removePrefix(TICK_PREFIX)) }.getOrNull()
    }

    /** [state] with the [ticks] younger than [MAX_AGE_MILLIS] laid over it; the latest tap wins. */
    fun applyTo(state: RoutineWidgetState, ticks: List<Tick>, nowMillis: Long): RoutineWidgetState =
        ticks.filter { nowMillis - it.at < MAX_AGE_MILLIS }
            .sortedBy { it.at }
            .fold(state) { current, tick -> current.withStatus(tick.routineId, tick.date, tick.slotId, tick.status) }

    /** The ticks whose work has not ended, leaving out the work [ignoringWork] and the routine [ignoringRoutine]. */
    suspend fun read(context: Context, ignoringWork: UUID? = null, ignoringRoutine: String? = null): List<Tick> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).first()
            .filter { !it.state.isFinished && it.id != ignoringWork }
            .mapNotNull { tickIn(it.tags) }
            .filter { it.routineId != ignoringRoutine }
}
