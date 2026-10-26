package com.rendyhd.vicu.ui.components.fab

import kotlin.math.abs
import kotlin.math.hypot

/** What a press of the + button turns into, decided by [FabGesture]. */
sealed interface FabGestureEvent {
    /** Held still long enough: start listening. */
    data object StartHold : FabGestureEvent

    /** Moved before the hold time, on a screen with drop targets: the + follows the finger. */
    data class StartDrag(val x: Float, val y: Float) : FabGestureEvent

    data class Drag(val x: Float, val y: Float) : FabGestureEvent

    /** While holding: the finger crossed the cancel line below the button ([armed]) or came back. */
    data class CancelArmed(val armed: Boolean) : FabGestureEvent

    /** While holding: the finger slid up to the lock. Smart dictation takes over; the rest of the gesture is swallowed. */
    data object Lock : FabGestureEvent

    /** The finger lifted after a hold; [cancelled] when it was past the cancel line. */
    data class Release(val cancelled: Boolean) : FabGestureEvent

    /** The finger lifted after a drag, at ([x], [y]). */
    data class Drop(val x: Float, val y: Float) : FabGestureEvent

    /** The system took the pointer away during a drag: nothing happens. */
    data object DragCancelled : FabGestureEvent
}

enum class FabGesturePhase {
    IDLE,

    /** Down, not yet a hold or a drag: a lift now is the button's own tap. */
    PRESSED,
    HOLDING,
    DRAGGING,

    /** Moved off without a drag to do: the button decides (it cancels its click when the finger left it). */
    PASSED,

    /** The gesture did its thing (or the hold could not start); the rest of it is swallowed. */
    ENDED,
}

/**
 * The gestures of the + button, without Compose so they can be tested: a lift before [holdMillis]
 * without moving is the button's tap (quick add, as always); holding still for [holdMillis] starts
 * listening, sliding down more than [cancelDistance] arms the cancel, and sliding up more than
 * [lockDistance] locks it (when the lock is offered); moving more than [touchSlop] before the hold
 * drags the + when the screen has drop targets.
 *
 * Positions are in one coordinate space of the caller's choosing (root coordinates in the app).
 * [consumes] says whether the pointer events belong to the gesture, so the button must not see them.
 */
class FabGesture(
    val holdMillis: Long = HOLD_MILLIS,
    private val touchSlop: Float,
    private val cancelDistance: Float,
    private val lockDistance: Float = cancelDistance,
) {
    var phase: FabGesturePhase = FabGesturePhase.IDLE
        private set

    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var canDrag = false
    private var canLock = false
    private var cancelArmed = false

    /** Whether the button has to be kept from seeing the pointer (its click must not fire). */
    val consumes: Boolean
        get() = phase == FabGesturePhase.HOLDING || phase == FabGesturePhase.DRAGGING || phase == FabGesturePhase.ENDED

    fun down(atMillis: Long, x: Float, y: Float, canDrag: Boolean, canLock: Boolean = false) {
        phase = FabGesturePhase.PRESSED
        downAt = atMillis
        downX = x
        downY = y
        this.canDrag = canDrag
        this.canLock = canLock
        cancelArmed = false
    }

    /** Milliseconds until the hold starts, or null when no hold is pending. */
    fun millisUntilHold(atMillis: Long): Long? =
        if (phase == FabGesturePhase.PRESSED) (downAt + holdMillis - atMillis).coerceAtLeast(0L) else null

    /** Time passed without a pointer event. */
    fun tick(atMillis: Long): FabGestureEvent? {
        if (phase != FabGesturePhase.PRESSED || atMillis - downAt < holdMillis) return null
        phase = FabGesturePhase.HOLDING
        return FabGestureEvent.StartHold
    }

    fun move(atMillis: Long, x: Float, y: Float): FabGestureEvent? {
        // A late event decides the hold first: the finger was still for long enough before it moved.
        tick(atMillis)?.let { return it }
        return when (phase) {
            FabGesturePhase.PRESSED -> {
                if (hypot(x - downX, y - downY) <= touchSlop) return null
                if (canDrag) {
                    phase = FabGesturePhase.DRAGGING
                    FabGestureEvent.StartDrag(x, y)
                } else {
                    phase = FabGesturePhase.PASSED
                    null
                }
            }
            FabGesturePhase.HOLDING -> {
                if (canLock && isPastLockLine(x, y)) {
                    phase = FabGesturePhase.ENDED
                    return FabGestureEvent.Lock
                }
                val armed = isPastCancelLine(x, y)
                if (armed == cancelArmed) return null
                cancelArmed = armed
                FabGestureEvent.CancelArmed(armed)
            }
            FabGesturePhase.DRAGGING -> FabGestureEvent.Drag(x, y)
            else -> null
        }
    }

    fun up(x: Float, y: Float): FabGestureEvent? {
        val event = when (phase) {
            // A lift before the hold started (the caller ticks while waiting) is the button's tap.
            FabGesturePhase.HOLDING -> FabGestureEvent.Release(cancelled = cancelArmed || isPastCancelLine(x, y))
            FabGesturePhase.DRAGGING -> FabGestureEvent.Drop(x, y)
            else -> null
        }
        phase = FabGesturePhase.IDLE
        return event
    }

    /** The system cancelled the pointer (another window took it). */
    fun cancel(): FabGestureEvent? {
        val event = when (phase) {
            FabGesturePhase.HOLDING -> FabGestureEvent.Release(cancelled = true)
            FabGesturePhase.DRAGGING -> FabGestureEvent.DragCancelled
            else -> null
        }
        phase = FabGesturePhase.IDLE
        return event
    }

    /** The hold could not start (a permission prompt, no recognizer): swallow the rest of the gesture. */
    fun end() {
        if (phase != FabGesturePhase.IDLE) phase = FabGesturePhase.ENDED
    }

    // Down past the line, and more down than sideways, so a wobble of the thumb does not cancel.
    private fun isPastCancelLine(x: Float, y: Float): Boolean {
        val dy = y - downY
        return dy > cancelDistance && dy > abs(x - downX)
    }

    // Up past the line, and more up than sideways.
    private fun isPastLockLine(x: Float, y: Float): Boolean {
        val dy = downY - y
        return dy > lockDistance && dy > abs(x - downX)
    }

    companion object {
        /** How long the + is held before it listens. */
        const val HOLD_MILLIS = 400L
    }
}
