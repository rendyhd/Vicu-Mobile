package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.domain.model.TaskOrder
import com.rendyhd.vicu.domain.model.TaskOrderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The synced settings as keys and values: what [SyncedPreferences] offers the typed views on it. */
interface SyncedEntries {
    /** The value of each key a user chose; a key without an entry is at its default. */
    val entries: Flow<Map<String, JsonElement?>>

    /** Sets [key]; null puts it back to its default on every device. */
    suspend fun set(key: String, value: JsonElement?)

    /** Sets every key of [values] as one change (one sync), as [set] would one by one. */
    suspend fun setAll(values: Map<String, JsonElement?>) {
        for ((key, value) in values) set(key, value)
    }
}

/**
 * The task order (docs/cross-app-semantics-v1.md, section 12) as synced settings: `task_order` for
 * every project and `task_order.project.<id>` for one. Choosing the default order writes no value,
 * so it reaches the other devices as a reset; a project's "Default" makes it follow its parent again.
 */
class TaskOrderPreferences(private val synced: SyncedEntries) {

    val settings: Flow<TaskOrderSettings> =
        synced.entries.map { TaskOrderSettings.fromEntries(it) }.distinctUntilChanged()

    suspend fun setGlobalOrder(order: TaskOrder) {
        synced.set(SyncedPrefKeys.TASK_ORDER, order.takeUnless { it == TaskOrder.DEFAULT }?.let { JsonPrimitive(it.key) })
    }

    /** Gives [projectId] its own [order], or with null makes it follow its parent or the global one again. */
    suspend fun setProjectOrder(projectId: Long, order: TaskOrder?) {
        synced.set(SyncedPrefKeys.taskOrderOf(projectId), order?.let { JsonPrimitive(it.key) })
    }

    /** Every project follows the global order again. */
    suspend fun clearProjectOverrides() {
        synced.entries.first()
            .filter { (key, value) -> value != null && SyncedPrefKeys.taskOrderProject(key) != null }
            .keys
            .forEach { synced.set(it, null) }
    }
}
