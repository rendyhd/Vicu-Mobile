package com.rendyhd.vicu.ai.provider

import com.rendyhd.vicu.ai.AiContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One wire protocol: how a request is written and how its (streamed) answer is read. */
internal interface WireProtocol {
    /** The request body, leaving out the optional fields in [omit] (refused by this model before). */
    fun body(request: ChatRequest, omit: Set<String>): JsonObject

    fun reader(): AnswerReader
}

internal interface AnswerReader {
    /** True once the provider said the answer is over. */
    val done: Boolean

    /** One server-sent event's data. */
    fun onData(data: String): ChatEvent?

    /** A whole JSON answer, from a server that ignored streaming. */
    fun onWhole(body: String): ChatEvent?

    fun finish(): ChatEvent.Finished
}

/**
 * The system prompt as sent: in prompt mode the schema follows it, after a blank line and the line
 * `Answer with JSON that matches this schema:` (section 2.2), as compact JSON in the fixture's key order.
 */
internal fun systemText(request: ChatRequest): String {
    val schema = request.schema ?: return request.system
    if (request.structuredMode != StructuredMode.PROMPT) return request.system
    return request.system + "\n\n" + AiContract.message("promptSchemaIntro") + "\n" + schema.toString()
}

/** The name structured answers and the JSON schema format carry on the wire. */
internal const val SCHEMA_NAME = "answer"

// --- openai-chat ---------------------------------------------------------------------------

