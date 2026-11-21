package com.rendyhd.vicu.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `workMode` vectors of test-fixtures/cross-app-semantics-v1.json (contract section 22.10).
 * The desktop runs the same file against `src/shared/work-scope.ts`.
 */
class WorkScopeFixtureTest {

    private val block: JsonObject by lazy {
        Json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json"))
            .jsonObject.getValue("workMode").jsonObject
    }

    private val inbox: Long get() = block.getValue("inboxProjectId").jsonPrimitive.long

    private val projects: List<WorkProject> by lazy {
        block.getValue("projects").jsonArray.map {
            val o = it.jsonObject
            WorkProject(o.getValue("id").jsonPrimitive.long, o.getValue("parent").jsonPrimitive.long)
        }
    }

    private fun vectors(name: String): List<JsonObject> = block.getValue(name).jsonArray.map { it.jsonObject }

    private fun JsonObject.longs(name: String): List<Long> = getValue(name).jsonArray.map { it.jsonPrimitive.long }

    private fun JsonObject.entries(name: String = "entries"): Map<String, JsonElement?> =
        getValue(name).jsonObject.mapValues { (_, v) -> v.takeUnless { it is JsonNull } }

    private fun JsonObject.optLong(name: String): Long? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull

    private fun JsonObject.mode(name: String = "mode"): WorkMode = WorkScopeRules.parseWorkMode(getValue(name).jsonPrimitive.contentOrNull)

    private fun JsonObject.switches(name: String = "settings"): WorkModeSwitches {
        val s = getValue(name).jsonObject
        fun flag(key: String) = s[key]?.jsonPrimitive?.booleanOrNull == true
        return WorkModeSwitches(
            workModeEnabled = flag("work_mode_enabled"),
            workMode = s["work_mode"]?.jsonPrimitive?.contentOrNull,
            proOnlyInWork = flag("pro_only_in_work"),
            standaloneMode = flag("standalone_mode"),
        )
    }

    private fun scopeOf(entries: Map<String, JsonElement?>): WorkScope =
        WorkScopeRules.scopeOf(entries, projects, inbox, null)

    @Test
    fun `keys name a project only in the written form`() {
        for (v in vectors("keys")) {
            assertEquals(v.optLong("expect"), WorkScopeRules.workProjectIdOfKey(v.getValue("key").jsonPrimitive.content), v.toString())
        }
    }

    @Test
    fun `only the allowed values are writable`() {
        for (v in vectors("writable")) {
            val value = v["value"]?.takeUnless { it is JsonNull }
            assertEquals(v.getValue("expect").jsonPrimitive.boolean, WorkScopeRules.isWritableWorkValue(v.getValue("key").jsonPrimitive.content, value), v.toString())
        }
    }

    @Test
    fun `the work label is a whole number above zero`() {
        for (v in vectors("label")) {
            assertEquals(v.optLong("expect"), WorkScopeRules.readWorkLabel(v["value"]), v.toString())
        }
    }

