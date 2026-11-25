package com.rendyhd.vicu.util

import com.rendyhd.vicu.util.EntryChip.LABELS
import com.rendyhd.vicu.util.EntryChip.PRIORITY
import com.rendyhd.vicu.util.EntryChip.PROJECT
import com.rendyhd.vicu.util.EntryChip.REMIND
import com.rendyhd.vicu.util.EntryChip.REPEAT
import com.rendyhd.vicu.util.EntryChip.TEMPLATE
import com.rendyhd.vicu.util.EntryChip.WHEN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryChipsTest {

    private val all = EntryChip.entries.toSet()
    private val noTemplate = all - TEMPLATE

    @Test
    fun `the default layout is the row of earlier versions`() {
        val layout = EntryChipLayout()
        assertEquals(EntryChipPreset.STANDARD, layout.preset)
        assertFalse(layout.openFull)
        assertEquals(listOf(WHEN, REMIND, PRIORITY, TEMPLATE), EntryChips.shown(layout, all, emptySet(), details = false))
        assertEquals(listOf(WHEN, REMIND, PRIORITY), EntryChips.shown(layout, noTemplate, emptySet(), details = false))
        // Labels, the project and a repeat join once they hold something, in their place.
        assertEquals(
            listOf(WHEN, REMIND, PRIORITY, LABELS, REPEAT),
            EntryChips.shown(layout, noTemplate, setOf(REPEAT, LABELS), details = false),
        )
    }

    @Test
    fun `minimal shows only what holds a value, and Details shows everything there is`() {
        val layout = EntryChipLayout(pinned = EntryChips.pinnedOf(EntryChipPreset.MINIMAL))
        assertEquals(emptyList(), EntryChips.shown(layout, all, emptySet(), details = false))
        assertEquals(listOf(WHEN), EntryChips.shown(layout, all, setOf(WHEN), details = false))
        assertEquals(EntryChip.entries, EntryChips.shown(layout, all, emptySet(), details = true))
        assertEquals(EntryChip.entries - TEMPLATE, EntryChips.shown(layout, noTemplate, emptySet(), details = true))
    }

    @Test
    fun `the row follows the user's order`() {
        val layout = EntryChipLayout(order = listOf(PROJECT, WHEN, TEMPLATE, REMIND, PRIORITY, LABELS, REPEAT), pinned = setOf(WHEN, PROJECT))
        assertEquals(listOf(PROJECT, WHEN, PRIORITY), EntryChips.shown(layout, all, setOf(PRIORITY), details = false))
    }

    @Test
    fun `Details is offered only while it would show another chip`() {
        val standard = EntryChipLayout()
        assertTrue(EntryChips.detailsReveals(standard, all, emptySet()))
        assertFalse(EntryChips.detailsReveals(standard, all, setOf(LABELS, PROJECT, REPEAT)))
        val everything = EntryChipLayout(pinned = EntryChips.pinnedOf(EntryChipPreset.EVERYTHING))
        assertFalse(EntryChips.detailsReveals(everything, all, emptySet()))
        // A template chip that cannot show is nothing to reveal.
        val allButTemplate = EntryChipLayout(pinned = all - TEMPLATE)
        assertFalse(EntryChips.detailsReveals(allButTemplate, noTemplate, emptySet()))
        assertTrue(EntryChips.detailsReveals(allButTemplate, all, emptySet()))
    }

    @Test
    fun `a pinned set is a preset only when it is exactly one`() {
        assertEquals(EntryChipPreset.MINIMAL, EntryChips.presetOf(emptySet()))
        assertEquals(EntryChipPreset.EVERYTHING, EntryChips.presetOf(all))
        assertNull(EntryChips.presetOf(setOf(WHEN)))
        assertNull(EntryChips.presetOf(EntryChips.STANDARD - TEMPLATE))
    }

    @Test
    fun `a stored order keeps the chips it names first and adds the rest in their default order`() {
        assertEquals(EntryChip.entries, EntryChips.readOrder(null))
        assertEquals(EntryChip.entries, EntryChips.readOrder(""))
        assertEquals(
            listOf(REPEAT, WHEN, REMIND, PRIORITY, LABELS, PROJECT, TEMPLATE),
            EntryChips.readOrder("REPEAT,CALENDAR,WHEN,REPEAT"),
        )
    }

    @Test
    fun `pins never written are Standard and an empty value is Minimal`() {
        assertEquals(EntryChips.STANDARD, EntryChips.readPinned(null))
        assertEquals(emptySet(), EntryChips.readPinned(""))
        assertEquals(setOf(LABELS), EntryChips.readPinned("LABELS,ESTIMATE"))
    }

    @Test
    fun `writing keeps the chips of a newer version`() {
        assertEquals("WHEN,LABELS", EntryChips.write(listOf(WHEN, LABELS), null))
        assertEquals("WHEN,ESTIMATE", EntryChips.write(listOf(WHEN), "LABELS,ESTIMATE"))
        assertEquals("ESTIMATE", EntryChips.write(emptyList(), "ESTIMATE,WHEN"))
    }

    @Test
    fun `a move takes one chip to another place`() {
        val order = EntryChip.entries
        assertEquals(listOf(REMIND, PRIORITY, WHEN, LABELS, PROJECT, REPEAT, TEMPLATE), EntryChips.moved(order, 0, 2))
        assertEquals(order, EntryChips.moved(order, 0, 0))
        assertEquals(order, EntryChips.moved(order, 0, 9))
    }
}
