package com.rendyhd.vicu.ai

import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.ai.provider.ChatRequest
import com.rendyhd.vicu.ai.provider.LlmClient
import com.rendyhd.vicu.ai.provider.LlmException
import com.rendyhd.vicu.ai.provider.ProviderConnection
import com.rendyhd.vicu.ai.provider.StructuredAnswers
import com.rendyhd.vicu.ai.provider.StructuredMode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What Test connection found (`docs/ai-assistant-v1.md`, section 2.4). */
data class ConnectionTest(
    /** The model list, or null when the provider has none or it failed. */
    val models: List<String>?,
    /** The structured mode that gave the expected answer, or null. */
    val structured: StructuredMode?,
    /** The model called the probe tool with the expected arguments. */
    val tools: Boolean,
    /** Why a step failed, for the settings screen. */
    val problems: List<String>,
) {
    val canDictate: Boolean get() = structured != null
    val canAsk: Boolean get() = structured != null && tools
}

/** The user's provider: building the connection from the settings and the key, and Test connection. */
class AiProviders(
    private val client: LlmClient,
    private val keys: AiKeyStore,
) {
    val structured = StructuredAnswers(client)

    val llm: LlmClient get() = client

    suspend fun connection(settings: AiSettings): ProviderConnection? {
        val preset = settings.providerId?.let { AiContract.provider(it) } ?: return null
        if (settings.address.isBlank()) return null
        return ProviderConnection(preset, settings.address, keys.getKey(preset.id))
    }

    suspend fun listModels(settings: AiSettings): List<String> {
        val connection = connection(settings) ?: return emptyList()
        return client.listModels(connection)
    }

    /** The model list, one structured answer (json_schema, then prompt) and one tool call. */
    suspend fun test(settings: AiSettings, model: String): ConnectionTest {
        val connection = connection(settings) ?: return ConnectionTest(null, null, false, listOf("Choose a provider and its address first."))
        val problems = mutableListOf<String>()
        val models = try {
            client.listModels(connection)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            if (e.kind == LlmException.Kind.AUTH) problems += "The key was not accepted: ${e.message}"
            null
        }

        val probe = AiContract.probe
        val structuredProbe = probe.obj("structured")
        val expected = structuredProbe.obj("expect")
        var structuredMode: StructuredMode? = null
        val first = settings.capabilities(model)?.structured?.let { StructuredMode.of(it) } ?: StructuredMode.JSON_SCHEMA
        for (mode in listOf(first) + StructuredMode.entries.filter { it != first }) {
            try {
                val answer = structured.request(
                    connection,
                    ChatRequest(
                        model = model,
                        system = "",
                        messages = listOf(ChatMessage.User(structuredProbe.str("user"))),
                        schema = structuredProbe.obj("schema"),
                        structuredMode = mode,
                        deterministic = true,
                        idleTimeoutSeconds = AiContract.limits.dictationIdleTimeoutSeconds,
                        maxTokens = PROBE_MAX_TOKENS,
                    ),
                )
                if (sameJson(answer, expected)) {
                    structuredMode = mode
                    break
                }
                problems += "The ${mode.wire} answer was $answer, not $expected."
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                problems += "${mode.wire}: ${e.message}"
                if (e.kind == LlmException.Kind.AUTH || e.kind == LlmException.Kind.NETWORK || e.kind == LlmException.Kind.NOT_FOUND) break
            }
        }

        val toolProbe = probe.obj("tool")
        val toolSpec = toolProbe.obj("tool")
        val tools = try {
            val answer = client.complete(
                connection,
                ChatRequest(
                    model = model,
                    system = "",
                    messages = listOf(ChatMessage.User(toolProbe.str("user"))),
                    tools = listOf(AskToolSpec(toolSpec.str("name"), "read", true, toolSpec.str("description"), toolSpec.obj("parameters"))),
                    idleTimeoutSeconds = AiContract.limits.askIdleTimeoutSeconds,
                    maxTokens = PROBE_MAX_TOKENS,
                ),
            ).message
            val call = answer.toolCalls.firstOrNull { it.name == toolSpec.str("name") }
            val arguments = call?.let { runCatching { Json.parseToJsonElement(it.arguments).jsonObject }.getOrNull() }
            val ok = arguments != null && sameJson(arguments, toolProbe.obj("expectArguments"))
            if (!ok) problems += "The model did not call the test tool."
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            problems += "Tools: ${e.message}"
            false
        }
        return ConnectionTest(models, structuredMode, tools, problems)
    }

    /** Numbers compare by value (5 and 5.0 are the same answer). */
    private fun sameJson(a: JsonObject, b: JsonObject): Boolean =
        a.keys == b.keys && a.all { (key, value) ->
            val other = b.getValue(key)
            value == other || value.toString().toDoubleOrNull()?.let { it == other.toString().toDoubleOrNull() } == true
        }

    private companion object {
        const val PROBE_MAX_TOKENS = 1_024
    }
}
