package com.rendyhd.vicu.ui.screens.taskdetail

import androidx.compose.animation.core.animate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.theme.VicuMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How far the editor sheet has to be pulled down for letting go to close it. */
internal val EDITOR_PULL_CLOSE_DISTANCE = 120.dp

/** A flick down at least this fast (per second) closes the sheet before [EDITOR_PULL_CLOSE_DISTANCE]. */
internal val EDITOR_PULL_CLOSE_VELOCITY = 800.dp

/** How far a flick has to move the sheet, so the end of a scroll to the top of the list does not close it. */
internal val EDITOR_PULL_FLICK_MIN = 24.dp

/**
 * Whether letting go of the sheet pulled [offset] px down at [velocity] px/s (down positive)
 * closes it: a flick down closes it, a flick up keeps it, and otherwise the distance decides.
 */
internal fun editorPullCloses(offset: Float, velocity: Float, density: Density): Boolean = with(density) {
    val flick = EDITOR_PULL_CLOSE_VELOCITY.toPx()
    when {
        offset <= 0f -> false
        velocity >= flick -> offset >= EDITOR_PULL_FLICK_MIN.toPx()
        velocity <= -flick -> false
        else -> offset >= EDITOR_PULL_CLOSE_DISTANCE.toPx()
    }
}

/** How much of the scrim stays while the sheet is [offset] px down: all at rest, none at the bottom. */
internal fun editorPullScrimFraction(offset: Float, sheetHeight: Float): Float =
    if (sheetHeight <= 0f) 1f else 1f - (offset / sheetHeight).coerceIn(0f, 1f)

/**
 * The editor sheet pulled down by the finger: [offset] is how far (px, 0 at rest), read by the
 * sheet's graphics layer (a draw-phase read, so a drag frame recomposes nothing). A new drag
 * takes the sheet from a spring that is still settling it.
 */
class EditorPullState {
    var offset by mutableFloatStateOf(0f)
        private set

    /** The sheet's height, how far it goes when it slides out. */
    var sheetHeight by mutableFloatStateOf(0f)

    private var settling: Job? = null

    /** Moves the sheet by a drag of [delta] px (down positive), never above rest; returns the part it used. */
    fun dragBy(delta: Float): Float {
        settling?.cancel()
        val before = offset
        offset = (before + delta).coerceAtLeast(0f)
        return offset - before
    }

    /** Back to rest, a spring from the finger's [velocity]; with [reduceMotion] at once. */
    fun springBack(scope: CoroutineScope, velocity: Float, reduceMotion: Boolean) {
        if (reduceMotion) {
            settling?.cancel()
            offset = 0f
        } else {
            settle(scope, target = 0f, velocity)
        }
    }

    /** On down off the screen while the editor fades out; with [reduceMotion] it only fades. */
    fun slideOut(scope: CoroutineScope, velocity: Float, reduceMotion: Boolean) {
        if (!reduceMotion) settle(scope, target = sheetHeight, velocity)
    }

    // The springs run on the caller's coroutine, so they follow the system animator scale.
    private fun settle(scope: CoroutineScope, target: Float, velocity: Float) {
        settling?.cancel()
        settling = scope.launch {
            animate(
                initialValue = offset,
                targetValue = target,
                initialVelocity = velocity,
                animationSpec = VicuMotion.defaultSpatialSpec(),
            ) { value, _ -> offset = value.coerceAtLeast(0f) }
        }
    }
}

/**
 * Pulls the sheet with what the list in it does not scroll: a drag down at the top of the list
 * moves the sheet down, and a drag up moves a pulled sheet back before the list scrolls. Only a
 * finger moves it; a fling that reaches the top of the list stops there. Letting go of a pulled
 * sheet calls [onRelease] with the velocity (px/s, down positive) and keeps the fling from the list.
 */
internal class EditorPullConnection(
    private val pull: EditorPullState,
    private val onRelease: (velocity: Float) -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && available.y < 0f && pull.offset > 0f) {
            Offset(0f, pull.dragBy(available.y))
        } else {
            Offset.Zero
        }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && available.y > 0f) {
            Offset(0f, pull.dragBy(available.y))
        } else {
            Offset.Zero
        }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (pull.offset <= 0f) return Velocity.Zero
        onRelease(available.y)
        return available
    }
}
