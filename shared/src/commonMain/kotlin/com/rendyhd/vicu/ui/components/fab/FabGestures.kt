package com.rendyhd.vicu.ui.components.fab

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.voice.TaskDefaults
import kotlinx.coroutines.CancellationException

/** How far below the + the finger slides before letting go cancels. */
private val CANCEL_DISTANCE = 56.dp

/** How far above the + the finger slides to lock: smart dictation keeps listening. */
private val LOCK_DISTANCE = 72.dp

/** Read by a screen reader as the + button's extra action: hold has no equivalent for it. */
internal const val SAY_A_TASK = "Say a task"

/**
 * The + button's hold and drag ([FabGesture]) on top of its own tap. The pointer is watched before
 * the button sees it (the initial pass): a quick tap reaches the button untouched and opens quick
 * add as always; once a hold or a drag starts, the rest of the gesture is taken from the button, so
 * its click does not fire. The task said gets [defaults], the list's own; [openQuickAdd] is the
 * button's tap, for a device that cannot listen.
 *
 * A screen reader gets "Say a task" as a custom action; dragging only saves taps, so it has none.
 */
@Composable
internal fun Modifier.fabGestures(
    controller: FabController,
    screen: String?,
    defaults: TaskDefaults,
    openQuickAdd: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val currentScreen by rememberUpdatedState(screen)
    val currentOpen by rememberUpdatedState(openQuickAdd)
    val currentDefaults by rememberUpdatedState(defaults)
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onGloballyPositioned { coordinates[0] = it }
        // The + stays in its corner, faded, while its copy follows the finger.
        .graphicsLayer { alpha = if (controller.dragPoint != null) DRAGGED_FAB_ALPHA else 1f }
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(SAY_A_TASK) {
                    controller.sayTask(currentDefaults) { currentOpen() }
                    true
                },
            )
        }
        .pointerInput(controller) {
            val cancelDistance = CANCEL_DISTANCE.toPx()
            val lockDistance = LOCK_DISTANCE.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                fun root(position: Offset): Offset {
                    val layout = coordinates[0]
                    return if (layout != null && layout.isAttached) layout.localToRoot(position) else position
                }
                val gesture = FabGesture(touchSlop = viewConfiguration.touchSlop, cancelDistance = cancelDistance, lockDistance = lockDistance)
                val start = root(down.position)
                gesture.down(
                    down.uptimeMillis,
                    start.x,
                    start.y,
                    canDrag = controller.dropTargets.hasTargets(currentScreen),
                    canLock = controller.holdToTalk.lockOffered(),
                )
                fun handle(event: FabGestureEvent?) {
                    when (event) {
                        null -> Unit
                        FabGestureEvent.StartHold -> {
                            if (controller.startHold(currentDefaults) { currentOpen() }) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            } else {
                                gesture.end()
                            }
                        }
                        is FabGestureEvent.CancelArmed -> controller.holdToTalk.setCancelArmed(event.armed)
                        FabGestureEvent.Lock -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            controller.holdToTalk.lock()
                        }
                        is FabGestureEvent.Release ->
                            if (event.cancelled) controller.holdToTalk.cancel() else controller.holdToTalk.release()
                        is FabGestureEvent.StartDrag -> controller.startDrag(currentScreen, Offset(event.x, event.y))
                        is FabGestureEvent.Drag -> controller.dragTo(currentScreen, Offset(event.x, event.y))
                        is FabGestureEvent.Drop -> controller.drop(currentScreen, Offset(event.x, event.y))
                        FabGestureEvent.DragCancelled -> controller.endDrag()
                    }
                }
                var lastTime = down.uptimeMillis
                try {
                    while (true) {
                        val wait = gesture.millisUntilHold(lastTime)
                        val event = if (wait != null) {
                            withTimeoutOrNull(wait) { awaitPointerEvent(PointerEventPass.Initial) }
                        } else {
                            awaitPointerEvent(PointerEventPass.Initial)
                        }
                        if (event == null) {
                            // Held still: the hold time has passed.
                            lastTime = down.uptimeMillis + gesture.holdMillis
                            handle(gesture.tick(lastTime))
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        lastTime = change.uptimeMillis
                        val at = root(change.position)
                        val lifted = !change.pressed
                        val taken = gesture.consumes
                        val result = if (lifted) gesture.up(at.x, at.y) else gesture.move(lastTime, at.x, at.y)
                        // Taken before (a hold, a drag, an ended gesture) or by this event (a drag starting).
                        if (taken || gesture.consumes) event.changes.forEach { it.consume() }
                        handle(result)
                        if (lifted) break
                    }
                } catch (e: CancellationException) {
                    // The button left the screen mid-gesture: nothing is added, nothing is dropped.
                    handle(gesture.cancel())
                    throw e
                }
            }
        }
}

private const val DRAGGED_FAB_ALPHA = 0.4f
