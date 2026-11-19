package com.rendyhd.vicu.ui.components.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.repository.ProjectNotificationSettings
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.location.LocationReminderCoordinator
import com.rendyhd.vicu.ui.navigation.buildProjectTree
import com.rendyhd.vicu.ui.navigation.visibleProjectRows
import com.rendyhd.vicu.util.ProjectNotifyMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One project in the "Projects" part of the Notifications tab. */
data class ProjectNotifyRow(
    val project: Project,
    val depth: Int,
    val mode: ProjectNotifyMode,
    /** False while the mode is the default for this project (nobody picked one on this device). */
    val chosen: Boolean,
)

/** Feeds the per-project notification modes and applies a change to every reminder at once. */
class ProjectNotificationsViewModel(
    private val settings: ProjectNotificationSettings,
    projectRepository: ProjectRepository,
    private val hooks: PlatformRepositoryHooks,
    private val locationReminders: LocationReminderCoordinator,
) : ViewModel() {

    val rows: StateFlow<List<ProjectNotifyRow>> =
        combine(projectRepository.getAll(), settings.entries) { projects, entries ->
            visibleProjectRows(buildProjectTree(projects), emptySet()).map { row ->
                val entry = entries[row.project.id]
                ProjectNotifyRow(
                    project = row.project,
                    depth = row.depth,
                    mode = entry?.mode ?: ProjectNotifyMode.ALL,
                    chosen = entry?.chosen ?: false,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setMode(projectId: Long, mode: ProjectNotifyMode) {
        viewModelScope.launch {
            settings.setMode(projectId, mode)
            // Alarms already set for the project follow the new mode, and so do watched places.
            hooks.rescheduleAlarms()
            locationReminders.requestReplan()
        }
    }
}
