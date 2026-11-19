package com.rendyhd.vicu.ai.dictation

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiToolsFixtureTest
import com.rendyhd.vicu.ai.notes.NotesDecision
import com.rendyhd.vicu.ai.notes.NotesDraft
import com.rendyhd.vicu.ai.notes.NotesFilter
import com.rendyhd.vicu.ai.notes.NotesPage
import com.rendyhd.vicu.ai.notes.NotesPlan
import com.rendyhd.vicu.ai.notes.NotesQuestion
import com.rendyhd.vicu.ai.notes.NotesRules
import com.rendyhd.vicu.ai.notes.OwnerAction
import com.rendyhd.vicu.util.CrossAppFixture
import com.rendyhd.vicu.util.pro.Person
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Notes to tasks (`docs/ai-assistant-v1.md`, section 4.9) against the `notes` vectors of
 * `ai-tools-v1.json` (the `checks` and `wire` vectors run in [AiToolsFixtureTest]), plus rules the
 * vectors do not pin.
 */
class NotesTest {
    private val notes: JsonObject =
        Json.parseToJsonElement(CrossAppFixture.readTestFixture("ai-tools-v1.json")).jsonObject.getValue("notes").jsonObject

    private fun vectors(key: String): List<JsonObject> = notes.getValue(key).jsonArray.map { it.jsonObject }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun pagesOf(e: JsonElement): List<NotesPage> = e.jsonArray.map { NotesPage(it.jsonObject.getValue("n").jsonPrimitive.int, it.jsonObject.getValue("text").jsonPrimitive.content) }

    /** A draft in the vectors' shape (a date-only due date is local 23:59:59). */
    private fun draftOf(o: JsonObject): NotesDraft {
        val due = str(o["due"])?.let { LocalDateTime.parse(it) }
        val projectRef = str(o["project_ref"])
        val draft = CheckedDraft(
            ref = o.getValue("ref").jsonPrimitive.content,
            title = o.getValue("title").jsonPrimitive.content,
            notes = o.getValue("notes").jsonPrimitive.content,
            due = due,
            dueHasTime = due != null && due.time != LocalTime(23, 59, 59),
            projectId = (o["project_id"] as? JsonPrimitive)?.longOrNull ?: 0L,
            projectRef = projectRef,
            labels = o.getValue("labels").jsonArray.map { l -> DraftLabel((l.jsonObject["id"] as? JsonPrimitive)?.longOrNull, l.jsonObject.getValue("title").jsonPrimitive.content) },
            priority = (o["priority"] as? JsonPrimitive)?.intOrNull,
            repeat = null,
            reminder = null,
            place = null,
            subtasks = o.getValue("subtasks").jsonArray.map { it.jsonPrimitive.content },
            guessed = o.getValue("guessed").jsonArray.map { it.jsonPrimitive.content },
        )
        return NotesDraft(draft, str(o["owner"]), (o["page"] as? JsonPrimitive)?.intOrNull, str(o["from"]))
    }

    private fun projectsOf(e: JsonElement): List<CheckedProject> = e.jsonArray.map {
        val o = it.jsonObject
        CheckedProject(o.getValue("ref").jsonPrimitive.content, o.getValue("title").jsonPrimitive.content, (o["parent_id"] as? JsonPrimitive)?.longOrNull, str(o["parent_ref"]))
    }

    private fun planOf(o: JsonObject): NotesPlan = NotesPlan(
        projects = projectsOf(o.getValue("projects")),
        drafts = o.getValue("drafts").jsonArray.map { draftOf(it.jsonObject) },
        decisions = o.getValue("decisions").jsonArray.map { val d = it.jsonObject; NotesDecision(d.getValue("what").jsonPrimitive.content, str(d["by"]), (d["page"] as? JsonPrimitive)?.intOrNull) },
        questions = o.getValue("questions").jsonArray.map { val q = it.jsonObject; NotesQuestion(q.getValue("what").jsonPrimitive.content, str(q["ask"]), (q["page"] as? JsonPrimitive)?.intOrNull) },
        dropped = o.getValue("dropped").jsonArray.map { val d = it.jsonObject; DroppedField(str(d["ref"]), d.getValue("field").jsonPrimitive.content, d.getValue("value")) },
        unclear = o.getValue("unclear").jsonArray.map { it.jsonPrimitive.content },
    )

