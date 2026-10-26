package com.rendyhd.vicu.ai.dictation

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.JsonSchema
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.screens.dictation.newProjectPlace
import com.rendyhd.vicu.ui.screens.dictation.planGroups
import com.rendyhd.vicu.ui.screens.dictation.planTitle
import com.rendyhd.vicu.util.NetworkResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** A brain dump (`docs/ai-assistant-v1.md`, section 4.8) beyond the fixture's check vectors. */
class BrainDumpTest {
    private val context = DictationContext(
        now = LocalDateTime(2026, 10, 6, 10, 5, 31),
        zone = TimeZone.of("Europe/Amsterdam"),
        list = "Today",
        defaultProjectId = 1,
        defaultDue = null,
        projects = listOf(ContextProject(1, "Inbox", "Inbox"), ContextProject(12, "Home", "Home")),
        labels = emptyList(),
        places = emptyList(),
    )

    private fun draft(ref: String, projectId: Long = 1, projectRef: String? = null) = CheckedDraft(
        ref = ref,
        title = "Task $ref",
        notes = "",
        due = null,
        dueHasTime = false,
        projectId = projectId,
        projectRef = projectRef,
        labels = emptyList(),
        priority = null,
        repeat = null,
        reminder = null,
        place = null,
        subtasks = emptyList(),
        guessed = emptyList(),
    )

    private val garden = CheckedProject("p1", "Garden", parentId = 12)
    private val patch = CheckedProject("p2", "Vegetable patch", parentRef = "p1")
    private val side = CheckedProject("p3", "Side business")

    @Test
    fun thePromptsAreTheDictationsWithTheBrainDumpAfter() {
        val organize = AiContract.prompt("dictation") + "\n\n" + AiContract.prompt("brainDump")
        assertEquals(organize, DictationRequests.brainDumpSystem(editing = false))
        assertEquals(organize + "\n\n" + AiContract.prompt("brainDumpEdit"), DictationRequests.brainDumpSystem(editing = true))
    }

    @Test
    fun thePlanBlockIsTheAnswerShape() {
        val plan = CheckResult(listOf(draft("d1", projectRef = "p1"), draft("d2", projectId = 12)), emptyList(), emptyList(), listOf(garden, patch))
        val json = DictationRequests.planJson(plan.projects, plan.drafts, context)
        assertEquals(
            """{"projects":[{"ref":"p1","title":"Garden","parent_id":12,"parent_ref":null},""" +
                """{"ref":"p2","title":"Vegetable patch","parent_id":null,"parent_ref":"p1"}],""" +
                """"tasks":[{"ref":"d1","title":"Task d1","notes":"","when":{"said":null,"date":null,"time":null},"project_id":null,"project_ref":"p1",""" +
                """"labels":[],"priority":null,"repeat":null,"reminder":null,"place":null,"subtasks":[],"unsure":[]},""" +
                """{"ref":"d2","title":"Task d2","notes":"","when":{"said":null,"date":null,"time":null},"project_id":12,"project_ref":null,""" +
                """"labels":[],"priority":null,"repeat":null,"reminder":null,"place":null,"subtasks":[],"unsure":[]}]}""",
            json,
        )
        // The plan block matches the answer schema, so the model sees its own shape.
        val answer = Json.parseToJsonElement(json.dropLast(1) + ""","unclear":[]}""").jsonObject
        assertNull(JsonSchema.firstProblem(answer, AiContract.brainDumpAnswerSchema))
        val message = DictationRequests.brainDumpMessage(context, "put the hose in Garden", plan)
        assertTrue(message.contains("</context>\n<plan>\n$json\n</plan>\n<transcript>\nput the hose in Garden\n</transcript>"))
        assertTrue(DictationRequests.brainDumpMessage(context, "a dump").endsWith("</context>\n<transcript>\na dump\n</transcript>"))
    }

