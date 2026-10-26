package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.util.ProjectCategories
import com.rendyhd.vicu.util.ProjectCategoryState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement

/**
 * The project categories (docs/cross-app-semantics-v1.md, section 13) as synced settings. Reading
 * gives the usable categories and the projects in them; a change is planned by [ProjectCategories]
 * from the entries as they are now and written as one change.
 */
class ProjectCategoryPreferences(private val synced: SyncedEntries) {

    val state: Flow<ProjectCategoryState> =
        synced.entries.map { ProjectCategories.state(it) }.distinctUntilChanged()

    /** The synced entries as they are now. */
    suspend fun entries(): Map<String, JsonElement?> = synced.entries.first()

    /** Plans a change from the entries as they are now and writes it; answers what it wrote. */
    suspend fun change(plan: suspend (Map<String, JsonElement?>) -> Map<String, JsonElement?>): Map<String, JsonElement?> {
        val writes = plan(entries())
        if (writes.isNotEmpty()) synced.setAll(writes)
        return writes
    }

    suspend fun write(values: Map<String, JsonElement?>) {
        if (values.isNotEmpty()) synced.setAll(values)
    }
}