    @Test
    fun batchVectors() {
        for (vector in vectors("batches")) {
            val expect = vector.getValue("expect").jsonArray.map { pagesOf(it) }
            assertEquals(expect, NotesRules.batches(pagesOf(vector.getValue("pages")), vector.getValue("max").jsonPrimitive.int), vector.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun mergeVectors() {
        val shape = AiToolsFixtureTest()
        for (vector in vectors("merge")) {
            val merged = NotesRules.merge(planOf(vector.getValue("plan").jsonObject), planOf(vector.getValue("batch").jsonObject))
            assertEquals(vector.getValue("expect").jsonObject, shape.notesJson(merged), vector.getValue("name").jsonPrimitive.content)
        }
    }

    /** The context as `DictationService.context` builds it: projects by path, labels and places by name. */
    private fun contextOf(c: JsonObject): DictationContext = DictationContext(
        now = LocalDateTime.parse(c.getValue("now").jsonPrimitive.content),
        zone = TimeZone.of(c.getValue("timeZone").jsonPrimitive.content),
        list = c.getValue("list").jsonPrimitive.content,
        defaultProjectId = c.getValue("default_project_id").jsonPrimitive.long,
        defaultDue = null,
        projects = c.getValue("projects").jsonArray.map {
            val o = it.jsonObject
            ContextProject(o.getValue("id").jsonPrimitive.long, o.getValue("title").jsonPrimitive.content, o.getValue("path").jsonPrimitive.content)
        }.sortedWith(compareBy({ it.path.lowercase() }, { it.path }, { it.id })),
        labels = c.getValue("labels").jsonArray.mapIndexed { i, l -> ContextLabel(i.toLong(), l.jsonPrimitive.content) }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.title })),
        places = c.getValue("places").jsonArray.map { ContextPlace(it.jsonPrimitive.content, it.jsonPrimitive.content) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.name })),
    )

    @Test
    fun requestVectors() {
        val keys = notes.getValue("contextKeys").jsonArray.map { it.jsonPrimitive.content }
        for (vector in vectors("requests")) {
            val name = vector.getValue("name").jsonPrimitive.content
            val c = vector.getValue("context").jsonObject
            val context = contextOf(c)
            val contextJson = NotesRules.contextJson(context, str(c["me"]), c.getValue("people").jsonArray.map { it.jsonPrimitive.content }, c.getValue("title").jsonPrimitive.content)
            val expect = vector.getValue("expect").jsonObject
            assertEquals(expect.getValue("contextJson").jsonPrimitive.content, contextJson, name)
            assertEquals(keys, Json.parseToJsonElement(contextJson).jsonObject.keys.toList(), name)
            val soFar = (vector["soFar"] as? JsonArray).orEmpty().map {
                val o = it.jsonObject
                KnownProject(o.getValue("ref").jsonPrimitive.content, o.getValue("title").jsonPrimitive.content, o.getValue("path").jsonPrimitive.content)
            }
            val plan = (vector["plan"] as? JsonObject)?.let { p ->
                NotesRules.planJson(projectsOf(p.getValue("projects")), p.getValue("drafts").jsonArray.map { draftOf(it.jsonObject) }, context)
            }
            val user = NotesRules.userMessage(contextJson, pagesOf(vector.getValue("pages")), soFar, plan, str(vector["words"]))
            assertEquals(expect.getValue("user").jsonPrimitive.content, user, name)
        }
    }

    @Test
    fun ownerVectors() {
        for (vector in vectors("owners")) {
            val value = vector["value"]
            assertEquals(str(vector["expect"]), NotesRules.ownerOf(value, str(vector["me"])), value.toString())
        }
    }

    private fun personOf(o: JsonObject) = Person(
        o.getValue("id").jsonPrimitive.long,
        o.getValue("title").jsonPrimitive.content,
        (o["aliases"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
    )

    private fun actionJson(action: OwnerAction): JsonObject = JsonObject(
        when (action) {
            OwnerAction.Mine -> mapOf("kind" to JsonPrimitive("mine"))
            OwnerAction.Name -> mapOf("kind" to JsonPrimitive("name"))
            is OwnerAction.Assign -> mapOf("kind" to JsonPrimitive("assign"), "userId" to JsonPrimitive(action.userId))
            is OwnerAction.Promise -> mapOf("kind" to JsonPrimitive("promise"), "personId" to JsonPrimitive(action.personId))
            is OwnerAction.Confirm -> mapOf("kind" to JsonPrimitive("confirm"), "personId" to JsonPrimitive(action.personId))
        },
    )

    @Test
    fun ownerActionsAndWhatEachFilterTicks() {
        val actions = notes.getValue("actions").jsonObject
        val world = actions.getValue("world").jsonObject
        val members = world.getValue("members").jsonArray.map { personOf(it.jsonObject) }
        val people = world.getValue("people").jsonArray.map { personOf(it.jsonObject) }
        for (case in actions.getValue("cases").jsonArray.map { it.jsonObject }) {
            val owner = str(case["owner"])
            val agreed = case.getValue("agreed").jsonPrimitive.content == "true"
            val action = NotesRules.defaultOwnerAction(owner, members, people, agreed)
            assertEquals(case.getValue("expect").jsonObject, actionJson(action), "$owner agreed=$agreed")
            val ticked = case.getValue("ticked").jsonObject
            for (filter in NotesFilter.entries) {
                assertEquals(ticked.getValue(filter.key).jsonPrimitive.content == "true", NotesRules.tickedBy(filter, action), "$owner ${filter.key}")
            }
        }
    }

    @Test
    fun readingAPage() {
        val read = notes.getValue("read").jsonObject
        for (v in read.getValue("answers").jsonArray.map { it.jsonObject }) {
            assertEquals(v.getValue("expect").jsonPrimitive.content, NotesRules.readPageAnswer(v.getValue("answer").jsonPrimitive.content))
        }
        for (v in read.getValue("userText").jsonArray.map { it.jsonObject }) {
            assertEquals(
                v.getValue("expect").jsonPrimitive.content,
                NotesRules.readPageUserText(v.getValue("page").jsonPrimitive.int, v.getValue("pages").jsonPrimitive.int, v.getValue("title").jsonPrimitive.content),
            )
        }
    }

    @Test
    fun sourceLinesAndNamedTitles() {
        for (v in vectors("sourceLines")) {
            val line = NotesRules.sourceLine(v.getValue("title").jsonPrimitive.content, v.getValue("date").jsonPrimitive.content, (v["page"] as? JsonPrimitive)?.intOrNull, str(v["from"]))
            val expect = v.getValue("expect").jsonObject
            assertEquals(expect.getValue("line").jsonPrimitive.content, line)
            assertEquals(expect.getValue("notes").jsonPrimitive.content, NotesRules.withSource(v.getValue("notes").jsonPrimitive.content, line))
        }
        for (v in vectors("namedTitles")) {
            assertEquals(v.getValue("expect").jsonPrimitive.content, NotesRules.namedTitle(v.getValue("name").jsonPrimitive.content, v.getValue("title").jsonPrimitive.content))
        }
    }

    @Test
    fun theSystemPromptIsTheBrainDumpsWithNotesAfter() {
        val organize = AiContract.prompt("dictation") + "\n\n" + AiContract.prompt("brainDump") + "\n\n" + AiContract.prompt("notes")
        assertEquals(organize, NotesRules.system(editing = false))
        assertEquals(organize + "\n\n" + AiContract.prompt("brainDumpEdit"), NotesRules.system(editing = true))
    }

    @Test
    fun theNextBatchKnowsThePlansNewProjectsWithTheirPaths() {
        val projects = listOf(
            CheckedProject("p1", "Offsite Q4", parentId = 12),
            CheckedProject("p2", "Hiring", parentRef = "p1"),
            CheckedProject("p3", "Travel"),
        )
        assertEquals(
            listOf(KnownProject("p1", "Offsite Q4", "Home / Offsite Q4"), KnownProject("p2", "Hiring", "Home / Offsite Q4 / Hiring"), KnownProject("p3", "Travel", "Travel")),
            NotesRules.knownProjects(projects) { if (it == 12L) "Home" else "" },
        )
    }

    @Test
    fun updateIsOfferedOnlyForAPlanOfOneBatchWithinTheBrainDumpLimit() {
        assertTrue(NotesRules.canUpdate(1, 60))
        assertFalse(NotesRules.canUpdate(1, 61))
        assertFalse(NotesRules.canUpdate(2, 3))
    }

    @Test
    fun aPageLongerThanTheLimitIsCutIntoPartsWithItsNumber() {
        val text = (0 until 50).joinToString("\n") { "Line $it" }
        val parts = NotesRules.batches(listOf(NotesPage(7, text)), 60).flatten()
        assertTrue(parts.all { it.n == 7 && it.text.length <= 60 })
        assertEquals(text, parts.joinToString("\n") { it.text })
    }

    @Test
    fun addCreatesOnlyTheNewProjectsThatHoldATickedTaskWithTheirParents() {
        val projects = listOf(CheckedProject("p1", "Offsite"), CheckedProject("p2", "Hiring", parentRef = "p1"), CheckedProject("p3", "Travel"))
        assertEquals(listOf("p1", "p2"), NotesRules.projectsInUse(projects, listOf("p2", null)).map { it.ref })
        assertEquals(emptyList(), NotesRules.projectsInUse(projects, emptyList()))
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
