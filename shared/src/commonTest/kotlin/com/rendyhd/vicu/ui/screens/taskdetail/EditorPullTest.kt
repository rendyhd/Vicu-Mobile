package com.rendyhd.vicu.ui.screens.taskdetail

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorPullTest {

    // At density 1 a pixel is a dp.
    private val density = Density(1f)

    @Test
    fun `let go short of the distance and slowly the sheet stays`() {
        assertFalse(editorPullCloses(offset = 119f, velocity = 0f, density))
        assertFalse(editorPullCloses(offset = 60f, velocity = 799f, density))
    }

    @Test
    fun `let go past the distance the sheet closes`() {
        assertTrue(editorPullCloses(offset = 120f, velocity = 0f, density))
        assertTrue(editorPullCloses(offset = 300f, velocity = 400f, density))
    }

    @Test
    fun `a flick down closes it early and a flick up keeps it`() {
        assertTrue(editorPullCloses(offset = 30f, velocity = 800f, density))
        assertFalse(editorPullCloses(offset = 300f, velocity = -800f, density))
    }

    @Test
    fun `a flick that barely moved the sheet or a sheet at rest does not close`() {
        assertFalse(editorPullCloses(offset = 10f, velocity = 3000f, density))
        assertFalse(editorPullCloses(offset = 0f, velocity = 3000f, density))
    }

    @Test
    fun `the scrim fades as the sheet goes down`() {
        assertEquals(1f, editorPullScrimFraction(0f, 1000f), 0f)
        assertEquals(0.75f, editorPullScrimFraction(250f, 1000f), 1e-6f)
        assertEquals(0f, editorPullScrimFraction(2000f, 1000f), 0f)
        // Not measured yet: the scrim is whole.
        assertEquals(1f, editorPullScrimFraction(50f, 0f), 0f)
    }

    @Test
    fun `a drag never takes the sheet above rest`() {
        val pull = EditorPullState()
        assertEquals(40f, pull.dragBy(40f), 0f)
        assertEquals(-40f, pull.dragBy(-100f), 0f)
        assertEquals(0f, pull.offset, 0f)
    }

    @Test
    fun `a drag down at the top of the list pulls the sheet`() {
        val pull = EditorPullState()
        val connection = EditorPullConnection(pull) {}
        val used = connection.onPostScroll(Offset.Zero, Offset(0f, 50f), NestedScrollSource.UserInput)
        assertEquals(Offset(0f, 50f), used)
        assertEquals(50f, pull.offset, 0f)
    }

    @Test
    fun `a fling that reaches the top of the list does not pull the sheet`() {
        val pull = EditorPullState()
        val connection = EditorPullConnection(pull) {}
        val used = connection.onPostScroll(Offset.Zero, Offset(0f, 50f), NestedScrollSource.SideEffect)
        assertEquals(Offset.Zero, used)
        assertEquals(0f, pull.offset, 0f)
    }

    @Test
    fun `a drag up moves a pulled sheet back before the list scrolls`() {
        val pull = EditorPullState()
        pull.dragBy(30f)
        val connection = EditorPullConnection(pull) {}
        // The sheet takes the 30 it is down; the list scrolls the other 20.
        assertEquals(Offset(0f, -30f), connection.onPreScroll(Offset(0f, -50f), NestedScrollSource.UserInput))
        assertEquals(0f, pull.offset, 0f)
        // At rest a drag up is the list's.
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, -50f), NestedScrollSource.UserInput))
    }

    @Test
    fun `letting go of a pulled sheet keeps the fling from the list`() = runTest {
        val pull = EditorPullState()
        var released: Float? = null
        val connection = EditorPullConnection(pull) { released = it }

        // A sheet at rest leaves the fling to the list.
        assertEquals(Velocity.Zero, connection.onPreFling(Velocity(0f, 900f)))
        assertNull(released)

        pull.dragBy(80f)
        assertEquals(Velocity(0f, 900f), connection.onPreFling(Velocity(0f, 900f)))
        assertEquals(900f, released)
    }

    @Test
    fun `a sheet let go springs back to rest even with animations off`() = runTest {
        val pull = EditorPullState()
        pull.dragBy(90f)
        pull.springBack(animationsOff(), velocity = 500f, reduceMotion = false)
        advanceUntilIdle()
        assertEquals(0f, pull.offset, 0f)
    }

    @Test
    fun `with reduced motion the sheet is back at once and does not slide out`() = runTest {
        val pull = EditorPullState()
        pull.sheetHeight = 1000f
        pull.dragBy(90f)
        pull.slideOut(animationsOff(), velocity = 500f, reduceMotion = true)
        advanceUntilIdle()
        assertEquals(90f, pull.offset, 0f)
        pull.springBack(this, velocity = 0f, reduceMotion = true)
        assertEquals(0f, pull.offset, 0f)
    }

    @Test
    fun `a closing sheet slides down to its height`() = runTest {
        val pull = EditorPullState()
        pull.sheetHeight = 1000f
        pull.dragBy(150f)
        pull.slideOut(animationsOff(), velocity = 900f, reduceMotion = false)
        advanceUntilIdle()
        assertEquals(1000f, pull.offset, 0f)
    }

    /** The test's scope with the animator scale at 0 and a frame clock that ticks 16 ms per frame. */
    private fun TestScope.animationsOff(): CoroutineScope {
        val off = object : MotionDurationScale {
            override val scaleFactor: Float = 0f
        }
        var nanos = 0L
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                nanos += 16_000_000L
                return onFrame(nanos)
            }
        }
        return this + off + clock
    }
}
