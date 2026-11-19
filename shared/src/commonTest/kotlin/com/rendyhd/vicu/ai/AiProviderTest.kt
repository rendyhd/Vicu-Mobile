package com.rendyhd.vicu.ai

import com.rendyhd.vicu.ai.provider.ChatEvent
import com.rendyhd.vicu.ai.provider.ChatImage
import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.ai.provider.ChatRequest
import com.rendyhd.vicu.ai.provider.LlmClient
import com.rendyhd.vicu.ai.provider.LlmException
import com.rendyhd.vicu.ai.provider.ProviderConnection
import com.rendyhd.vicu.ai.provider.StructuredAnswers
import com.rendyhd.vicu.ai.provider.StructuredMode
import com.rendyhd.vicu.ai.provider.ToolCall
import com.rendyhd.vicu.ai.provider.ToolResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiProviderTest {
    private val sse = headersOf(HttpHeaders.ContentType, "text/event-stream")
    private val requests = mutableListOf<Pair<String, JsonObject>>()
    private val headers = mutableListOf<Map<String, String>>()

    private fun engine(vararg bodies: Pair<HttpStatusCode, String>): MockEngine {
        var i = 0
        return MockEngine { request ->
            val text = request.body.toByteArray().decodeToString()
            requests += request.url.toString() to (if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject)
            headers += request.headers.entries().associate { it.key to it.value.joinToString() }
            val (status, body) = bodies[minOf(i++, bodies.size - 1)]
            respond(body, status, if (body.startsWith("data:") || body.startsWith("event:")) sse else headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private fun openai(engine: MockEngine) = LlmClient(engine) to ProviderConnection(AiContract.provider("openai")!!, "https://api.openai.com/v1/", "sk-test")

    private fun anthropic(engine: MockEngine) = LlmClient(engine) to ProviderConnection(AiContract.provider("anthropic")!!, "https://api.anthropic.com/v1", "sk-ant")

    private fun request(vararg messages: ChatMessage, schema: JsonObject? = null, mode: StructuredMode = StructuredMode.JSON_SCHEMA, tools: Boolean = false) = ChatRequest(
        model = "m",
        system = "System text",
        messages = messages.toList(),
        tools = if (tools) AiContract.askTools.filter { it.enabled } else emptyList(),
        schema = schema,
        structuredMode = mode,
        deterministic = schema != null,
        idleTimeoutSeconds = 5,
        maxTokens = 100,
    )

    @Test
    fun openAiStreamsTextAndAssemblesToolCallsByIndex() = runTest {
        val (client, connection) = openai(
            engine(
                HttpStatusCode.OK to """
                data: {"choices":[{"delta":{"content":"Hel"}}]}

                data: {"choices":[{"delta":{"content":"lo"}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_task","arguments":"{\"task_"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"id\":7}"}}]}}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """.trimIndent(),
            ),
        )
        val events = client.stream(connection, request(ChatMessage.User("Hi"), tools = true)).toList()
        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<ChatEvent.TextDelta>().map { it.text })
        val finished = events.filterIsInstance<ChatEvent.Finished>().single()
        assertEquals("Hello", finished.message.text)
        assertEquals(listOf(ToolCall("call_1", "get_task", """{"task_id":7}""")), finished.message.toolCalls)
        val (url, body) = requests.single()
        assertEquals("https://api.openai.com/v1/chat/completions", url)
        assertEquals("system", body.getValue("messages").jsonArray[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(7, body.getValue("tools").jsonArray.size)
        assertFalse("tool_choice" in body)
        assertEquals("Bearer sk-test", headers.single()["Authorization"])
    }

    @Test
    fun anthropicKeepsThinkingBlocksAndToolUseForTheNextTurn() = runTest {
        val (client, connection) = anthropic(
            engine(
                HttpStatusCode.OK to """
                event: message_start
                data: {"type":"message_start","message":{"id":"m1","content":[]}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Looking."}}

                event: content_block_start
                data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tu_1","name":"get_overview","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{}"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

                event: message_stop
                data: {"type":"message_stop"}

                """.trimIndent(),
            ),
        )
        val finished = client.complete(connection, request(ChatMessage.User("Hi"), tools = true))
        assertEquals("Looking.", finished.message.text)
        assertEquals("get_overview", finished.message.toolCalls.single().name)
        val raw = finished.message.raw!!.jsonArray
        assertEquals(listOf("thinking", "text", "tool_use"), raw.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("sig", raw[0].jsonObject.getValue("signature").jsonPrimitive.content)
        val h = headers.single()
        assertEquals("sk-ant", h["x-api-key"])
        assertEquals("2023-06-01", h["anthropic-version"])
        val body = requests.single().second
        assertEquals("System text", body.getValue("system").jsonPrimitive.content)
        assertFalse("temperature" in body)

        // The next turn sends the blocks back as they came, and the tool results with the next message in one user turn.
        requests.clear()
        val next = request(
            ChatMessage.User("Hi"),
            finished.message,
            ChatMessage.ToolResults(listOf(ToolResult("tu_1", "get_overview", "{}"))),
            ChatMessage.User(AiContract.message("finalAnswer")),
            tools = true,
        ).copy(toolsDisabled = true)
        runCatching { client.complete(connection, next) }
        val sent = requests.single().second
        val messages = sent.getValue("messages").jsonArray
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals(raw, messages[1].jsonObject.getValue("content"))
        assertEquals(listOf("tool_result", "text"), messages[2].jsonObject.getValue("content").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("none", sent.getValue("tool_choice").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun aRefusedOptionalFieldIsLeftOutAndRemembered() = runTest {
        val (client, connection) = openai(
            engine(
                HttpStatusCode.BadRequest to """{"error":{"message":"Unsupported parameter: 'temperature' is not supported with this model."}}""",
                HttpStatusCode.OK to """{"choices":[{"message":{"content":"{\"answer\":5}"},"finish_reason":"stop"}]}""",
            ),
        )
        val schema = AiContract.probe.obj("structured").obj("schema")
        val answer = StructuredAnswers(client).request(connection, request(ChatMessage.User("2+3"), schema = schema))
        assertEquals(5, answer.getValue("answer").jsonPrimitive.content.toInt())
        assertTrue("temperature" in requests[0].second)
        assertFalse("temperature" in requests[1].second)
        assertEquals("json_schema", requests[1].second.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun aWrongAnswerGetsOneRepairInPromptMode() = runTest {
        val (client, connection) = openai(
            engine(
                HttpStatusCode.OK to """{"choices":[{"message":{"content":"Sure! {\"answer\":\"five\"}"}}]}""",
                HttpStatusCode.OK to """{"choices":[{"message":{"content":"{\"answer\":5}"}}]}""",
            ),
        )
        val schema = AiContract.probe.obj("structured").obj("schema")
        val answer = StructuredAnswers(client).request(connection, request(ChatMessage.User("2+3"), schema = schema, mode = StructuredMode.PROMPT))
        assertEquals("5", answer.getValue("answer").jsonPrimitive.content)
        val first = requests[0].second
        assertFalse("response_format" in first)
        val system = first.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content
        assertEquals("System text\n\n${AiContract.message("promptSchemaIntro")}\n$schema", system)
        val repair = requests[1].second.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
        assertEquals("That answer did not match the schema: answer is not integer (wrong type). Answer again with JSON only.", repair)
    }

    @Test
    fun aSecondWrongAnswerFails() = runTest {
        val (client, connection) = openai(engine(HttpStatusCode.OK to """{"choices":[{"message":{"content":"no json here"}}]}"""))
        val schema = AiContract.probe.obj("structured").obj("schema")
        val e = assertFailsWith<LlmException> { StructuredAnswers(client).request(connection, request(ChatMessage.User("x"), schema = schema)) }
        assertEquals(LlmException.Kind.BAD_ANSWER, e.kind)
        assertEquals(2, requests.size)
    }

    @Test
    fun aWrongKeyIsAnAuthError() = runTest {
        val (client, connection) = anthropic(engine(HttpStatusCode.Unauthorized to """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        val e = assertFailsWith<LlmException> { client.listModels(connection) }
        assertEquals(LlmException.Kind.AUTH, e.kind)
        assertEquals("invalid x-api-key", e.message)
    }

    @Test
    fun localAddressesSkipConsent() {
        assertTrue(AiSettings.isLocalAddress("http://localhost:11434/v1"))
        assertTrue(AiSettings.isLocalAddress("http://192.168.1.20:3000/api"))
        assertTrue(AiSettings.isLocalAddress("http://nas.local:11434"))
        assertFalse(AiSettings.isLocalAddress("https://api.openai.com/v1"))
        assertTrue(AiSettings.isInsecureRemote("http://example.com/v1"))
        assertFalse(AiSettings.isInsecureRemote("http://10.0.0.5/v1"))
        assertNull(AiSettings.hostOf("not a url"))
    }

    /** Section 4.9.2: a picture's `detail` is an optional field, left out for that model after a 400 that names it. */
    @Test
    fun aRefusedPictureDetailIsLeftOutAndRemembered() = runTest {
        val (client, connection) = openai(
            engine(
                HttpStatusCode.BadRequest to """{"error":{"message":"Unrecognized request argument supplied: detail"}}""",
                HttpStatusCode.OK to "data: {\"choices\":[{\"delta\":{\"content\":\"Page text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
            ),
        )
        val message = ChatMessage.User("<page>1 of 1</page>", listOf(ChatImage("image/jpeg", "AAEC")))
        val answer = client.complete(connection, request(message))
        assertEquals("Page text", answer.message.text)
        val sent = requests.map { it.second.getValue("messages").jsonArray[1].toString() }
        assertTrue(sent[0].contains("\"detail\":\"high\""))
        assertFalse(sent[1].contains("detail"))
    }

    /** Section 4.9.1: the reading model is the one picked, else the first model in use that reads images. */
    @Test
    fun theReadingModelIsPickedOrTheFirstThatReadsPictures() {
        val base = AiSettings(providerId = "openai", address = "https://api.openai.com/v1", dictationModel = "d", askModel = "a")
        fun caps(vararg pairs: Pair<String, Boolean?>) = pairs.associate { (model, vision) -> base.modelKey(model) to ModelCapabilities("json_schema", true, vision) }
        assertEquals(ReadingModel(null, listOf("d", "a")), base.readingModel())
        assertEquals(ReadingModel("a", emptyList()), base.copy(tested = caps("d" to false, "a" to true)).readingModel())
        assertEquals(ReadingModel("a", listOf("d")), base.copy(tested = caps("a" to true)).readingModel())
        assertEquals(ReadingModel(null, emptyList()), base.copy(tested = caps("d" to false, "a" to false)).readingModel())
        assertEquals(ReadingModel("r", listOf("r")), base.copy(readModel = "r").readingModel())
        assertEquals(ReadingModel(null, emptyList()), base.copy(readModel = "r", tested = caps("r" to false)).readingModel())
        // Pictures need an agreement of their own, after the provider's.
        assertFalse(base.copy(consented = setOf(base.consentKey())).hasImagesConsent())
        assertTrue(base.copy(consented = setOf(base.consentKey(), base.imagesConsentKey())).hasImagesConsent())
        assertEquals("openai|https://api.openai.com/v1|images", base.imagesConsentKey())
    }
}
