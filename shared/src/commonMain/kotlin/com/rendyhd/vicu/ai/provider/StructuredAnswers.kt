package com.rendyhd.vicu.ai.provider

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.JsonSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A JSON answer that matches a schema (`docs/ai-assistant-v1.md`, section 2.2): asked for in the
 * model's structured mode, read, checked against the schema, and repaired once.
 */
class StructuredAnswers(private val client: LlmClient) {

    /** The answer object. Throws [LlmException] (kind BAD_ANSWER after a failed repair). */
    suspend fun request(connection: ProviderConnection, request: ChatRequest): JsonObject {
        val schema = requireNotNull(request.schema)
        var messages = request.messages
        var problem: String
        var attempt = 0
        while (true) {
            val answer = client.complete(connection, request.copy(messages = messages)).message
            val parsed = parse(answer.text)
            problem = when {
                parsed == null -> "it is not a JSON object"
                else -> JsonSchema.firstProblem(parsed, schema) ?: return parsed
            }
            if (attempt++ >= AiContract.limits.repairAttempts) break
            messages = messages + ChatMessage.Assistant(answer.text) +
                ChatMessage.User(AiContract.message("repair").replace("{problem}", problem))
        }
        throw LlmException(LlmException.Kind.BAD_ANSWER, "The model's answer did not match: $problem.")
    }

    companion object {
        /** The answer's JSON object: from the first `{` to the last `}`. */
        fun parse(text: String): JsonObject? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull()
        }
    }
}
