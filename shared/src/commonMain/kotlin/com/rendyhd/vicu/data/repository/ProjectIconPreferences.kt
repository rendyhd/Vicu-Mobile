package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A project's icon, a synced setting of the account (docs/cross-app-semantics-v1.md, section 11.3):
 * `project_icon.<id>` is `"none"` for a project drawn without its folder (its name may carry an
 * emoji instead); no entry is the folder. Any other value acts as the folder and is kept.
 */
class ProjectIconPreferences(private val synced: SyncedEntries) {

    /** The projects drawn without an icon. */
    val withoutIcon: Flow<Set<Long>> = synced.entries
        .map { entries ->
            entries.mapNotNullTo(mutableSetOf()) { (key, value) ->
                val id = SyncedPrefKeys.projectIconProject(key) ?: return@mapNotNullTo null
                id.takeIf { (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull == SyncedPrefKeys.ICON_NONE }
            }
        }
        .distinctUntilChanged()

    suspend fun setShowIcon(projectId: Long, show: Boolean) {
        synced.set(SyncedPrefKeys.projectIconOf(projectId), if (show) null else JsonPrimitive(SyncedPrefKeys.ICON_NONE))
    }
}
