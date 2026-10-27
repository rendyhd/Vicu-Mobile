package com.rendyhd.vicu.ui.screens.guided

import com.rendyhd.vicu.ai.organize.Suggestion
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DueDates
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a card writes and how a session counts what it did (contract section 14.3). */
class GuidedCardsTest {
    private val zone = TimeZone.of("Europe/Amsterdam")
    private val friday = LocalDate(2026, 10, 30).atTime(DueDates.DATE_ONLY_TIME)

    private fun task(id: Long, due: LocalDate? = null, project: Long = 1) = Task(
        id = id,
        title = "Task $id",
        projectId = project,
        dueDate = due?.let { DueDates.dateOnlyDue(it, zone).toString() }.orEmpty(),
    )

    private fun suggestion(id: Long, due: LocalDateTime? = null, project: Long? = null, clearDue: Boolean = false, title: String? = null) =
        Suggestion(id, title, project, due, dueHasTime = false, clearDue = clearDue, close = null, reason = "Because")

    @Test
    fun aSuggestionFillsTheCardAndMarksWhatItFilled() {
        val draft = CardDraft.of(suggestion(1, due = friday, project = 3))
        assertEquals(DueChoice.On(friday, false), draft.due)
        assertEquals(3L, draft.projectId)
        assertEquals(setOf(CardDraft.FIELD_WHEN, CardDraft.FIELD_PROJECT), draft.suggested)
        assertEquals(setOf(CardDraft.FIELD_PROJECT), draft.withDue(null).suggested)
        assertEquals(DueChoice.Clear, CardDraft.of(suggestion(1, clearDue = true)).due)
    }

    @Test
    fun aCardWithoutChangesWritesNothing() {
        val t = task(1, project = 3)
        assertNull(CardDraft().changeOf(t))
        assertNull(CardDraft(projectId = 3, title = " Task 1 ").changeOf(t))
        // No date on a task without one is no change either.
        assertNull(CardDraft(due = DueChoice.Clear).changeOf(t))
    }

    @Test
    fun aCardWritesOnlyWhatItChanges() {
        val t = task(1, due = LocalDate(2026, 10, 1))
        val change = CardDraft(due = DueChoice.On(friday, false), projectId = 4).changeOf(t)!!
        assertEquals(friday to false, change.due)
        assertEquals(4L, change.projectId)
        assertNull(change.title)
        assertTrue(CardDraft(due = DueChoice.Clear).changeOf(t)!!.clearDue)
    }

    @Test
    fun theSummaryCountsEachTaskOnceLikeTheDesktop() {
        val log = GuidedLog()
        val a = task(1)
        val b = task(2, due = LocalDate(2026, 10, 1))
        log.changed(CardDraft(due = DueChoice.On(friday, false), projectId = 5).changeOf(a)!!, zone)
        log.changed(CardDraft(due = DueChoice.Clear).changeOf(b)!!, zone)
        log.completed(task(3))
        log.deleted(task(4))
        assertEquals(GuidedCounts(sorted = 4, scheduled = 1, unscheduled = 1, moved = 1, completed = 1, deleted = 1), log.counts())
        assertEquals("Sorted 4: 1 scheduled, 1 without a date now, 1 moved, 1 completed, 1 deleted", log.counts().text)
        assertEquals("Nothing changed", GuidedLog().counts().text)
    }

    @Test
    fun aSecondChangeToATaskKeepsWhereItStarted() {
        val log = GuidedLog()
        val a = task(1)
        log.changed(CardDraft(due = DueChoice.On(friday, false)).changeOf(a)!!, zone)
        val after = log.all.single().after
        log.changed(CardDraft(title = "Renamed").changeOf(after)!!, zone)
        val entry = log.all.single()
        assertEquals(a, entry.before)
        assertEquals("Renamed", entry.after.title)
        assertEquals("Sorted 1: 1 scheduled, 1 renamed", log.counts().text)
    }
}