    @Test
    fun removingANewProjectMovesWhatWasInItToItsParent() {
        val drafts = listOf(draft("d1", projectRef = "p1"), draft("d2", projectRef = "p2"), draft("d3", projectRef = "p3"))
        val (projects, moved) = BrainDumpChecks.removeProject("p1", listOf(garden, patch, side), drafts, defaultProjectId = 1)
        // Garden was under Home: its task goes to Home, and the vegetable patch with it.
        assertEquals(listOf(CheckedProject("p2", "Vegetable patch", parentId = 12), side), projects)
        assertEquals(listOf(12L to null, 1L to "p2", 1L to "p3"), moved.map { it.projectId to it.projectRef })

        // At the top level: its tasks go to the default project.
        val (_, topMoved) = BrainDumpChecks.removeProject("p3", listOf(garden, patch, side), drafts, defaultProjectId = 1)
        assertEquals(1L to null, topMoved[2].projectId to topMoved[2].projectRef)

        // A subproject: its tasks go to the new project above it.
        val (left, subMoved) = BrainDumpChecks.removeProject("p2", listOf(garden, patch, side), drafts, defaultProjectId = 1)
        assertEquals(listOf(garden, side), left)
        assertEquals("p1", subMoved[1].projectRef)
    }

    @Test
    fun thePlanListsProjectsInTheOrderOfTheirFirstTask() {
        val drafts = listOf(draft("d1", projectId = 12), draft("d2", projectRef = "p2"), draft("d3", projectId = 12), draft("d4", projectRef = "p1"))
        val groups = planGroups(listOf(garden, patch, side), drafts)
        assertEquals(listOf("project:12", "new:p2", "new:p1", "new:p3"), groups.map { it.key })
        assertEquals(listOf("d1", "d3"), groups[0].drafts.map { it.ref })
        assertEquals(emptyList(), groups[3].drafts)
    }

    @Test
    fun aNewProjectSaysWhereItGoes() {
        val projects = listOf(garden, patch, side)
        assertEquals("in Home", newProjectPlace(garden, projects, context))
        assertEquals("in Home / Garden", newProjectPlace(patch, projects, context))
        assertEquals("at the top level", newProjectPlace(side, projects, context))
        assertEquals("2 new projects, 1 task", planTitle(listOf(garden, side), listOf(draft("d1"))))
        assertEquals("3 tasks", planTitle(emptyList(), listOf(draft("d1"), draft("d2"), draft("d3"))))
        assertEquals("Added 2 projects and 14 tasks", DictationController.addedText(2, 14))
        assertEquals("Added 1 project", DictationController.addedText(1, 0))
        assertEquals("Added 1 task", DictationController.addedText(0, 1))
    }

    private fun writers(): Triple<PlanWriter, FakeProjectRepository, FakeTaskRepository> {
        val projects = FakeProjectRepository()
        var nextProject = 100L
        projects.createResult = { NetworkResult.Success(it.copy(id = nextProject++)) }
        val tasks = FakeTaskRepository()
        var nextTask = 500L
        tasks.createHandler = { NetworkResult.Success(it.copy(id = nextTask++)) }
        val drafts = DraftWriter(tasks, FakeLabelRepository(), NotificationPrefsStore(InMemoryPreferencesDataStore()), null)
        return Triple(PlanWriter(projects, drafts), projects, tasks)
    }

    @Test
    fun addCreatesTheProjectsParentsFirstThenTheTasksInThem() = runTest {
        val (writer, projects, tasks) = writers()
        val plan = CheckResult(
            listOf(draft("d1", projectRef = "p2"), draft("d2", projectId = 12), draft("d3", projectRef = "p1")),
            emptyList(),
            emptyList(),
            listOf(garden, patch),
        )
        val added = writer.add(plan, context.zone)
        assertNull(added.error)
        assertEquals(listOf("Garden" to 12L, "Vegetable patch" to 100L), projects.creates.map { it.title to it.parentProjectId })
        assertEquals(listOf(101L, 12L, 100L), tasks.created.map { it.projectId })
        assertEquals(listOf(100L, 101L), added.projectIds)

        writer.undo(added)
        assertEquals(listOf(502L, 501L, 500L), tasks.deleted)
        assertEquals(listOf(101L, 100L), projects.deletes)
    }

    @Test
    fun aProjectThatFailsStopsAddBeforeAnyTask() = runTest {
        val (writer, projects, tasks) = writers()
        projects.createResult = { p -> if (p.title == "Vegetable patch") NetworkResult.Error("offline") else NetworkResult.Success(p.copy(id = 100)) }
        val added = writer.add(CheckResult(listOf(draft("d1", projectRef = "p2")), emptyList(), emptyList(), listOf(garden, patch)), context.zone)
        assertEquals("Could not create the project “Vegetable patch” (offline). Nothing was added.", added.error)
        assertEquals(listOf(100L), projects.deletes)
        assertEquals(emptyList(), tasks.created)
    }
}
