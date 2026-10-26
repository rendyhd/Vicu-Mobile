package com.rendyhd.vicu.ui.screens.shared

import com.rendyhd.vicu.data.repository.InMemorySyncedEntries
import com.rendyhd.vicu.data.repository.ProjectCategoryPreferences
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.util.POSITION_STEP
import com.rendyhd.vicu.util.ProjectCategories
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The category actions of [ProjectActions] (contract section 13): what each writes and says. */
class ProjectCategoryActionsTest {

    private val workEntries: Map<String, JsonElement?> = mapOf(
        "category.work" to buildJsonObject { put("name", "Work"); put("order", 65536) },
        "category.project.2" to JsonPrimitive("work"),
        "category.project.3" to JsonPrimitive("work"),
    )

    private class Rig(projects: List<Project>, entries: Map<String, JsonElement?> = emptyMap()) {
        val repo = FakeProjectRepository(projects)
        val synced = InMemorySyncedEntries(entries)
        val auth = offlineAuthManager()
        val actions = testProjectActions(repo, auth = auth, categories = ProjectCategoryPreferences(synced))

        suspend fun state() = actions.categories.first()
    }

    private fun project(id: Long, parent: Long = 0, position: Double = id * 100.0) =
        Project(id = id, title = "P$id", parentProjectId = parent, position = position)

    private val projects = listOf(project(1), project(2), project(3), project(4))

    @Test
    fun `a new category goes last and can take a project with it`() = runTest {
        val rig = Rig(projects, workEntries)

        val result = rig.actions.createCategory("  Home ", rig.repo.projects.value.first { it.id == 4L })

        assertEquals(ProjectActionResult.Done("Moved \"P4\" to Home"), result)
        val state = rig.state()
        assertEquals(listOf("Work", "Home"), state.categories.map { it.name })
        assertEquals(2 * ProjectCategories.ORDER_STEP, state.categories.last().order)
        assertEquals("Home", state.categoryOf(4)?.name)
    }

    @Test
    fun `a blank name makes nothing`() = runTest {
        val rig = Rig(projects)
        assertEquals(ProjectActionResult.Failed(ProjectActions.ENTER_A_NAME), rig.actions.createCategory("   "))
        assertTrue(rig.state().categories.isEmpty())
    }

    @Test
    fun `deleting a category keeps its projects and undo puts everything back`() = runTest {
        val rig = Rig(projects, workEntries)
        val before = rig.state()

        val previous = rig.actions.deleteCategory("work")

        assertTrue(rig.state().categories.isEmpty())
        assertNull(rig.state().categoryOf(2))
        assertTrue(rig.repo.deletes.isEmpty(), "no project is deleted")
        assertEquals(setOf("category.work", "category.project.2", "category.project.3"), previous.keys)

        rig.actions.restoreCategories(previous)
        assertEquals(before, rig.state())
    }

    @Test
    fun `moving a project from a menu says where it went and writes nothing when it is already there`() = runTest {
        val rig = Rig(projects, workEntries)
        val work = rig.state().category("work")!!

        assertEquals(ProjectActionResult.Done("Moved \"P1\" to Work"), rig.actions.assignCategory(projects[0], work))
        assertEquals(ProjectActionResult.Done("Moved \"P2\" out of Work"), rig.actions.assignCategory(projects[1], null))
        assertEquals(ProjectActionResult.Done(null), rig.actions.assignCategory(projects[0], work))
        assertTrue(rig.repo.updates.isEmpty(), "a menu move keeps the position")
    }

    @Test
    fun `a drop into another category writes the category and a position between the new neighbours`() = runTest {
        val rig = Rig(projects, workEntries)

        // P4 dropped between P2 (200) and P3 (300) in Work.
        val result = rig.actions.moveIntoCategory(4, "work", listOf(projects[1], projects[3], projects[2]))

        assertEquals(SiblingMoveResult.Saved, result)
        assertEquals("work", rig.state().categoryOf(4)?.id)
        assertEquals(250.0, rig.repo.updates.single { it.id == 4L }.position)
    }

    @Test
    fun `a drop into an empty category puts the project after every top-level project`() = runTest {
        val entries = workEntries + ("category.home" to buildJsonObject { put("name", "Home"); put("order", 131072) })
        val rig = Rig(projects, entries)

        rig.actions.moveIntoCategory(1, "home", listOf(projects[0]))

        assertEquals("home", rig.state().categoryOf(1)?.id)
        assertEquals(400.0 + POSITION_STEP, rig.repo.updates.single { it.id == 1L }.position)
    }

    @Test
    fun `add category above starts a category at that project`() = runTest {
        val rig = Rig(projects, workEntries)

        rig.actions.addCategoryAbove(3, "Later")

        val state = rig.state()
        assertEquals(listOf("Work", "Later"), state.categories.map { it.name })
        assertEquals("work", state.categoryOf(2)?.id)
        assertEquals("Later", state.categoryOf(3)?.name)
    }

    @Test
    fun `moving a category up and down follows the display order`() = runTest {
        val entries = workEntries + ("category.home" to buildJsonObject { put("name", "Home"); put("order", 131072) })
        val rig = Rig(projects, entries)

        rig.actions.moveCategory("home", -1)
        assertEquals(listOf("Home", "Work"), rig.state().categories.map { it.name })
        rig.actions.moveCategory("home", -1)
        assertEquals(listOf("Home", "Work"), rig.state().categories.map { it.name }, "the first one goes no higher")
    }
}
