package com.rendyhd.vicu.ui.navigation

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.util.ProjectCategories
import com.rendyhd.vicu.util.ProjectCategoryState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The drawer's and Settings' project lists with category headers (contract section 13). */
class ProjectListItemsTest {

    private fun project(id: Long, parent: Long = 0, position: Double = id * 100.0) =
        Project(id = id, title = "P$id", parentProjectId = parent, position = position)

    // 1, 2 (with 3 below it), 4 and 5 at the top; 4 is in Work, Home is empty.
    private val tree = buildProjectTree(listOf(project(1), project(2), project(3, parent = 2), project(4), project(5)))

    private val entries: Map<String, JsonElement?> = mapOf(
        "category.work" to buildJsonObject { put("name", "Work"); put("order", 1) },
        "category.home" to buildJsonObject { put("name", "Home"); put("order", 2) },
        "category.project.4" to JsonPrimitive("work"),
    )
    private val categories = ProjectCategories.state(entries)

    /** The items as text: a header is `#id`, a row `id` with `@category` when it is in one. */
    private fun shape(items: List<ProjectListItem>): List<String> = items.map { item ->
        when (item) {
            is ProjectListItem.Header -> "#${item.category.id}"
            is ProjectListItem.Row -> "${item.row.project.id}" + (item.categoryId?.let { "@$it" } ?: "")
        }
    }

    @Test
    fun `without categories the items are the rows of the tree in the same order`() {
        val items = projectListItems(tree, emptySet(), ProjectCategoryState.EMPTY)
        assertEquals(visibleProjectRows(tree, emptySet()), items.projectRows())
        assertTrue(items.none { it is ProjectListItem.Header })
    }

    @Test
    fun `the projects without a category come first - then each category under its header - empty ones too`() {
        val items = projectListItems(tree, emptySet(), categories)
        assertEquals(listOf("1", "2", "3", "5", "#work", "4@work", "#home"), shape(items))
        assertEquals(listOf(1, 0), items.filterIsInstance<ProjectListItem.Header>().map { it.count })
    }

    @Test
    fun `a collapsed category hides its projects and keeps their count`() {
        val items = projectListItems(tree, emptySet(), categories, collapsedCategories = setOf("work"))
        assertEquals(listOf("1", "2", "3", "5", "#work", "#home"), shape(items))
        val work = items.filterIsInstance<ProjectListItem.Header>().first()
        assertTrue(work.collapsed)
        assertEquals(1, work.count)
    }

    @Test
    fun `a top-level project dragged down over a header goes first into that category`() {
        val items = projectListItems(tree, emptySet(), categories)
        val moved = moveProjectItem(items, 5, items.indexOfCategory("work"))!!
        assertEquals(listOf("1", "2", "3", "#work", "5@work", "4@work", "#home"), shape(moved))
        assertEquals("work" to listOf(5L, 4L), droppedPlace(moved, 5))
    }

    @Test
    fun `a top-level project dragged up over a header goes last into the group above`() {
        val items = projectListItems(tree, emptySet(), categories)
        val moved = moveProjectItem(items, 4, items.indexOfCategory("work"))!!
        assertEquals(listOf("1", "2", "3", "5", "4", "#work", "#home"), shape(moved))
        assertEquals(null to listOf(1L, 2L, 5L, 4L), droppedPlace(moved, 4))
    }

    @Test
    fun `a top-level project takes the projects below it into the other category`() {
        val items = projectListItems(tree, emptySet(), categories)
        val moved = moveProjectItem(items, 2, items.indexOfCategory("work"))!!
        assertEquals(listOf("1", "5", "#work", "2@work", "3@work", "4@work", "#home"), shape(moved))
    }

    @Test
    fun `over a row of another group a project lands before it from below and after it from above`() {
        val items = projectListItems(tree, emptySet(), categories)
        val down = moveProjectItem(items, 1, items.indexOfProject(4))!!
        assertEquals(listOf("2", "3", "5", "#work", "4@work", "1@work", "#home"), shape(down))
        val up = moveProjectItem(down, 4, down.indexOfProject(2))!!
        assertEquals(listOf("4", "2", "3", "5", "#work", "1@work", "#home"), shape(up))
    }

    @Test
    fun `a subproject stays among its siblings and never goes past a header`() {
        val items = projectListItems(tree, emptySet(), categories)
        assertNull(moveProjectItem(items, 3, items.indexOfCategory("work")))
        assertNull(moveProjectItem(items, 3, items.indexOfProject(4)))
    }

    @Test
    fun `a dragged project is carried as one closed row`() {
        val items = projectListItems(tree, emptySet(), categories)
        val carried = items.withoutDescendants(2)
        assertEquals(listOf("1", "2", "5", "#work", "4@work", "#home"), shape(carried))
        assertEquals(false, (carried[1] as ProjectListItem.Row).row.expanded)
        assertEquals(items, items.withoutDescendants(1), "a project with nothing below it stays as it is")
        val moved = moveProjectItem(carried, 2, carried.indexOfCategory("home"))!!
        assertEquals("home" to listOf(2L), droppedPlace(moved, 2))
    }

    @Test
    fun `move up and move down stay within the level and the category`() {
        val siblings = levelSiblings(projectListItems(tree, emptySet(), categories))
        assertEquals(listOf(1L, 2L, 5L), siblings[1])
        assertEquals(listOf(3L), siblings[3])
        assertEquals(listOf(4L), siblings[4])
    }

    @Test
    fun `the group of a top-level project is found in the tree`() {
        assertEquals(listOf(1L, 2L, 5L), topLevelGroupOf(tree, categories, 5))
        assertEquals(listOf(4L), topLevelGroupOf(tree, categories, 4))
        assertTrue(topLevelGroupOf(tree, categories, 3).isEmpty(), "a subproject is not in a group of its own")
    }

    @Test
    fun `a drop shown before it is stored moves the project into its new category`() {
        val shown = categories.withAssignment(5, "home").withAssignment(4, null)
        assertEquals(listOf("1", "2", "3", "4", "#work", "#home", "5@home"), shape(projectListItems(tree, emptySet(), shown)))
        assertNull(categories.withAssignment(5, "gone").categoryOf(5), "a category that is not there is none")
    }
}
