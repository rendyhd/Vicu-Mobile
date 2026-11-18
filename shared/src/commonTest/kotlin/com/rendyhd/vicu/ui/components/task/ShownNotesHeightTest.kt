package com.rendyhd.vicu.ui.components.task

import kotlin.test.Test
import kotlin.test.assertEquals

class ShownNotesHeightTest {
    @Test
    fun shortNotesShowWhole() {
        assertEquals(300, shownNotesHeight(full = 300, fold = 600, slack = 100, open = false))
    }

    @Test
    fun notesJustPastTheFoldShowWhole() {
        assertEquals(700, shownNotesHeight(full = 700, fold = 600, slack = 100, open = false))
    }

    @Test
    fun longNotesStopAtTheFold() {
        assertEquals(600, shownNotesHeight(full = 701, fold = 600, slack = 100, open = false))
    }

    @Test
    fun openNotesShowWhole() {
        assertEquals(5000, shownNotesHeight(full = 5000, fold = 600, slack = 100, open = true))
    }

    @Test
    fun withoutAFoldNotesShowWhole() {
        assertEquals(5000, shownNotesHeight(full = 5000, fold = null, slack = 100, open = false))
    }

    @Test
    fun measuredHeightWins() {
        assertEquals(150, notesTextHeight(measured = 150, remembered = 400, fold = 600, min = 72))
    }

    // The field the list disposed comes back at its old height, not at the fold: that frame at the
    // fold, then shrinking, threw the editor's page down every time the notes scrolled into view.
    @Test
    fun recomposedNotesStartAtTheirLastHeight() {
        assertEquals(150, notesTextHeight(measured = null, remembered = 150, fold = 600, min = 72))
    }

    @Test
    fun firstFrameEverIsAtTheFold() {
        assertEquals(600, notesTextHeight(measured = null, remembered = null, fold = 600, min = 72))
    }

    @Test
    fun neverBelowTheMinimum() {
        assertEquals(72, notesTextHeight(measured = 20, remembered = null, fold = null, min = 72))
        assertEquals(72, notesTextHeight(measured = null, remembered = null, fold = null, min = 72))
    }
}