internal class OpenAiChatProtocol(private val json: Json) : WireProtocol {
    override fun body(request: ChatRequest, omit: Set<String>): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        putJsonArray("messages") {
            val system = systemText(request)
            if (system.isNotEmpty()) add(buildJsonObject { put("role", "system"); put("content", system) })
            for (message in request.messages) {
                when (message) {
                    is ChatMessage.User -> add(buildJsonObject { put("role", "user"); put("content", message.text) })
                    is ChatMessage.Assistant -> add(
                        buildJsonObject {
                            put("role", "assistant")
                            if (message.text.isEmpty() && message.toolCalls.isNotEmpty()) put("content", JsonNull) else put("content", message.text)
                            if (message.toolCalls.isNotEmpty()) {
                                putJsonArray("tool_calls") {
                                    for (call in message.toolCalls) {
                                        add(
                                            buildJsonObject {
                                                put("id", call.id)
                                                put("type", "function")
                                                putJsonObject("function") {
                                                    put("name", call.name)
                                                    put("arguments", call.arguments)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                    is ChatMessage.ToolResults -> for (result in message.results) {
                        add(
                            buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", result.callId)
                                put("content", result.content)
                            },
                        )
                    }
                }
            }
        }
        if (request.tools.isNotEmpty() && !request.toolsDisabled) {
            putJsonArray("tools") {
                for (tool in request.tools) {
                    add(
                        buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            }
                        },
                    )
                }
            }
        }
        val schema = request.schema
        if (schema != null && request.structuredMode == StructuredMode.JSON_SCHEMA) {
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", SCHEMA_NAME)
                    put("strict", true)
                    put("schema", schema)
                }
            }
        }
        if (request.deterministic) {
            if ("temperature" !in omit) put("temperature", 0)
            if ("reasoning_effort" !in omit) put("reasoning_effort", "low")
        }
    }

    override fun reader(): AnswerReader = Reader(json)

    private class PartialCall(var id: String? = null, var name: String = "", val arguments: StringBuilder = StringBuilder())

    private class Reader(private val json: Json) : AnswerReader {
        override var done = false
            private set
        private val text = StringBuilder()
        private val calls = linkedMapOf<Int, PartialCall>()
        private var stopReason: String? = null

        override fun onData(data: String): ChatEvent? {
            if (data.trim() == "[DONE]") {
                done = true
                return null
            }
            val o = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
            o["error"]?.let { throw LlmException(LlmException.Kind.SERVER, errorText(it)) }
            val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { stopReason = it }
            val delta = (choice["delta"] ?: choice["message"]) as? JsonObject ?: return null
            readCalls(delta)
            val piece = (delta["content"] as? JsonPrimitive)?.contentOrNull
            if (!piece.isNullOrEmpty()) {
                text.append(piece)
                return ChatEvent.TextDelta(piece)
            }
            return null
        }

        override fun onWhole(body: String): ChatEvent? {
            val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw LlmException(LlmException.Kind.BAD_ANSWER, "The answer could not be read.")
            o["error"]?.let { throw LlmException(LlmException.Kind.SERVER, errorText(it)) }
            val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            stopReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            val message = choice["message"] as? JsonObject ?: return null
            readCalls(message)
            (message["content"] as? JsonPrimitive)?.contentOrNull?.let { text.append(it) }
            done = true
            return null
        }

        private fun readCalls(delta: JsonObject) {
            val list = delta["tool_calls"] as? JsonArray ?: return
            list.forEachIndexed { position, element ->
                val call = element as? JsonObject ?: return@forEachIndexed
                val index = (call["index"] as? JsonPrimitive)?.intOrNull ?: position
                val partial = calls.getOrPut(index) { PartialCall() }
                call["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { partial.id = it }
                val function = call["function"] as? JsonObject ?: return@forEachIndexed
                function["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { partial.name = it }
                when (val arguments = function["arguments"]) {
                    is JsonPrimitive -> arguments.contentOrNull?.let { partial.arguments.append(it) }
                    is JsonObject -> partial.arguments.append(arguments.toString())
                    else -> Unit
                }
            }
        }

        override fun finish(): ChatEvent.Finished {
            if (stopReason == "content_filter") throw LlmException(LlmException.Kind.REFUSED, "The model declined to answer.")
            val toolCalls = calls.entries.mapNotNull { (index, call) ->
                if (call.name.isEmpty()) null else ToolCall(call.id ?: "call_$index", call.name, call.arguments.toString())
            }
            return ChatEvent.Finished(ChatMessage.Assistant(text.toString(), toolCalls), stopReason)
        }
    }
}

// --- anthropic-messages --------------------------------------------------------------------

internal class AnthropicMessagesProtocol(private val json: Json) : WireProtocol {
    override fun body(request: ChatRequest, omit: Set<String>): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_tokens", request.maxTokens)
        put("stream", true)
        systemText(request).takeIf { it.isNotEmpty() }?.let { put("system", it) }
        put("messages", messages(request.messages))
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (tool in request.tools) {
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.parameters)
                        },
                    )
                }
            }
            if (request.toolsDisabled) putJsonObject("tool_choice") { put("type", "none") }
        }
        val schema = request.schema?.takeIf { request.structuredMode == StructuredMode.JSON_SCHEMA }
        val effort = request.deterministic && "effort" !in omit
        if (schema != null || effort) {
            putJsonObject("output_config") {
                if (schema != null) {
                    putJsonObject("format") {
                        put("type", "json_schema")
                        put("schema", schema)
                    }
                }
                if (effort) put("effort", "low")
            }
        }
    }

    /** Anthropic wants turns that alternate: consecutive user parts (tool results, then a message) join. */
    private fun messages(messages: List<ChatMessage>): JsonArray {
        val turns = mutableListOf<Pair<String, MutableList<JsonElement>>>()
        fun add(role: String, blocks: List<JsonElement>) {
            val last = turns.lastOrNull()
            if (last != null && last.first == role) last.second += blocks else turns += role to blocks.toMutableList()
        }
        for (message in messages) {
            when (message) {
                is ChatMessage.User -> add("user", listOf(textBlock(message.text.ifEmpty { " " })))
                is ChatMessage.Assistant -> {
                    val raw = message.raw as? JsonArray
                    if (raw != null && raw.isNotEmpty()) {
                        add("assistant", raw)
                    } else {
                        val blocks = mutableListOf<JsonElement>()
                        if (message.text.isNotEmpty()) blocks += textBlock(message.text)
                        for (call in message.toolCalls) {
                            blocks += buildJsonObject {
                                put("type", "tool_use")
                                put("id", call.id)
                                put("name", call.name)
                                put("input", runCatching { json.parseToJsonElement(call.arguments) as JsonObject }.getOrElse { JsonObject(emptyMap()) })
                            }
                        }
                        if (blocks.isEmpty()) blocks += textBlock(" ")
                        add("assistant", blocks)
                    }
                }
                is ChatMessage.ToolResults -> add(
                    "user",
                    message.results.map { result ->
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", result.callId)
                            put("content", result.content)
                            if (result.isError) put("is_error", true)
                        }
                    },
                )
            }
        }
        return buildJsonArray {
            for ((role, blocks) in turns) {
                add(
                    buildJsonObject {
                        put("role", role)
                        put("content", JsonArray(blocks))
                    },
                )
            }
        }
    }

    private fun textBlock(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    override fun reader(): AnswerReader = Reader(json)

    private class Block(
        val type: String,
        val start: JsonObject,
        val text: StringBuilder = StringBuilder(),
        val input: StringBuilder = StringBuilder(),
        val thinking: StringBuilder = StringBuilder(),
        var signature: String? = null,
    )

    private class Reader(private val json: Json) : AnswerReader {
        override var done = false
            private set
        private val blocks = mutableMapOf<Int, Block>()
        private var stopReason: String? = null

        override fun onData(data: String): ChatEvent? {
            val o = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "error" -> throw LlmException(LlmException.Kind.SERVER, errorText(o["error"] ?: o))
                "content_block_start" -> {
                    val index = o["index"]?.jsonPrimitive?.intOrNull ?: blocks.size
                    val block = o["content_block"] as? JsonObject ?: return null
                    val type = block["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    val started = Block(type, block)
                    (block["text"] as? JsonPrimitive)?.contentOrNull?.let { started.text.append(it) }
                    blocks[index] = started
                    if (type == "text" && started.text.isNotEmpty()) return ChatEvent.TextDelta(started.text.toString())
                }
                "content_block_delta" -> {
                    val index = o["index"]?.jsonPrimitive?.intOrNull ?: return null
                    val block = blocks[index] ?: return null
                    val delta = o["delta"] as? JsonObject ?: return null
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            val piece = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            block.text.append(piece)
                            if (piece.isNotEmpty()) return ChatEvent.TextDelta(piece)
                        }
                        "input_json_delta" -> block.input.append(delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        "thinking_delta" -> block.thinking.append(delta["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        "signature_delta" -> block.signature = delta["signature"]?.jsonPrimitive?.contentOrNull
                    }
                }
                "message_delta" -> (o["delta"] as? JsonObject)?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let { stopReason = it }
                "message_stop" -> done = true
            }
            return null
        }

        override fun onWhole(body: String): ChatEvent? {
            val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw LlmException(LlmException.Kind.BAD_ANSWER, "The answer could not be read.")
            if (o["type"]?.jsonPrimitive?.contentOrNull == "error") throw LlmException(LlmException.Kind.SERVER, errorText(o["error"] ?: o))
            stopReason = o["stop_reason"]?.jsonPrimitive?.contentOrNull
            (o["content"] as? JsonArray)?.forEachIndexed { index, element ->
                val block = element as? JsonObject ?: return@forEachIndexed
                val type = block["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val b = Block(type, block)
                when (type) {
                    "text" -> b.text.append(block["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    "tool_use" -> b.input.append((block["input"] ?: JsonObject(emptyMap())).toString())
                    "thinking" -> {
                        b.thinking.append(block["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        b.signature = block["signature"]?.jsonPrimitive?.contentOrNull
                    }
                }
                blocks[index] = b
            }
            done = true
            return null
        }

        override fun finish(): ChatEvent.Finished {
            if (stopReason == "refusal") throw LlmException(LlmException.Kind.REFUSED, "The model declined to answer.")
            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            val raw = buildJsonArray {
                for (block in blocks.entries.sortedBy { it.key }.map { it.value }) {
                    when (block.type) {
                        "text" -> {
                            text.append(block.text)
                            add(buildJsonObject { put("type", "text"); put("text", block.text.toString()) })
                        }
                        "tool_use" -> {
                            val id = block.start["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val name = block.start["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val arguments = block.input.toString().ifBlank { "{}" }
                            calls += ToolCall(id, name, arguments)
                            add(
                                buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", id)
                                    put("name", name)
                                    put("input", runCatching { Json.parseToJsonElement(arguments) as JsonObject }.getOrElse { JsonObject(emptyMap()) })
                                },
                            )
                        }
                        "thinking" -> add(
                            buildJsonObject {
                                put("type", "thinking")
                                put("thinking", block.thinking.toString())
                                put("signature", block.signature.orEmpty())
                            },
                        )
                        // Anything else (redacted thinking, a newer block type) goes back as it came.
                        else -> add(block.start)
                    }
                }
            }
            return ChatEvent.Finished(ChatMessage.Assistant(text.toString(), calls, raw), stopReason)
        }
    }
}

private fun errorText(error: JsonElement): String = when (error) {
    is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull ?: "The provider reported an error."
    is JsonPrimitive -> error.contentOrNull ?: "The provider reported an error."
    else -> "The provider reported an error."
}
