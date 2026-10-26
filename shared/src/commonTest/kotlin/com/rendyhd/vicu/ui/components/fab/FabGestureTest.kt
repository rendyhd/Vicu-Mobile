package com.rendyhd.vicu.ui.components.fab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The + button's tap, hold and drag are told apart by time and distance. */
class FabGestureTest {
    private fun gesture() = FabGesture(touchSlop = 8f, cancelDistance = 50f)

    @Test
    fun aQuickLiftIsTheButtonsOwnTap() {
        val g = gesture()
        g.down(0, 100f, 100f, canDrag = true)
        assertNull(g.move(100, 103f, 102f))
        assertFalse(g.consumes)
        assertNull(g.up(103f, 102f))
        assertEquals(FabGesturePhase.IDLE, g.phase)
    }

    @Test
    fun holdingStillForTheHoldTimeStartsListening() {
        val g = gesture()
        g.down(1_000, 100f, 100f, canDrag = false)
        assertEquals(400L, g.millisUntilHold(1_000))
        assertNull(g.tick(1_399))
        assertEquals(FabGestureEvent.StartHold, g.tick(1_400))
        assertTrue(g.consumes)
        assertEquals(FabGestureEvent.Release(cancelled = false), g.up(100f, 100f))
    }

    @Test
    fun aMoveAfterTheHoldTimeStartsTheHoldFirst() {
        val g = gesture()
        g.down(0, 100f, 100f, canDrag = true)
        assertEquals(FabGestureEvent.StartHold, g.move(450, 130f, 100f))
        assertEquals(FabGesturePhase.HOLDING, g.phase)
    }

    @Test
    fun aWobbleWithinTheSlopStillHolds() {
        val g = gesture()
        g.down(0, 100f, 100f, canDrag = true)
        assertNull(g.move(200, 105f, 104f))
        assertEquals(FabGestureEvent.StartHold, g.tick(400))
    }

    @Test
    fun slidingUpPastTheLockLineLocks() {
        val g = FabGesture(touchSlop = 8f, cancelDistance = 50f, lockDistance = 70f)
        g.down(0, 100f, 100f, canDrag = false, canLock = true)
        g.tick(400)
        assertNull(g.move(500, 100f, 50f))
        assertEquals(FabGestureEvent.Lock, g.move(600, 100f, 20f))
        // Smart dictation took over: the rest of the gesture, the lift too, does nothing.
        assertTrue(g.consumes)
        assertNull(g.move(650, 100f, 200f))
        assertNull(g.up(100f, 200f))
    }

    @Test
    fun withoutTheLockSlidingUpOnlyHolds() {
        val g = FabGesture(touchSlop = 8f, cancelDistance = 50f, lockDistance = 70f)
        g.down(0, 100f, 100f, canDrag = false)
        g.tick(400)
        assertNull(g.move(500, 100f, 0f))
        assertEquals(FabGestureEvent.Release(cancelled = false), g.up(100f, 0f))
    }

    @Test
    fun aSidewaysSlideDoesNotLock() {
        val g = FabGesture(touchSlop = 8f, cancelDistance = 50f, lockDistance = 70f)
        g.down(0, 100f, 100f, canDrag = false, canLock = true)
        g.tick(400)
        assertNull(g.move(500, 200f, 20f))
    }

    @Test
    fun slidingDownPastTheLineArmsTheCancelAndBackUpDisarmsIt() {
        val g = gesture()
        g.down(0, 100f, 100f, canDrag = false)
        g.tick(400)
        assertNull(g.move(500, 100f, 140f))
        assertEquals(FabGestureEvent.CancelArmed(true), g.move(600, 100f, 160f))
        assertNull(g.move(650, 100f, 170f))
        assertEquals(FabGestureEvent.CancelArmed(false), g.move(700, 100f, 120f))
        assertEquals(FabGestureEvent.CancelArmed(true), g.move(800, 100f, 200f))
        assertEquals(FabGestureEvent.Release(cancelled = true), g.up(100f, 200f))
    }

    @Test
    fun slidingSidewaysOrUpDoesNotCancel() {
        val g = gesture()
        g.down(0, 100f, 100f, canDrag = false)
        g.tick(400)
        assertNull(g.move(500, 220f, 160f))
        assertNull(g.move(600, 100f, 0f))
        assertEquals(FabGestureEvent.Release(cancelled = false), g.up(100f, 0f))
    }

    @Test
    fun movingBeforeTheHoldDragsWhenTheScreenHasTargets() {
        val g = gesture()
        g.down(0, 100f, 100f, canDrag = true)
        assertEquals(FabGestureEvent.StartDrag(100f, 80f), g.move(100, 100f, 80f))
        assertTrue(g.consumes)
        // No hold once dragging.
        assertNull(g.tick(1_000))
        assertEquals(FabGestureEvent.Drag(90f, 40f), g.move(1_000, 90f, 40f))
        assertEquals(FabGestureEvent.Drop(90f, 40f), g.up(90f, 40f))
    }

    @Test
    fun movingWithoutTargetsLeavesItToTheButton() {
        val g = gesture()
        g.down(0, 100f, 100f, canDrag = false)
        assertNull(g.move(100, 100f, 60f))
        assertEquals(FabGesturePhase.PASSED, g.phase)
        assertFalse(g.consumes)
        assertNull(g.millisUntilHold(100))
        assertNull(g.tick(1_000))
        assertNull(g.up(100f, 60f))
    }

    @Test
    fun aHoldThatCouldNotStartSwallowsTheRest() {
        val g = gesture()
        g.down(0, 100f, 100f, canDrag = false)
        g.tick(400)
        g.end()
        assertTrue(g.consumes)
        assertNull(g.move(500, 100f, 200f))
        assertNull(g.up(100f, 200f))
    }

    @Test
    fun aCancelledPointerAddsNothingAndDropsNothing() {
        val hold = gesture()
        hold.down(0, 0f, 0f, canDrag = false)
        hold.tick(400)
        assertEquals(FabGestureEvent.Release(cancelled = true), hold.cancel())

        val drag = gesture()
        drag.down(0, 0f, 0f, canDrag = true)
        drag.move(50, 0f, -40f)
        assertEquals(FabGestureEvent.DragCancelled, drag.cancel())

        val tap = gesture()
        tap.down(0, 0f, 0f, canDrag = true)
        assertNull(tap.cancel())
    }
}
