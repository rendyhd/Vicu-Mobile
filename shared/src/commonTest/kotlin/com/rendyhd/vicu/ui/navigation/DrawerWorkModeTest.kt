package com.rendyhd.vicu.ui.navigation

import com.rendyhd.vicu.data.local.BehaviorPrefs
import com.rendyhd.vicu.data.local.ReviewPrefs
import com.rendyhd.vicu.data.repository.WorkState
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.ProjectCategory
import com.rendyhd.vicu.util.ProjectCategoryState
import com.rendyhd.vicu.util.WorkMode
import com.rendyhd.vicu.util.WorkScope
import com.rendyhd.vicu.util.pro.ProLayer
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Work and Personal (docs/cross-app-semantics-v1.md section 22) where the app applies it: the drawer's project list, a list's rows, Pro's gate. */
class DrawerWorkModeTest {

    private val today = LocalDate(2026, 10, 7)

    private fun project(id: Long, parent: Long = 0) =
        Project(id = id, title = "Project $id", parentProjectId = parent, position = id.toDouble())

    // Inbox 1; 2 Personal; 3 work with 4 below it; 5 Personal with work subproject 6.
    private val projects = listOf(project(1), project(2), project(3), project(4, parent = 3), project(5), project(6, parent = 5))
    private val scope = WorkScope(label = 9, projects = setOf(3, 4, 6))

    private fun build(mode: WorkMode, categories: ProjectCategoryState = ProjectCategoryState.EMPTY): DrawerUiState {
        val work = if (mode == WorkMode.ALL) WorkState.OFF else WorkState(mode, scope)
        val sources = DrawerSources(projects, emptyList(), emptyList(), DrawerSectionsExpanded(), 1L, categories, work)
        return buildDrawerState(sources, BottomBarSlot.DEFAULT_SLOTS, ReviewPrefs(), emptyList(), today)
    }

    private fun DrawerUiState.listed(): List<Long> = projectRows.map { it.project.id }.sorted()

    @Test
    fun `All lists every project and Work the work ones and what holds them and Personal the others`() {
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), build(WorkMode.ALL).listed())
        // 5 stays in Work because its subproject 6 is work, so the tree is not cut.
        assertEquals(listOf(3L, 4L, 5L, 6L), build(WorkMode.WORK).listed())
        // The work projects and everything below them are out; the Inbox is not in the rows.
        assertEquals(listOf(2L, 5L), build(WorkMode.PERSONAL).listed())
    }

    @Test
    fun `the project pickers still reach every project`() {
        assertEquals(projects.map { it.id }, build(WorkMode.WORK).allProjects.map { it.id })
    }

    @Test
    fun `a category shows while one of its projects is listed and an empty one only in All and Personal`() {
        val work = ProjectCategory("w", "Clients", 1.0)
        val home = ProjectCategory("h", "Home", 2.0)
        val empty = ProjectCategory("e", "Someday", 3.0)
        val categories = ProjectCategoryState(listOf(work, home, empty), mapOf(3L to "w", 2L to "h"))

        assertEquals(listOf("w", "h", "e"), build(WorkMode.ALL, categories).categories.categories.map { it.id })
        assertEquals(listOf("w"), build(WorkMode.WORK, categories).categories.categories.map { it.id })
        assertEquals(listOf("h", "e"), build(WorkMode.PERSONAL, categories).categories.categories.map { it.id })
        // The pickers keep the whole list.
        assertEquals(3, build(WorkMode.WORK, categories).pickerCategories.categories.size)
    }

    @Test
    fun `a list shows the tasks of the mode and a split counts the ones it leaves out`() {
        val work = WorkState(WorkMode.WORK, scope)
        val tasks = listOf(
            Task(id = 1, title = "a", projectId = 3),
            Task(id = 2, title = "b", projectId = 2),
            Task(id = 3, title = "c", projectId = 2, labels = listOf(Label(9, "Work"))),
            Task(id = 4, title = "d", projectId = 2, done = true),
        )

        assertEquals(listOf(1L, 3L), work.filter(tasks).map { it.id })
        val split = work.split(tasks, showHidden = false)
        assertEquals(listOf(1L, 3L), split.tasks.map { it.id })
        // Only open tasks are counted: the done one is not "left out".
        assertEquals(1, split.hidden)
        assertEquals(4, work.split(tasks, showHidden = true).tasks.size)
        // Off or All: the list is passed on as it is.
        assertEquals(tasks, WorkState.OFF.filter(tasks))
        assertEquals(0, WorkState.OFF.split(tasks, false).hidden)
    }

    @Test
    fun `Pro shows unless Pro only in Work puts a Personal device out of it and the ledger still follows the plain switch`() {
        val personal = BehaviorPrefs(proEnabled = true, workModeEnabled = true, workMode = "personal", proOnlyInWork = true)
        assertFalse(personal.isProEnabled())
        assertFalse(personal.isProEnabled(ProLayer.PROMISES))
        assertTrue(personal.isProSwitchedOn())

        assertTrue(personal.copy(workMode = "work").isProEnabled())
        assertTrue(personal.copy(workMode = "all").isProEnabled())
        assertTrue(personal.copy(proOnlyInWork = false).isProEnabled())
        // The feature off keeps its stored mode and changes nothing.
        assertTrue(personal.copy(workModeEnabled = false).isProEnabled())
        assertEquals(WorkMode.ALL, personal.copy(workModeEnabled = false).effectiveWorkMode)
        assertEquals(WorkMode.PERSONAL, personal.effectiveWorkMode)
        // Pro itself off stays off.
        assertFalse(BehaviorPrefs(workModeEnabled = true, workMode = "work").isProEnabled())
    }

    @Test
    fun `Pro's area may be the work projects`() {
        assertTrue(SyncedPrefKeys.isWritableProValue(SyncedPrefKeys.PRO_AREA, JsonPrimitive("work")))
        assertTrue(SyncedPrefKeys.isWritableProValue(SyncedPrefKeys.PRO_AREA, JsonArray(listOf(JsonPrimitive("c1")))))
        assertFalse(SyncedPrefKeys.isWritableProValue(SyncedPrefKeys.PRO_AREA, JsonPrimitive("Work")))
    }
}
