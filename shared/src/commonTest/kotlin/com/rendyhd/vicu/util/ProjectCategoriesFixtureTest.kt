package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `projectCategories` vectors of test-fixtures/cross-app-semantics-v1.json (contract section 13).
 * The desktop runs the same file.
 */
class ProjectCategoriesFixtureTest {

    @Serializable
    private data class FixtureProject(
        val id: Long,
        val title: String,
        val parent: Long,
        val position: Double = 0.0,
        val archived: Boolean = false,
    )

    @Serializable
    private data class ReadVector(val name: String, val entries: JsonObject, val key: String, val expect: JsonElement)

    @Serializable
    private data class Group(val category: String?, val projects: List<Long>)

    @Serializable
    private data class LayoutVector(val name: String, val entries: JsonObject, val expect: List<Group>)

    @Serializable
    private data class ChangeVector(val name: String, val entries: JsonObject, val action: JsonObject, val expectWrites: JsonObject)

    @Serializable
    private data class Block(
        val inboxProjectId: Long,
        val projects: List<FixtureProject>,
        val read: List<ReadVector>,
        val layout: List<LayoutVector>,
        val changes: List<ChangeVector>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val block: Block by lazy {
        val root = json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json")).jsonObject
        json.decodeFromJsonElement(Block.serializer(), root.getValue("projectCategories"))
    }

    private val projects: List<Project> by lazy {
        block.projects.map { Project(id = it.id, title = it.title, parentProjectId = it.parent, position = it.position, isArchived = it.archived) }
    }

    @Test
    fun `categories and assignments read only values of the right shape`() {
        assertTrue(block.read.isNotEmpty())
        for (v in block.read) {
            assertEquals(v.expect, ProjectCategories.read(v.entries, v.key), v.name)
        }
    }

    @Test
    fun `the project list groups its top-level projects by category`() {
        assertTrue(block.layout.isNotEmpty())
        for (v in block.layout) {
            val groups = ProjectCategories.layout(projects, block.inboxProjectId, v.entries)
                .map { Group(it.category?.id, it.items) }
            assertEquals(v.expect, groups, v.name)
        }
    }

    @Test
    fun `every change writes exactly the entries of the vector`() {
        assertTrue(block.changes.isNotEmpty())
        for (v in block.changes) {
            val writes = plan(v.entries, v.action)
            assertEquals(v.expectWrites, JsonObject(writes.mapValues { it.value ?: JsonNull }), v.name)
        }
    }

    private fun plan(entries: Map<String, JsonElement?>, action: JsonObject): Map<String, JsonElement?> {
        fun text(name: String) = action.getValue(name).jsonPrimitive.content
        return when (text("type")) {
            "new" -> ProjectCategories.planNew(entries, text("id"), text("name"))
            "move" -> ProjectCategories.planMove(entries, text("category"), action.getValue("to").jsonPrimitive.int)
            "rename" -> ProjectCategories.planRename(entries, text("category"), text("name"))
            "delete" -> ProjectCategories.planDelete(entries, text("category"))
            "assign" -> ProjectCategories.planAssign(
                entries,
                action.getValue("project").jsonPrimitive.long,
                action.getValue("category").jsonPrimitive.contentOrNull,
            )
            "split" -> ProjectCategories.planSplit(
                entries,
                ProjectCategories.layout(projects, block.inboxProjectId, entries),
                action.getValue("project").jsonPrimitive.longOrNull ?: error("split without a project"),
                text("id"),
                text("name"),
            )
            else -> error("unknown action ${text("type")}")
        }
    }
}
