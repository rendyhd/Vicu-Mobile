package com.rendyhd.vicu.widget

import com.rendyhd.vicu.ui.navigation.ViewTarget
import com.rendyhd.vicu.domain.model.TaskOrder
import kotlinx.serialization.Serializable

@Serializable
enum class WidgetViewType {
    TODAY, INBOX, UPCOMING, ANYTIME, PROJECT, CUSTOM_LIST, ALL_TASKS
}

/**
 * The screen a tap on the widget title opens. Null when the widget names a project that has no
 * usable id; the title then just opens the app.
 */
fun WidgetViewType.toViewTarget(viewId: String): ViewTarget? = when (this) {
    WidgetViewType.TODAY -> ViewTarget.Today
    WidgetViewType.INBOX -> ViewTarget.Inbox
    WidgetViewType.UPCOMING -> ViewTarget.Upcoming
    WidgetViewType.ANYTIME -> ViewTarget.Anytime
    WidgetViewType.ALL_TASKS -> null
    WidgetViewType.PROJECT -> viewId.toLongOrNull()?.let { ViewTarget.Project(it) }
    WidgetViewType.CUSTOM_LIST -> viewId.takeIf { it.isNotBlank() }?.let { ViewTarget.CustomList(it) }
}

@Serializable
data class WidgetTaskItem(
    val id: Long,
    val title: String,
    val projectName: String = "",
    val dueDate: String = "",
    val priority: Int = 0,
    val done: Boolean = false,
    val projectId: Long = 0L,
)

@Serializable
data class TaskWidgetState(
    val viewType: WidgetViewType = WidgetViewType.TODAY,
    val viewId: String = "",
    val viewName: String = "Today",
    val tasks: List<WidgetTaskItem> = emptyList(),
    val totalCount: Int = 0,
    val lastUpdated: String = "",
    val error: String? = null,
    val smartAdd: Boolean = true,
    val contextNav: Boolean = true,
    val voiceButton: Boolean = true,
    val addToProjectId: Long = 0L, // for custom lists: which project the + button targets
    val transparentBackground: Boolean = false,
    val groupByProject: Boolean = false,
)

@Serializable
data class WidgetConfig(
    val viewType: WidgetViewType = WidgetViewType.TODAY,
    val viewId: String = "",
    val viewName: String = "Today",
    val transparentBackground: Boolean = false,
    val groupByProject: Boolean = false,
    val projectOrder: List<Long> = emptyList(),
    val taskOrderKey: String? = null,
)

/** Saved project IDs go first. Newly discovered projects follow in title order. */
fun WidgetConfig.orderedProjectIds(projectNames: Map<Long, String>): List<Long> {
    val alphabetical = projectNames.keys.sortedWith(compareBy({ projectNames.getValue(it).lowercase() }, { it }))
    return (projectOrder + alphabetical).distinct().filter { it in projectNames }
}

val WidgetConfig.taskOrder: TaskOrder? get() = TaskOrder.fromKey(taskOrderKey)

fun TaskWidgetState.projectGroups(): Collection<List<WidgetTaskItem>> =
    tasks.groupBy { it.projectId }.values

/** Keep cached rows when an edit does not change which tasks qualify. */
fun TaskWidgetState.withConfig(config: WidgetConfig): TaskWidgetState {
    val viewChanged = viewType != config.viewType || viewId != config.viewId
    val reloadTasks = viewChanged || groupByProject != config.groupByProject
    return copy(
        viewType = config.viewType,
        viewId = config.viewId,
        viewName = config.viewName,
        transparentBackground = config.transparentBackground,
        groupByProject = config.groupByProject,
        tasks = if (reloadTasks) emptyList() else tasks,
        totalCount = if (reloadTasks) 0 else totalCount,
        lastUpdated = if (reloadTasks) "" else lastUpdated,
        error = if (viewChanged) null else error,
        addToProjectId = if (viewChanged) 0L else addToProjectId,
    )
}