    @Test
    fun `the nearest entry decides which projects are work`() {
        assertTrue(vectors("resolve").isNotEmpty())
        for (v in vectors("resolve")) {
            val entries = v.entries()
            val work = WorkScopeRules.workProjectIds(projects, { entries[WorkScopeRules.workProjectKey(it)] }, inbox)
            assertEquals(v.longs("expect"), work.sorted(), v.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun `a task is work through its project or the work label`() {
        val tasks = block.getValue("tasks").jsonArray.map { it.jsonObject }
        for (v in vectors("classify")) {
            val scope = scopeOf(v.entries())
            val work = tasks.filter { t ->
                WorkScopeRules.isWorkTask(t.optLong("project"), labelsOf(t), scope)
            }.map { it.getValue("id").jsonPrimitive.long }
            assertEquals(v.longs("expectWork"), work, v.getValue("name").jsonPrimitive.content)
        }
    }

    private fun labelsOf(task: JsonObject): List<Long>? =
        (task["labels"] as? JsonArray)?.map { it.jsonPrimitive.long }

    @Test
    fun `a stored mode that is not known is all`() {
        for (v in vectors("modes")) {
            val raw = v["value"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.takeIf { it.isString }?.content
            assertEquals(v.getValue("expect").jsonPrimitive.content, WorkScopeRules.parseWorkMode(raw).key, v.toString())
        }
    }

    @Test
    fun `the mode in effect follows the switch and standalone mode`() {
        for (v in vectors("effective")) {
            assertEquals(v.getValue("expect").jsonPrimitive.content, WorkScopeRules.effectiveWorkMode(v.switches()).key, v.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun `rows of a list follow the mode and the nested rule`() {
        val rows = block.getValue("rows").jsonObject
        val label = rows.getValue("label").jsonPrimitive.long
        val scope = WorkScope(label, emptySet())
        val tasks = rows.getValue("tasks").jsonArray.map { it.jsonObject }
        for (v in rows.getValue("vectors").jsonArray.map { it.jsonObject }) {
            val nested = v.getValue("nested").jsonPrimitive.boolean
            val shown = WorkScopeRules.shownInWorkMode(
                tasks,
                scope,
                v.mode(),
                idOf = { it.getValue("id").jsonPrimitive.long },
                projectOf = { it.optLong("project") },
                labelsOf = { labelsOf(it) },
                parentOf = if (nested) ({ it.optLong("parent") }) else null,
            ).map { it.getValue("id").jsonPrimitive.long }
            assertEquals(v.longs("expect"), shown, v.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun `the project list keeps the tree whole and the Inbox listed`() {
        val list = block.getValue("projectList").jsonObject
        val scope = scopeOf(list.entries())
        for (v in list.getValue("vectors").jsonArray.map { it.jsonObject }) {
            val shown = WorkScopeRules.projectsShownInMode(projects, scope.projects, v.mode(), inbox)
            assertEquals(v.longs("expect"), shown.sorted(), v.toString())
        }
    }

    @Test
    fun `a new task gets the work label only in Work mode`() {
        for (v in vectors("newTask")) {
            val label = v.optLong("label")
            val scope = WorkScope(label, v.longs("workProjects").toSet())
            val task = v.getValue("task").jsonObject
            val labels = labelsOf(task)
            val mode = v.mode()
            val name = v.getValue("name").jsonPrimitive.content
            assertEquals(v.optLong("expectAdd"), WorkScopeRules.workLabelForNewTask(task.optLong("project"), labels, scope, mode), name)
            assertEquals(v.getValue("expectStaysPersonal").jsonPrimitive.boolean, WorkScopeRules.newTaskStaysPersonal(task.optLong("project"), labels, scope, mode), name)
        }
    }

    @Test
    fun `marking a project writes an entry only when the parent differs`() {
        for (v in vectors("mark")) {
            val expected = v.getValue("expectWrite").takeUnless { it is JsonNull }?.jsonPrimitive?.boolean
            assertEquals(expected, WorkScopeRules.workEntryToWrite(v.getValue("want").jsonPrimitive.boolean, v.getValue("parentIsWork").jsonPrimitive.boolean), v.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun `Pro only in Work blocks Pro in Personal mode`() {
        for (v in vectors("proOnly")) {
            assertEquals(v.getValue("expectProAllowed").jsonPrimitive.boolean, WorkScopeRules.proAllowedInWorkMode(v.switches()), v.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun `the Pro area value work means the work projects`() {
        for (v in vectors("proArea")) {
            assertEquals(v.getValue("expectWork").jsonPrimitive.boolean, WorkScopeRules.isWorkProArea(v["value"]), v.toString())
        }
    }

    @Test
    fun `the server clause is a superset of what the mode shows`() {
        for (v in vectors("clause")) {
            val scope = WorkScope(v.optLong("label"), v.longs("workProjects").toSet())
            val result = WorkScopeRules.workModeClause(scope, v.mode())
            val name = v.getValue("name").jsonPrimitive.content
            assertEquals(v.getValue("expect").takeUnless { it is JsonNull }?.jsonPrimitive?.content, result.clause, name)
            assertEquals(v.getValue("expectEmpty").jsonPrimitive.boolean, result.empty, name)
        }
    }
}
