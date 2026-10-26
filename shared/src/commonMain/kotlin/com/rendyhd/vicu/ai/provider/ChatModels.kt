package com.rendyhd.vicu.ai.provider

import com.rendyhd.vicu.ai.AskToolSpec
import com.rendyhd.vicu.ai.ProviderPreset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Where requests go: a preset, the address the user kept or typed, and the key (null when none). */
data class ProviderConnection(
    val preset: ProviderPreset,
    val address: String,
    val key: String?,
) {
    /** The address as given, without a trailing `/` (`docs/ai-assistant-v1.md`, section 2.1). */
    val base: String get() = address.trim().trimEnd('/')
}

/** One message of a conversation, in the app's own shape; each protocol writes it its own way. */
@Serializable
sealed interface ChatMessage {
    @Serializable
    @SerialName("user")
    data class User(val text: String) : ChatMessage

    /**
     * [raw] is the provider's own content as it came (Anthropic's blocks, with any thinking blocks
     * and their signatures), sent back unchanged so a model that reasons keeps its place.
     */
    @Serializable
    @SerialName("assistant")
    data class Assistant(
        val text: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val raw: JsonElement? = null,
    ) : ChatMessage

    /** The answers to the tool calls of the assistant message before it, in the same order. */
    @Serializable
    @SerialName("tools")
    data class ToolResults(val results: List<ToolResult>) : ChatMessage
}

@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String)

@Serializable
data class ToolResult(val callId: String, val name: String, val content: String, val isError: Boolean = false)

enum class StructuredMode(val wire: String) {
    JSON_SCHEMA("json_schema"),
    PROMPT("prompt");

    companion object {
        fun of(wire: String?): StructuredMode? = entries.firstOrNull { it.wire == wire }
    }
}

data class ChatRequest(
    val model: String,
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<AskToolSpec> = emptyList(),
    /** The answer's schema when the answer must be JSON (section 2.2). */
    val schema: JsonObject? = null,
    val structuredMode: StructuredMode = StructuredMode.JSON_SCHEMA,
    /** Dictation and repair: the lowest reasoning effort and temperature 0 where accepted (section 2.5). */
    val deterministic: Boolean = false,
    val idleTimeoutSeconds: Int,
    val maxTokens: Int,
    /**
     * The final answer after the tool rounds (section 5.5): no tool may be called. openai-chat
     * leaves the tools out; anthropic-messages keeps them (its history holds tool calls) with
     * `tool_choice` none.
     */
    val toolsDisabled: Boolean = false,
)

sealed interface ChatEvent {
    data class TextDelta(val text: String) : ChatEvent

    /** The whole answer once it ended. */
    data class Finished(val message: ChatMessage.Assistant, val stopReason: String?) : ChatEvent
}

class LlmException(
    val kind: Kind,
    message: String,
    val status: Int? = null,
) : Exception(message) {
    enum class Kind {
        /** No connection, or the address cannot be reached. */
        NETWORK,

        /** Nothing arrived for the idle timeout. */
        TIMEOUT,

        /** 401 or 403: the key is wrong or missing. */
        AUTH,

        /** 404: no such address or model. */
        NOT_FOUND,

        RATE_LIMIT,
        SERVER,
        BAD_REQUEST,

        /** The answer could not be read, or did not match the schema twice. */
        BAD_ANSWER,

        /** The model declined. */
        REFUSED,
    }
}
