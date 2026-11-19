package com.rendyhd.vicu.ai

import com.rendyhd.vicu.ai.dictation.BrainDumpChecks
import com.rendyhd.vicu.ai.dictation.CheckResult
import com.rendyhd.vicu.ai.dictation.ContextLabel
import com.rendyhd.vicu.ai.dictation.ContextPlace
import com.rendyhd.vicu.ai.dictation.ContextProject
import com.rendyhd.vicu.ai.dictation.DictationChecks
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.dictation.KnownProject
import com.rendyhd.vicu.ai.notes.NotesPage
import com.rendyhd.vicu.ai.notes.NotesPlan
import com.rendyhd.vicu.ai.notes.NotesRules
import com.rendyhd.vicu.ai.provider.AnthropicMessagesProtocol
import com.rendyhd.vicu.ai.provider.ChatImage
import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.ai.provider.ChatRequest
import com.rendyhd.vicu.ai.provider.OpenAiChatProtocol
import com.rendyhd.vicu.ai.organize.OrganizeChecks
import com.rendyhd.vicu.ai.organize.OrganizeResult
import com.rendyhd.vicu.ai.organize.OrganizeSession
import com.rendyhd.vicu.ai.organize.OrganizeTask
import com.rendyhd.vicu.ai.organize.PlanDayCheckInput
import com.rendyhd.vicu.ai.organize.PlanDayChecks
import com.rendyhd.vicu.ai.organize.PlanDayResult
import com.rendyhd.vicu.ai.talk.SpokenConfirmation
import com.rendyhd.vicu.speech.HintProject
import com.rendyhd.vicu.speech.VocabularyHint
import com.rendyhd.vicu.util.CrossAppFixture
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vectors of `test-fixtures/ai-tools-v1.json` (identical in the desktop repo), run against the
 * app's own code: the vocabulary hint, the dictation checks with the real quick-add parser, the
 * spoken confirmation, and the schema subset of everything a model sees.
 */
class AiToolsFixtureTest {
    private val fixture: JsonObject = Json.parseToJsonElement(CrossAppFixture.readTestFixture("ai-tools-v1.json")).jsonObject

    @Test
    fun theAppCarriesTheFixtureExactly() {
        assertEquals(fixture, AiContract.root)
    }

