package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.SwipeLeft
import androidx.compose.material.icons.outlined.SwipeRight
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.data.local.SwipeAction
import com.rendyhd.vicu.ui.components.task.SwipeActions

/** Shown above the two swipe settings. */
internal const val SWIPE_INTRO =
    "Choose what swiping a task does. A swipe needs a drag of half the row's width."

/**
 * What dragging does, and what a TalkBack user does instead: every row that can be dragged has
 * "Move up" and "Move down" among its actions.
 */
internal const val DRAG_GESTURE_TEXT =
    "Reorder tasks in the Inbox and in projects ordered by hand, and the projects, lists and " +
        "labels in the menu. With TalkBack, use the Move up and Move down actions of the row instead."

/**
 * A completed task stays in the list for a few seconds (struck through), whichever way it was
 * completed. No snackbar is shown for it, and none for the swipe actions.
 */
internal const val UNDO_TIP =
    "Tip: A task you complete stays in the list for a few seconds. " +
        "Tap its checkbox again to undo."

// ========== Gestures Tab ==========

@Composable
internal fun GesturesTab(
    swipeRight: SwipeAction,
    swipeLeft: SwipeAction,
    onSwipeRightChange: (SwipeAction) -> Unit,
    onSwipeLeftChange: (SwipeAction) -> Unit,
    listState: LazyListState,
) {
    // Which swipe is being chosen: true for the right one, false for the left, null for none.
    var choosingRight by rememberSaveable { mutableStateOf<Boolean?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
    ) {
        item(key = "swipes_header") {
            SectionHeader(icon = Icons.Outlined.Swipe, title = "Swipes")
        }

        item(key = "swipes_intro") {
            Text(
                text = SWIPE_INTRO,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        item(key = "swipe_right") {
            SwipeSettingRow(
                icon = Icons.Outlined.SwipeRight,
                gesture = "Swipe Right",
                action = swipeRight,
                onClick = { choosingRight = true },
            )
        }

        item(key = "swipe_left") {
            SwipeSettingRow(
                icon = Icons.Outlined.SwipeLeft,
                gesture = "Swipe Left",
                action = swipeLeft,
                onClick = { choosingRight = false },
            )
        }

        item(key = "swipes_divider") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item(key = "gestures_header") {
            SectionHeader(icon = Icons.Outlined.Gesture, title = "Other Gestures")
        }

        item(key = "gesture_tap") {
            GestureRow(
                icon = Icons.Outlined.TouchApp,
                gesture = "Tap",
                description = "Open task details",
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        item(key = "gesture_long_press") {
            GestureRow(
                icon = Icons.Outlined.TouchApp,
                gesture = "Long Press",
                description = "Select multiple tasks",
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        item(key = "gesture_drag") {
            GestureRow(
                icon = Icons.Outlined.DragHandle,
                gesture = "Long Press and Drag",
                description = DRAG_GESTURE_TEXT,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        item(key = "gesture_checkbox") {
            GestureRow(
                icon = Icons.Outlined.CheckCircle,
                gesture = "Tap Checkbox",
                description = "Toggle task completion",
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item(key = "gestures_divider") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item(key = "gestures_tip") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = UNDO_TIP,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "gestures_bottom_spacer") {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    choosingRight?.let { right ->
        ChoiceDialog(
            title = if (right) "Swipe right" else "Swipe left",
            options = SwipeAction.entries.map { it.name to SwipeActions.name(it) },
            selected = (if (right) swipeRight else swipeLeft).name,
            onPick = { name ->
                val action = SwipeAction.valueOf(name)
                if (right) onSwipeRightChange(action) else onSwipeLeftChange(action)
                choosingRight = null
            },
            onDismiss = { choosingRight = null },
        )
    }
}

/** A swipe direction and the action it runs; tapping it opens the choice. */
@Composable
private fun SwipeSettingRow(
    icon: ImageVector,
    gesture: String,
    action: SwipeAction,
    onClick: () -> Unit,
) {
    val color = if (action == SwipeAction.NONE) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        SwipeActions.role(action).color
    }
    GestureRow(
        icon = icon,
        gesture = gesture,
        description = SwipeActions.description(action),
        color = color,
        value = SwipeActions.name(action),
        modifier = Modifier.clickable(onClickLabel = "Change", role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun GestureRow(
    icon: ImageVector,
    gesture: String,
    description: String,
    color: Color,
    value: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = gesture,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (value != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
