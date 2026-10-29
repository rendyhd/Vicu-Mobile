package com.rendyhd.vicu.ui.screens.settings

import com.rendyhd.vicu.data.local.SwipeAction
import com.rendyhd.vicu.ui.components.task.SwipeActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GestureGuideTest {

    @Test
    fun `every swipe action has its own name and text`() {
        val names = SwipeAction.entries.map { SwipeActions.name(it) }
        val texts = SwipeAction.entries.map { SwipeActions.description(it) }
        assertEquals(SwipeAction.entries.size, names.toSet().size)
        assertEquals(SwipeAction.entries.size, texts.toSet().size)
    }

    @Test
    fun `every action but nothing has a word behind the row`() {
        for (action in SwipeAction.entries) {
            assertEquals(action == SwipeAction.NONE, SwipeActions.word(action).isEmpty(), action.name)
        }
        // The words the swipe showed before the actions could be chosen.
        assertEquals("Complete", SwipeActions.word(SwipeAction.COMPLETE))
        assertEquals("Schedule", SwipeActions.word(SwipeAction.CHOOSE_WHEN))
        assertEquals("Urgent", SwipeActions.word(SwipeAction.URGENT))
    }

    @Test
    fun `the delete text says when it asks first`() {
        val text = SwipeActions.description(SwipeAction.DELETE)
        assertTrue("Confirm before deleting" in text)
        assertTrue("subtasks" in text)
    }

    @Test
    fun `the intro says how far a swipe goes`() {
        assertTrue("half" in SWIPE_INTRO)
    }

    @Test
    fun `the drag text tells a screen reader user about the move actions`() {
        assertTrue("Move up" in DRAG_GESTURE_TEXT && "Move down" in DRAG_GESTURE_TEXT)
        assertTrue("TalkBack" in DRAG_GESTURE_TEXT)
        assertTrue("menu" in DRAG_GESTURE_TEXT, "the drawer rows can be moved too")
    }

    @Test
    fun `the undo tip describes the held row - not a snackbar`() {
        assertTrue("snackbar" !in UNDO_TIP.lowercase())
        assertTrue("checkbox" in UNDO_TIP.lowercase())
    }
}