    @Test
    fun vocabularyHintVectors() {
        for (vector in fixture.getValue("speech").jsonObject.getValue("hintVectors").jsonArray.map { it.jsonObject }) {
            val projects = vector.getValue("projects").jsonArray.map { p ->
                val o = p.jsonObject
                HintProject(
                    title = o.getValue("title").jsonPrimitive.content,
                    inbox = o["inbox"]?.jsonPrimitive?.booleanOrNull ?: false,
                    writable = o["writable"]?.jsonPrimitive?.booleanOrNull ?: true,
                    archived = o["archived"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
            val labels = vector.getValue("labels").jsonArray.map { it.jsonPrimitive.content }
            val places = vector.getValue("places").jsonArray.map { it.jsonPrimitive.content }
            val expect = (vector["expect"] as? JsonPrimitive)?.contentOrNull
            assertEquals(expect, VocabularyHint.build(projects, labels, places), vector.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun dictationCheckVectors() {
        val dictation = fixture.getValue("dictation").jsonObject
        val defaultContext = dictation.getValue("context").jsonObject
        for (vector in dictation.getValue("checks").jsonArray.map { it.jsonObject }) {
            val name = vector.getValue("name").jsonPrimitive.content
            val context = contextOf(vector, (vector["context"] as? JsonObject) ?: defaultContext)
            val answer = vector.getValue("answer").jsonObject
            val actual = toJson(DictationChecks.check(answer, context))
            val expect = vector.getValue("expect").jsonObject
            assertEquals(expect, actual, name)
        }
    }

    private fun contextOf(vector: JsonObject, ctx: JsonObject): DictationContext {
        val zone = TimeZone.of("Europe/Amsterdam")
        return DictationContext(
            now = LocalDateTime.parse(vector.getValue("reference").jsonPrimitive.content),
            zone = zone,
            list = "Inbox",
            defaultProjectId = ctx.getValue("default_project_id").jsonPrimitive.long,
            defaultDue = (vector["defaultDue"] as? JsonPrimitive)?.contentOrNull?.let { LocalDate.parse(it) },
            projects = ctx.getValue("projects").jsonArray.map {
                val o = it.jsonObject
                ContextProject(o.getValue("id").jsonPrimitive.long, o.getValue("title").jsonPrimitive.content, o.getValue("path").jsonPrimitive.content)
            },
            labels = ctx.getValue("labels").jsonArray.map {
                val o = it.jsonObject
                ContextLabel(o.getValue("id").jsonPrimitive.long, o.getValue("title").jsonPrimitive.content)
            },
            places = ctx.getValue("places").jsonArray.map {
                val o = it.jsonObject
                ContextPlace(o.getValue("id").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content)
            },
            locale = vector["locale"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun brainDumpCheckVectors() {
        val ctx = fixture.getValue("dictation").jsonObject.getValue("context").jsonObject
        for (vector in fixture.getValue("brainDump").jsonObject.getValue("checks").jsonArray.map { it.jsonObject }) {
            val name = vector.getValue("name").jsonPrimitive.content
            val context = contextOf(vector, ctx)
            val answer = vector.getValue("answer").jsonObject
            assertNull(JsonSchema.firstProblem(answer, AiContract.brainDumpAnswerSchema), name)
            val actual = toJson(BrainDumpChecks.check(answer, context), brainDump = true)
            assertEquals(vector.getValue("expect").jsonObject, actual, name)
        }
    }

    /** Section 4.9.6, with the context of `dictation.context`, in each contract zone. */
    @Test
    fun notesCheckVectors() {
        val ctx = fixture.getValue("dictation").jsonObject.getValue("context").jsonObject
        for (zone in CrossAppFixture.zones) {
            for (vector in fixture.getValue("notes").jsonObject.getValue("checks").jsonArray.map { it.jsonObject }) {
                val name = vector.getValue("name").jsonPrimitive.content + " in " + zone.id
                val context = contextOf(vector, ctx).copy(zone = zone)
                val answer = vector.getValue("answer").jsonObject
                assertNull(JsonSchema.firstProblem(answer, AiContract.notesAnswerSchema), name)
                val result = NotesRules.check(
                    answer,
                    context,
                    pages = vector.getValue("pages").jsonArray.map { NotesPage(it.jsonObject.getValue("n").jsonPrimitive.int, it.jsonObject.getValue("text").jsonPrimitive.content) },
                    me = (vector["me"] as? JsonPrimitive)?.contentOrNull,
                    known = vector.getValue("known").jsonArray.map {
                        val o = it.jsonObject
                        KnownProject(o.getValue("ref").jsonPrimitive.content, o.getValue("title").jsonPrimitive.content, o.getValue("path").jsonPrimitive.content)
                    },
                    firstProjectNumber = vector.getValue("firstProjectNumber").jsonPrimitive.int,
                    firstDraftNumber = vector.getValue("firstDraftNumber").jsonPrimitive.int,
                )
                assertEquals(vector.getValue("expect").jsonObject, notesJson(result), name)
            }
        }
    }

    /** A plan of notes in the vectors' shape: the brain dump's with owner, page and from, decisions and questions. */
    internal fun notesJson(plan: NotesPlan): JsonObject {
        val base = toJson(CheckResult(plan.drafts.map { it.draft }, plan.dropped, plan.unclear, plan.projects), brainDump = true)
        val drafts = base.getValue("drafts").jsonArray.mapIndexed { i, element ->
            val d = plan.drafts[i]
            JsonObject(
                element.jsonObject + mapOf(
                    "owner" to (d.owner?.let { JsonPrimitive(it) } ?: JsonNull),
                    "page" to (d.page?.let { JsonPrimitive(it) } ?: JsonNull),
                    "from" to (d.from?.let { JsonPrimitive(it) } ?: JsonNull),
                ),
            )
        }
        return buildJsonObject {
            put("projects", base.getValue("projects"))
            put("drafts", JsonArray(drafts))
            putJsonArray("decisions") {
                for (d in plan.decisions) addJsonObject { put("what", d.what); put("by", d.by?.let { JsonPrimitive(it) } ?: JsonNull); put("page", d.page?.let { JsonPrimitive(it) } ?: JsonNull) }
            }
            putJsonArray("questions") {
                for (q in plan.questions) addJsonObject { put("what", q.what); put("ask", q.ask?.let { JsonPrimitive(it) } ?: JsonNull); put("page", q.page?.let { JsonPrimitive(it) } ?: JsonNull) }
            }
            put("dropped", base.getValue("dropped"))
            put("unclear", base.getValue("unclear"))
        }
    }

    /** Section 4.9.2: a user message with pictures on both wire protocols (`notes.wire`). */
    @Test
    fun notesWireVectors() {
        val json = Json { ignoreUnknownKeys = true }
        val preset = AiContract.providers.first()
        for (vector in fixture.getValue("notes").jsonObject.getValue("wire").jsonArray.map { it.jsonObject }) {
            val protocolName = vector.getValue("protocol").jsonPrimitive.content
            val m = vector.getValue("message").jsonObject
            val message = ChatMessage.User(
                m.getValue("text").jsonPrimitive.content,
                m.getValue("images").jsonArray.map { ChatImage(it.jsonObject.getValue("media_type").jsonPrimitive.content, it.jsonObject.getValue("data").jsonPrimitive.content) },
            )
            val request = ChatRequest(model = preset.id, system = "s", messages = listOf(message), idleTimeoutSeconds = 10, maxTokens = 100)
            val omit = vector.getValue("omit").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val (protocol, index) = if (protocolName == ProviderPreset.PROTOCOL_OPENAI) OpenAiChatProtocol(json) to 1 else AnthropicMessagesProtocol(json) to 0
            val sent = protocol.body(request, omit).getValue("messages").jsonArray[index]
            assertEquals(vector.getValue("expect"), sent, "$protocolName without $omit")
        }
        // No pictures: the content stays a plain string.
        val plain = OpenAiChatProtocol(json).body(ChatRequest(model = "m", system = "", messages = listOf(ChatMessage.User("hi")), idleTimeoutSeconds = 10, maxTokens = 10), emptySet())
        assertEquals(JsonPrimitive("hi"), plain.getValue("messages").jsonArray[0].jsonObject.getValue("content"))
    }

    @Test
    fun organizeCheckVectors() {
        val ctx = fixture.getValue("dictation").jsonObject.getValue("context").jsonObject
        for (vector in fixture.getValue("organize").jsonObject.getValue("checks").jsonArray.map { it.jsonObject }) {
            val name = vector.getValue("name").jsonPrimitive.content
            val context = contextOf(vector, ctx)
            val answer = vector.getValue("answer").jsonObject
            assertNull(JsonSchema.firstProblem(answer, AiContract.organizeAnswerSchema), name)
            val session = OrganizeSession.entries.first { it.id == vector.getValue("session").jsonPrimitive.content }
            val tasks = vector.getValue("tasks").jsonArray.map {
                val o = it.jsonObject
                OrganizeTask(
                    id = o.getValue("id").jsonPrimitive.long,
                    title = o.getValue("title").jsonPrimitive.content,
                    projectId = o.getValue("project_id").jsonPrimitive.long,
                    hasDue = o["due"] !is JsonNull,
                )
            }
            val actual = organizeJson(OrganizeChecks.check(answer, session, tasks, context))
            assertEquals(vector.getValue("expect").jsonObject, actual, name)
        }
    }

    @Test
    fun planDayCheckVectors() {
        for (vector in fixture.getValue("planDay").jsonObject.getValue("checks").jsonArray.map { it.jsonObject }) {
            val name = vector.getValue("name").jsonPrimitive.content
            val input = PlanDayCheckInput(
                free = vector.getValue("free").jsonArray.map { range ->
                    val r = range.jsonArray
                    r[0].jsonPrimitive.content to r[1].jsonPrimitive.content
                },
                defaultLength = vector.getValue("defaultLength").jsonPrimitive.int,
                leftovers = vector.getValue("leftovers").jsonArray.map { it.jsonPrimitive.long },
                candidates = vector.getValue("candidates").jsonArray.map { it.jsonPrimitive.long },
            )
            val actual = planDayJson(PlanDayChecks.check(vector.getValue("answer").jsonObject, input))
            assertEquals(vector.getValue("expect").jsonObject, actual, name)
        }
    }

    private fun planDayJson(result: PlanDayResult): JsonObject = buildJsonObject {
        putJsonArray("leftovers") {
            for (item in result.leftovers) {
                addJsonObject {
                    put("task_id", item.taskId)
                    put("action", item.action.id)
                    put("reason", item.reason)
                }
            }
        }
        putJsonArray("picks") {
            for (item in result.picks) {
                addJsonObject {
                    put("task_id", item.taskId)
                    put("minutes", item.minutes)
                    put("start", item.start)
                    put("reason", item.reason)
                }
            }
        }
        putJsonArray("can_wait") {
            for (item in result.canWait) {
                addJsonObject {
                    put("task_id", item.taskId)
                    put("reason", item.reason)
                }
            }
        }
        putJsonArray("dropped") {
            for (item in result.dropped) {
                addJsonObject {
                    put("task_id", item.taskId)
                    put("field", item.field)
                    put("value", item.value)
                }
            }
        }
    }

    private fun organizeJson(result: OrganizeResult): JsonObject = buildJsonObject {
        put(
            "suggestions",
            buildJsonArray {
                for (s in result.suggestions) {
                    add(
                        buildJsonObject {
                            put("task_id", s.taskId)
                            put("title", s.title?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("project_id", s.projectId?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("due", s.due?.let { JsonPrimitive(wall(it)) } ?: JsonNull)
                            put("due_has_time", s.dueHasTime)
                            put("clear_due", s.clearDue)
                            put("close", s.close?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("reason", s.reason)
                        },
                    )
                }
            },
        )
        put(
            "next_step",
            result.nextStep?.let { n ->
                buildJsonObject {
                    put("task_id", n.taskId?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("title", n.title?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("due", n.due?.let { JsonPrimitive(wall(it)) } ?: JsonNull)
                    put("due_has_time", n.dueHasTime)
                }
            } ?: JsonNull,
        )
        put(
            "dropped",
            buildJsonArray {
                for (d in result.dropped) {
                    add(
                        buildJsonObject {
                            put("task_id", d.taskId?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("field", d.field)
                            put("value", d.value)
                        },
                    )
                }
            },
        )
    }

    @Test
    fun startersCoverEveryListKind() {
        for (kind in listOf("today", "upcoming", "inbox", "project", "other")) {
            assertTrue(AiContract.starters(kind).isNotEmpty(), kind)
        }
    }

    @Test
    fun spokenConfirmationVectors() {
        for (vector in fixture.getValue("talk").jsonObject.getValue("confirmVectors").jsonArray.map { it.jsonObject }) {
            val text = vector.getValue("text").jsonPrimitive.content
            val expect = vector.getValue("expect").jsonPrimitive.content
            val actual = SpokenConfirmation.answer(text, vector.getValue("language").jsonPrimitive.content).name.lowercase()
            assertEquals(expect, actual, text)
        }
    }

    @Test
    fun everySchemaAModelSeesIsInsideTheStrictSubset() {
        val keywords = fixture.getValue("schemaSubset").jsonObject.getValue("keywords").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val schemas = mutableMapOf(
            "dictation.answerSchema" to AiContract.dictationAnswerSchema,
            "brainDump.answerSchema" to AiContract.brainDumpAnswerSchema,
            "notes.answerSchema" to AiContract.notesAnswerSchema,
            "organize.answerSchema" to AiContract.organizeAnswerSchema,
            "planDay.answerSchema" to AiContract.planDayAnswerSchema,
        )
        for (tool in AiContract.askTools) schemas["tool ${tool.name}"] = tool.parameters
        val probe = fixture.getValue("probe").jsonObject
        schemas["probe.structured"] = probe.getValue("structured").jsonObject.getValue("schema").jsonObject
        schemas["probe.tool"] = probe.getValue("tool").jsonObject.getValue("tool").jsonObject.getValue("parameters").jsonObject
        schemas["probe.vision"] = probe.getValue("vision").jsonObject.getValue("schema").jsonObject
        for ((name, schema) in schemas) {
            assertEquals(emptyList(), JsonSchema.subsetProblems(schema, keywords), name)
        }
        assertEquals(AiContract.askTools.size, AiContract.askTools.map { it.name }.toSet().size, "tool names are unique")
    }

    @Test
    fun promptsAreTheirLinesJoined() {
        for ((name, value) in fixture.getValue("prompts").jsonObject) {
            val lines = value.jsonObject.getValue("lines").jsonArray.joinToString("\n") { it.jsonPrimitive.content }
            assertEquals(lines, AiContract.prompt(name))
        }
        assertTrue(AiContract.message("repair").contains("{problem}"))
    }

    @Test
    fun schemaValidatorFindsTheFirstProblem() {
        val schema = AiContract.dictationAnswerSchema
        val missing = buildJsonObject { put("tasks", JsonArray(emptyList())) }
        assertEquals("unclear is missing", JsonSchema.firstProblem(missing, schema))
        val extra = buildJsonObject {
            put("tasks", JsonArray(emptyList()))
            put("unclear", JsonArray(emptyList()))
            put("more", true)
        }
        assertEquals("more is not allowed", JsonSchema.firstProblem(extra, schema))
        val wrong = Json.parseToJsonElement("""{"tasks":[{"ref":"d1","title":"A","notes":"","when":{"said":null,"date":3,"time":null},"project_id":null,"labels":[],"priority":null,"repeat":null,"reminder":null,"place":null,"subtasks":[],"unsure":[]}],"unclear":[]}""").jsonObject
        assertEquals("tasks[0].when.date is not string or null", JsonSchema.firstProblem(wrong, schema))
    }

    private fun toJson(result: CheckResult, brainDump: Boolean = false): JsonObject = buildJsonObject {
        if (brainDump) {
            put(
                "projects",
                buildJsonArray {
                    for (p in result.projects) {
                        add(
                            buildJsonObject {
                                put("ref", p.ref)
                                put("title", p.title)
                                put("parent_id", p.parentId?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("parent_ref", p.parentRef?.let { JsonPrimitive(it) } ?: JsonNull)
                            },
                        )
                    }
                },
            )
        }
        put(
            "drafts",
            buildJsonArray {
                for (d in result.drafts) {
                    add(
                        buildJsonObject {
                            put("ref", d.ref)
                            put("title", d.title)
                            put("notes", d.notes)
                            put("due", d.due?.let { JsonPrimitive(wall(it)) } ?: JsonNull)
                            if (brainDump) {
                                put("project_id", if (d.projectRef != null) JsonNull else JsonPrimitive(d.projectId))
                                put("project_ref", d.projectRef?.let { JsonPrimitive(it) } ?: JsonNull)
                            } else {
                                put("project_id", d.projectId)
                            }
                            put("labels", buildJsonArray { d.labels.forEach { l -> add(buildJsonObject { put("id", l.id?.let { JsonPrimitive(it) } ?: JsonNull); put("title", l.title) }) } })
                            put("priority", d.priority?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("repeat", d.repeat?.let { r -> buildJsonObject { put("every", r.every); put("unit", r.unit) } } ?: JsonNull)
                            put(
                                "reminder",
                                d.reminder?.let { r ->
                                    buildJsonObject {
                                        r.beforeDueMinutes?.let { put("before_due_minutes", it) }
                                        r.at?.let { put("at", wall(it)) }
                                    }
                                } ?: JsonNull,
                            )
                            put("place", d.place?.let { p -> buildJsonObject { put("place_id", p.placeId); put("trigger", p.trigger) } } ?: JsonNull)
                            put("subtasks", buildJsonArray { d.subtasks.forEach { add(JsonPrimitive(it)) } })
                            put("guessed", buildJsonArray { d.guessed.forEach { add(JsonPrimitive(it)) } })
                        },
                    )
                }
            },
        )
        put(
            "dropped",
            buildJsonArray {
                result.dropped.forEach { d ->
                    add(buildJsonObject { put("ref", d.ref?.let { JsonPrimitive(it) } ?: JsonNull); put("field", d.field); put("value", d.value) })
                }
            },
        )
        put("unclear", buildJsonArray { result.unclear.forEach { add(JsonPrimitive(it)) } })
    }

    private fun wall(t: LocalDateTime): String =
        "${t.date}T${t.hour.pad()}:${t.minute.pad()}:${t.second.pad()}"

    private fun Int.pad() = toString().padStart(2, '0')

    @Suppress("unused")
    private fun JsonElement.str() = jsonPrimitive.content
}
