package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.util.CurrentTimesOfDay
import com.rendyhd.vicu.util.DayChoiceId
import com.rendyhd.vicu.util.MoveStep
import com.rendyhd.vicu.util.TimeOfDay
import com.rendyhd.vicu.util.WhenChoiceSet
import com.rendyhd.vicu.util.WhenChoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The When choices (docs/cross-app-semantics-v1.md, sections 11.3 and 23): the times of day, the
 * day choices and the Move by steps, three synced settings. Choosing a default writes null, so the
 * reset reaches the other devices; a value outside the rules is never written.
 */
class WhenChoicesPreferences(private val synced: SyncedEntries) {

    val choices: Flow<WhenChoiceSet> = synced.entries
        .map { entries ->
            WhenChoiceSet(
                times = WhenChoices.readTimes(entries[SyncedPrefKeys.WHEN_TIMES]),
                days = WhenChoices.readDays(entries[SyncedPrefKeys.WHEN_DAYS]),
                moveBy = WhenChoices.readMoveBy(entries[SyncedPrefKeys.WHEN_MOVE_BY]),
            )
        }
        .distinctUntilChanged()

    /** Keeps [CurrentTimesOfDay] (what the quick-add parser reads) in step with the setting. */
    fun follow(scope: CoroutineScope) {
        scope.launch { choices.collect { CurrentTimesOfDay.value = it.times } }
    }

    /** Sets the times of day; false when they break the rules of section 23.1. */
    suspend fun setTimes(times: List<TimeOfDay>): Boolean {
        if (!WhenChoices.isValidTimes(times)) return false
        synced.set(SyncedPrefKeys.WHEN_TIMES, WhenChoices.writeTimes(times))
        return true
    }

    /** Sets the day choices; false when there are too many or one is there twice. */
    suspend fun setDays(days: List<DayChoiceId>): Boolean {
        if (days.size > WhenChoices.MAX_DAYS || days.toSet().size != days.size) return false
        synced.set(SyncedPrefKeys.WHEN_DAYS, WhenChoices.writeDays(days))
        return true
    }

    /** Sets the Move by steps; false when there are too many or one is there twice. */
    suspend fun setMoveBy(steps: List<MoveStep>): Boolean {
        if (steps.size > WhenChoices.MAX_MOVE_BY || steps.toSet().size != steps.size) return false
        synced.set(SyncedPrefKeys.WHEN_MOVE_BY, WhenChoices.writeMoveBy(steps))
        return true
    }

    /** Back to the defaults of all three. */
    suspend fun reset() {
        synced.set(SyncedPrefKeys.WHEN_TIMES, null)
        synced.set(SyncedPrefKeys.WHEN_DAYS, null)
        synced.set(SyncedPrefKeys.WHEN_MOVE_BY, null)
    }
}
