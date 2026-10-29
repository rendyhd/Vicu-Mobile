package com.rendyhd.vicu.ui.components.task

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.shared.DragClickGuard
import com.rendyhd.vicu.ui.components.shared.dragClickGuardPresses
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState

/**
 * A task row that can be dragged to a new place in a lazy list, shared by every screen that lets
 * the user order tasks by hand (a project, the Inbox). A long press that moves the row drags it
 * ([onDragStarted] / [onDragStopped]); the rows that [canDrag] is false for keep their plain
 * long-press ([onLongClick]). A screen reader cannot drag: [onMoveUp] and [onMoveDown] (null where
 * the move is not possible) are offered as its "Move up" and "Move down" actions instead.
 *
 * A draggable row has no long click of its own, so the row's click would also fire when a long
 * press is let go without moving (the drag handle's selection). [DragClickGuard] drops that click.
 */
@Composable
fun LazyItemScope.ReorderableTaskRow(
    reorderableState: ReorderableLazyListState,
    task: Task,
    displayTask: Task,
    canDrag: Boolean,
    selectionActive: Boolean,
    selected: Boolean,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
    onSubtaskToggleDone: (Task) -> Unit,
    onSubtaskClick: (Task) -> Unit,
    onLongClick: (() -> Unit)?,
    contentStartPadding: Dp = 0.dp,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val clickGuard = remember { DragClickGuard() }
    ReorderableItem(reorderableState, key = task.id) { isDragging ->
        val elevation by animateDpAsState(
            if (isDragging) 4.dp else 0.dp,
            label = "dragElevation",
        )
        // The Surface stays in the tree even when idle: swapping it in/out on isDragging
        // would change the slot structure and reset the row's internal state mid-drag.
        Surface(
            // On every row, draggable or not: the row lifted to select it can no longer drag, and
            // the next tap on it must still count.
            modifier = Modifier.dragClickGuardPresses(clickGuard),
            shadowElevation = elevation,
            color = if (isDragging) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                Color.Transparent
            },
        ) {
            SwipeableTaskItem(
                task = displayTask,
                onToggleDone = onToggleDone,
                onClick = clickGuard.guarded(onClick),
                onSubtaskToggleDone = onSubtaskToggleDone,
                onSubtaskClick = onSubtaskClick,
                selectionActive = selectionActive,
                selected = selected,
                onLongClick = onLongClick,
                contentStartPadding = contentStartPadding,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                modifier = if (canDrag) {
                    Modifier.longPressDraggableHandle(
                        onDragStarted = {
                            clickGuard.dragStarted()
                            onDragStarted()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragStopped = onDragStopped,
                    )
                } else {
                    Modifier
                },
            )
        }
    }
}
