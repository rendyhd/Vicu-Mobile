package com.rendyhd.vicu.ui.components.task

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.rendyhd.vicu.data.local.SwipeAction
import com.rendyhd.vicu.ui.theme.LocalVicuColors
import com.rendyhd.vicu.ui.theme.VicuColorRole

/** How a swipe action is named and drawn: in Settings, Gestures and behind a row being swiped. */
internal object SwipeActions {
    /** The choice in Settings. */
    fun name(action: SwipeAction): String = when (action) {
        SwipeAction.COMPLETE -> "Complete"
        SwipeAction.CHOOSE_WHEN -> "Choose when"
        SwipeAction.DUE_TODAY -> "Due today"
        SwipeAction.DUE_TOMORROW -> "Due tomorrow"
        SwipeAction.URGENT -> "Mark urgent"
        SwipeAction.MOVE -> "Move to project"
        SwipeAction.DELETE -> "Delete"
        SwipeAction.NONE -> "Nothing"
    }

    /** The word beside the icon behind the row. */
    fun word(action: SwipeAction): String = when (action) {
        SwipeAction.COMPLETE -> "Complete"
        SwipeAction.CHOOSE_WHEN -> "Schedule"
        SwipeAction.DUE_TODAY -> "Today"
        SwipeAction.DUE_TOMORROW -> "Tomorrow"
        SwipeAction.URGENT -> "Urgent"
        SwipeAction.MOVE -> "Move"
        SwipeAction.DELETE -> "Delete"
        SwipeAction.NONE -> ""
    }

    /** What the swipe does, as the Gestures tab says it. */
    fun description(action: SwipeAction): String = when (action) {
        SwipeAction.COMPLETE -> "Complete the task"
        SwipeAction.CHOOSE_WHEN -> "Open the When sheet to choose a day and time"
        SwipeAction.DUE_TODAY -> "Make the task due today"
        SwipeAction.DUE_TOMORROW -> "Make the task due tomorrow"
        SwipeAction.URGENT -> "Mark the task urgent (priority 4)"
        SwipeAction.MOVE -> "Choose a project to move the task to"
        SwipeAction.DELETE ->
            "Delete the task. Asks first while Confirm before deleting is on, and always for a task with subtasks"
        SwipeAction.NONE -> "Nothing: the row stays put"
    }

    fun icon(action: SwipeAction): ImageVector = when (action) {
        SwipeAction.COMPLETE -> Icons.Outlined.Check
        SwipeAction.CHOOSE_WHEN -> Icons.Outlined.CalendarMonth
        SwipeAction.DUE_TODAY -> Icons.Outlined.Today
        SwipeAction.DUE_TOMORROW -> Icons.Outlined.Event
        SwipeAction.URGENT -> Icons.Outlined.PriorityHigh
        SwipeAction.MOVE -> Icons.AutoMirrored.Outlined.DriveFileMove
        SwipeAction.DELETE -> Icons.Outlined.Delete
        SwipeAction.NONE -> Icons.Outlined.Block
    }

    /**
     * The colour behind the row: Complete has its own, Delete is the scheme's error role, and every
     * other action changes the task without ending it, as scheduling does.
     */
    @Composable
    fun role(action: SwipeAction): VicuColorRole {
        val colors = LocalVicuColors.current
        val scheme = MaterialTheme.colorScheme
        return when (action) {
            SwipeAction.COMPLETE -> colors.swipeComplete
            SwipeAction.DELETE -> VicuColorRole(
                color = scheme.error,
                onColor = scheme.onError,
                container = scheme.errorContainer,
                onContainer = scheme.onErrorContainer,
            )
            else -> colors.swipeSchedule
        }
    }
}

/**
 * Asks before a task is deleted. A task with subtasks offers to keep them as standalone tasks;
 * [onDelete] gets whether the subtasks go too.
 */
@Composable
fun DeleteTaskDialog(
    descendantCount: Int,
    onDelete: (deleteSubtasks: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val hasDescendants = descendantCount > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasDescendants) "Delete task and subtasks?" else "Delete task?") },
        text = {
            Text(
                if (hasDescendants) {
                    "This task has $descendantCount ${if (descendantCount == 1) "subtask" else "subtasks"}. " +
                        "Delete them too, or keep them as standalone tasks."
                } else {
                    "This action cannot be undone."
                },
            )
        },
        confirmButton = {
            Row {
                if (hasDescendants) {
                    TextButton(onClick = { onDelete(false) }) {
                        Text("Keep subtasks")
                    }
                }
                TextButton(onClick = { onDelete(true) }) {
                    Text(if (hasDescendants) "Delete all" else "Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
