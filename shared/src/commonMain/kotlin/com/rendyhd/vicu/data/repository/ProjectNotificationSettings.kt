package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.TokenStorage
import com.rendyhd.vicu.data.local.ProjectNotifyPrefsStore
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.util.ProjectNotifyMode
import com.rendyhd.vicu.util.ReminderGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The mode of one project as Settings shows it: what applies now, and whether it was chosen. */
data class ProjectNotifyEntry(val mode: ProjectNotifyMode, val chosen: Boolean)

/**
 * Per-project notification modes: what the user chose on this device, else
 * [ProjectNotifyMode.ALL]. Reads only the database and the stores (no repository), so the alarm
 * schedulers can depend on it without a cycle.
 */
class ProjectNotificationSettings(
    private val store: ProjectNotifyPrefsStore,
    private val projectDao: ProjectDao,
    private val tokenStorage: TokenStorage,
) {
    /** The mode of every (not archived) project, for the Notifications tab. */
    val entries: Flow<Map<Long, ProjectNotifyEntry>> =
        combine(projectDao.getAll(), store.chosen) { projects, chosen ->
            projects.associate { project ->
                val picked = chosen[project.id]
                project.id to ProjectNotifyEntry(picked ?: ProjectNotifyMode.ALL, chosen = picked != null)
            }
        }

    /** A snapshot for one round of planning or one fired alarm. */
    suspend fun gate(): ReminderGate = ReminderGate(store.chosenNow(), tokenStorage.getUserId())

    suspend fun setMode(projectId: Long, mode: ProjectNotifyMode) = store.setMode(projectId, mode)
}
