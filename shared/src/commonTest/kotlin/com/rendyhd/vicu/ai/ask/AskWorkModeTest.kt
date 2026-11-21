package com.rendyhd.vicu.ai.ask

import com.rendyhd.vicu.data.repository.WorkState
import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskOrderSettings
import com.rendyhd.vicu.util.WorkMode
import com.rendyhd.vicu.util.WorkScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ask's tools answer as the screens do (contract 22.4): lists by the mode, search across both. */
class AskWorkModeTest {
    private val inbox = 1L
    private val client = 2L
    private val home = 3L
    private val workLabel = 9L

    // Inbox: one plain task (personal) and one with the work label; Client is work, Home is not.
    private val tasks = listOf(
        Task(id = 10, title = "Inbox plain", projectId = inbox),
        Task(id = 11, title = "Inbox labelled", projectId = inbox, labels = listOf(Label(id = workLabel, title = "wm-work"))),
        Task(id = 12, title = "Client task", projectId = client),
        Task(id = 13, title = "Home task", projectId = home),
    )

    private fun snapshot(mode: WorkMode, withState: Boolean = true) = AskSnapshot(
        tasks = tasks,
        projects = listOf(
            Project(inbox, "Inbox", maxPermission = 1),
            Project(client, "Client", maxPermission = 1),
            Project(home, "Home", maxPermission = 1),
        ),
        labels = emptyList(),
        customLists = emptyList(),
        collab = CollabState(),
        signals = emptyMap(),
        order = TaskOrderSettings(),
        inboxId = inbox,
        today = LocalDate(2026, 10, 6),
        zone = TimeZone.UTC,
        reviewCadenceDays = 14,
        reviewEnabled = false,
        reviewExcludeInbox = true,
        work = if (withState) WorkState(mode, WorkScope(label = workLabel, projects = setOf(client))) else WorkState.OFF,
    )

    private fun ids(result: JsonObject): List<Long> = result.getValue("tasks").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.long }

    private fun view(s: AskSnapshot, name: String): List<Long> =
        ids(s.listView(buildJsonObject { put("view", name) }, emptyList()))

    @Test
    fun listViewsFollowTheMode() {
        assertEquals(listOf(10L, 11L), view(snapshot(WorkMode.ALL), "inbox").sorted())
        assertEquals(listOf(11L), view(snapshot(WorkMode.WORK), "inbox"))
        assertEquals(listOf(10L), view(snapshot(WorkMode.PERSONAL), "inbox"))
        assertEquals(listOf(12L), view(snapshot(WorkMode.WORK), "anytime"))
        assertEquals(listOf(13L), view(snapshot(WorkMode.PERSONAL), "anytime"))
    }

    @Test
    fun theOverviewCountsByTheMode() {
        fun counts(s: AskSnapshot) = s.overview().getValue("counts").jsonObject
        assertEquals(2, counts(snapshot(WorkMode.ALL)).getValue("inbox").jsonPrimitive.int)
        assertEquals(1, counts(snapshot(WorkMode.WORK)).getValue("inbox").jsonPrimitive.int)
        assertEquals(1, counts(snapshot(WorkMode.WORK)).getValue("anytime").jsonPrimitive.int)
        assertEquals(1, counts(snapshot(WorkMode.PERSONAL)).getValue("anytime").jsonPrimitive.int)
    }

    @Test
    fun searchFindsBothModes() {
        val args = buildJsonObject { put("text", "task") }
        assertEquals(listOf(12L, 13L), ids(snapshot(WorkMode.WORK).findTasks(args)).sorted())
        assertEquals(listOf(12L, 13L), ids(snapshot(WorkMode.PERSONAL).findTasks(args)).sorted())
    }

    @Test
    fun withoutAStateNothingIsFiltered() {
        assertEquals(listOf(10L, 11L), view(snapshot(WorkMode.WORK, withState = false), "inbox").sorted())
    }
}
