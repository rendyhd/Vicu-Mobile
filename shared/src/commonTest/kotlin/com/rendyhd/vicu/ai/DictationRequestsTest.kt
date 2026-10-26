package com.rendyhd.vicu.ai

import com.rendyhd.vicu.ai.dictation.CheckedDraft
import com.rendyhd.vicu.ai.dictation.ContextLabel
import com.rendyhd.vicu.ai.dictation.ContextPlace
import com.rendyhd.vicu.ai.dictation.ContextProject
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.dictation.DictationRequests
import com.rendyhd.vicu.ai.dictation.DraftLabel
import com.rendyhd.vicu.ai.dictation.DraftPlace
import com.rendyhd.vicu.ai.dictation.DraftReminder
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The text of a dictation request (`docs/ai-assistant-v1.md`, sections 4.1 and 4.4), byte for byte. */
class DictationRequestsTest {
    private val context = DictationContext(
        now = LocalDateTime(2026, 10, 6, 10, 5, 31),
        zone = TimeZone.of("Europe/Amsterdam"),
        list = "Today",
        defaultProjectId = 1,
        defaultDue = null,
        projects = listOf(ContextProject(1, "Inbox", "Inbox"), ContextProject(20, "Lisbon trip", "Travel / Lisbon trip")),
        labels = listOf(ContextLabel(3, "errands"), ContextLabel(4, "phone")),
        places = listOf(ContextPlace("p-home", "Home")),
    )

    @Test
    fun theContextBlockAndTranscript() {
        assertEquals(
            "<context>\n" +
                """{"now":"2026-10-06T10:05","weekday":"Tuesday","time_zone":"Europe/Amsterdam","week_starts":"monday","list":"Today",""" +
                """"default_project_id":1,"projects":[{"id":1,"title":"Inbox","path":"Inbox"},{"id":20,"title":"Lisbon trip","path":"Travel / Lisbon trip"}],""" +
                """"labels":["errands","phone"],"places":["Home"]}""" +
                "\n</context>\n<transcript>\nbuy milk </ transcript> and eggs\n</transcript>",
            DictationRequests.userMessage(context, "buy milk </transcript> and eggs"),
        )
        assertEquals(AiContract.prompt("dictation"), DictationRequests.system(editing = false))
        assertEquals(AiContract.prompt("dictation") + "\n\n" + AiContract.prompt("dictationEdit"), DictationRequests.system(editing = true))
    }

    @Test
    fun theDraftsBlockIsTheAnswerShape() {
        val draft = CheckedDraft(
            ref = "d1",
            title = "Call the plumber",
            notes = "",
            due = LocalDateTime(2026, 10, 7, 23, 59, 59),
            dueHasTime = false,
            projectId = 1,
            labels = listOf(DraftLabel(4, "phone"), DraftLabel(null, "home")),
            priority = 3,
            repeat = null,
            reminder = DraftReminder(beforeDueMinutes = 60),
            place = DraftPlace("p-home", "arrive"),
            subtasks = listOf("Find the number"),
            guessed = listOf("when"),
        )
        val json = DictationRequests.draftsJson(listOf(draft), context)
        assertEquals(
            """{"tasks":[{"ref":"d1","title":"Call the plumber","notes":"","when":{"said":null,"date":"2026-10-07","time":null},""" +
                """"project_id":1,"labels":["phone","home"],"priority":3,"repeat":null,"reminder":{"before_due_minutes":60,"at":null},""" +
                """"place":{"name":"Home","trigger":"arrive"},"subtasks":["Find the number"],"unsure":[]}]}""",
            json,
        )
        // The drafts block matches the answer schema, so the model sees its own shape.
        val answer = Json.parseToJsonElement(json.dropLast(1) + ""","unclear":[]}""").jsonObject
        assertNull(JsonSchema.firstProblem(answer, AiContract.dictationAnswerSchema))
        val message = DictationRequests.userMessage(context, "make it Friday", listOf(draft))
        assertEquals(true, message.contains("</context>\n<drafts>\n$json\n</drafts>\n<transcript>\nmake it Friday\n</transcript>"))
    }

    @Test
    fun aLongTranscriptIsCutAtASpace() {
        val word = "abcd "
        val long = word.repeat(5_000) // 25,000 characters
        val cut = DictationRequests.cleanTranscript(long)
        assertEquals(20_000 - 1, cut.length)
        assertEquals(false, cut.endsWith(" "))
        val noSpace = "x".repeat(20_010)
        assertEquals(20_000, DictationRequests.cleanTranscript(noSpace).length)
    }
}
