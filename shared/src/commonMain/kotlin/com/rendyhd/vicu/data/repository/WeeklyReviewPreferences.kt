package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * When the weekly review was last finished, a synced setting of the account
 * (docs/cross-app-semantics-v1.md, sections 11.3 and 14.7), so Ask's agenda offers it on every
 * device at the same time.
 */
class WeeklyReviewPreferences(private val synced: SyncedEntries) {

    /** The value as stored (`"YYYY-MM-DD"`), or null when it was never finished. */
    val last: Flow<String?> = synced.entries
        .map { (it[SyncedPrefKeys.WEEKLY_REVIEW_LAST] as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
        .distinctUntilChanged()

    suspend fun finish(today: LocalDate) {
        synced.set(SyncedPrefKeys.WEEKLY_REVIEW_LAST, JsonPrimitive(today.toString()))
    }
}
