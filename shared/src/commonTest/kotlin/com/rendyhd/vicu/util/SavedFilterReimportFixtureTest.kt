package com.rendyhd.vicu.util

import com.rendyhd.vicu.data.remote.api.ProjectViewDto
import com.rendyhd.vicu.data.remote.api.SavedFilterDto
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListWire
import com.rendyhd.vicu.domain.model.CustomListWireFilter
import com.rendyhd.vicu.domain.model.CustomListWireImportBase
import com.rendyhd.vicu.domain.model.CustomListWireProjectView
import com.rendyhd.vicu.domain.model.CustomListWireSerializer
import com.rendyhd.vicu.domain.model.toDomain
import com.rendyhd.vicu.domain.model.toWire
import com.rendyhd.vicu.util.CrossAppFixture.readTestFixture
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the import-base, `compare`, `answers` and `views` vectors of `savedFilterImport` in
 * `test-fixtures/cross-app-semantics-v1.json` (cross-app semantics 3.7 and 3.9).
 */
class SavedFilterReimportFixtureTest {

    @Serializable
    data class ImportedList(
        val name: String,
        val icon: String,
        val savedFilterId: Long? = null,
        val projectView: CustomListWireProjectView? = null,
        val filter: CustomListWireFilter,
        val importBase: CustomListWireImportBase? = null,
    )

    @Serializable
    data class FilterVector(
        val name: String,
        val savedFilter: SavedFilterDto,
        val list: ImportedList? = null,
        val status: String,
        val reasons: List<String>,
    )

    @Serializable
    data class CompareVector(
        val name: String,
        val list: JsonObject,
        val fresh: JsonObject,
        val parts: Map<String, String>,
        val status: String,
    )

    @Serializable
    data class AnswerVector(
        val name: String,
        val list: JsonObject,
        val fresh: JsonObject,
        val overwrite: JsonObject,
        val skip: JsonObject,
    )

    @Serializable
    data class ViewProjectJson(val id: Long, val title: String, val archived: Boolean)

    @Serializable
    data class ViewVector(
        val name: String,
        val project: ViewProjectJson,
        val view: ProjectViewDto,
        val offered: Boolean,
        val list: ImportedList? = null,
        val status: String? = null,
        val reasons: List<String> = emptyList(),
    )

    @Serializable
    data class Block(
        val filters: List<FilterVector>,
        val compare: List<CompareVector>,
        val answers: List<AnswerVector>,
        val views: List<ViewVector>,
    )

    @Serializable
    data class Blocks(@SerialName("savedFilterImport") val block: Block)

    private val json = Json { ignoreUnknownKeys = true }

    private val block: Block by lazy { json.decodeFromString(Blocks.serializer(), readTestFixture("cross-app-semantics-v1.json")).block }

    private fun wire(obj: JsonObject, id: String = "x"): CustomListWire =
        json.decodeFromJsonElement(CustomListWireSerializer, JsonObject(obj + ("id" to JsonPrimitive(obj["id"]?.let { (it as JsonPrimitive).content } ?: id))))

    private fun domain(obj: JsonObject): CustomList = wire(obj).toDomain()

    private fun assertImported(expected: ImportedList, list: CustomList, name: String) {
        assertEquals(expected.name, list.name, name)
        assertEquals(expected.icon, list.icon, name)
        assertEquals(expected.savedFilterId, list.savedFilterId, name)
        assertEquals(expected.projectView?.viewId, list.projectView?.viewId, name)
        assertEquals(expected.projectView?.projectId, list.projectView?.projectId, name)
        val want = CustomListWire("new", expected.name, filter = expected.filter).toDomain().filter
        assertEquals(want, list.filter, name)
        expected.importBase?.let { base ->
            assertEquals(base, list.toWire().importBase, "$name: import base")
        }
    }

    @Test
    fun `every import records its base`() {
        val checked = block.filters.filter { it.list?.importBase != null }
        assertTrue(checked.size >= 2)
        for (vector in checked) {
            val result = SavedFilterImport.convert(vector.savedFilter, listId = "new")
            assertEquals(vector.status, result.status.name.lowercase(), vector.name)
            assertEquals(vector.reasons, result.reasons.map { it.wire }, vector.name)
            assertImported(assertNotNull(vector.list), assertNotNull(result.list, vector.name), vector.name)
        }
        // Every importable vector gets a base, whether or not the vector spells it out.
        for (vector in block.filters) {
            val list = SavedFilterImport.convert(vector.savedFilter, "new").list ?: continue
            assertNotNull(list.importBase, vector.name)
        }
    }

    @Test
    fun `every comparison`() {
        assertTrue(block.compare.size >= 10)
        for (vector in block.compare) {
            val result = SavedFilterImport.compare(domain(vector.list), domain(vector.fresh))
            val parts = SavedFilterImport.Part.entries.associate { it.wire to result.parts.getValue(it).wire }
            assertEquals(vector.parts, parts, vector.name)
            assertEquals(vector.status, result.status.wire, vector.name)
        }
    }

    @Test
    fun `overwrite and skip`() {
        for (vector in block.answers) {
            val before = wire(vector.list)
            val list = before.toDomain()
            val fresh = domain(vector.fresh)
            assertEquals(wire(vector.overwrite), SavedFilterImport.overwrite(list, fresh).toWire(preserving = before), "${vector.name}: overwrite")
            assertEquals(wire(vector.skip), SavedFilterImport.skip(list, fresh).toWire(preserving = before), "${vector.name}: skip")
            assertEquals(SavedFilterImport.Change.SAME, SavedFilterImport.compare(SavedFilterImport.overwrite(list, fresh), fresh).status, vector.name)
        }
    }

    @Test
    fun `every project view`() {
        assertTrue(block.views.size >= 10)
        for (vector in block.views) {
            val project = SavedFilterImport.ViewProject(vector.project.id, vector.project.title, vector.project.archived)
            assertEquals(vector.offered, SavedFilterImport.isOffered(project, vector.view), vector.name)
            if (!vector.offered) continue
            val result = SavedFilterImport.convertView(project, vector.view, "new")
            assertEquals(vector.status, result.status.name.lowercase(), vector.name)
            assertEquals(vector.reasons, result.reasons.map { it.wire }, vector.name)
            val expected = vector.list
            if (expected == null) {
                assertNull(result.list, vector.name)
            } else {
                assertImported(expected, assertNotNull(result.list, vector.name), vector.name)
            }
        }
    }

    @Test
    fun `the default list view is not offered`() {
        val project = SavedFilterImport.ViewProject(5, "Shopping", archived = false)
        assertFalse(SavedFilterImport.isOffered(project, block.views.first().view))
    }
}
