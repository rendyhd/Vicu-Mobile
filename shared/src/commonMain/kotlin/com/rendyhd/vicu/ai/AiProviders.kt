package com.rendyhd.vicu.ai

import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.ai.provider.ChatRequest
import com.rendyhd.vicu.ai.provider.LlmClient
import com.rendyhd.vicu.ai.provider.LlmException
import com.rendyhd.vicu.ai.provider.ProviderConnection
import com.rendyhd.vicu.ai.provider.StructuredAnswers
import com.rendyhd.vicu.ai.provider.StructuredMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val settingsStore: AiSettingsStore,
) {
    val structured = StructuredAnswers(client)

    val llm: LlmClient get() = client

    private val keyMove = Mutex()

    suspend fun connection(settings: AiSettings): ProviderConnection? {
        val preset = settings.providerId?.let { AiContract.provider(it) } ?: return null
        if (settings.address.isBlank()) return null
        return ProviderConnection(preset, settings.address, key(settings))
    }

    /** The key for the preset and, when typed, the address of [settings] (section 7.2). */
    suspend fun key(settings: AiSettings): String? {
        val id = settings.keyId() ?: return null
        moveKeysToAddresses()
        return keys.getKey(id)
    }

    /** Stores [key] for the preset and address of [settings]; null or blank removes it. */
    suspend fun setKey(settings: AiSettings, key: String?) {
        val id = settings.keyId() ?: return
        moveKeysToAddresses()
        keys.setKey(id, key)
    }

    /** Forgets the key of a saved [address] of [providerId]. */
    suspend fun forgetKey(providerId: String, address: String) {
        moveKeysToAddresses()
        keys.setKey(AiSettings.keyIdFor(providerId, address), null)
    }

    /**
     * Keys of typed presets used to be stored per preset; once, each moves to the address it was last
     * used with: the address in use, else the preset's default (section 7.2).
     */
    private suspend fun moveKeysToAddresses() = keyMove.withLock {
        val settings = settingsStore.current()
        if (settings.keysPerAddress) return@withLock
        for (preset in AiContract.providers.filter { it.addressEditable }) {
            val key = keys.getKey(preset.id) ?: continue
            val address = if (settings.providerId == preset.id) settings.address else preset.address.orEmpty()
            if (address.isNotBlank()) keys.setKey(AiSettings.keyIdFor(preset.id, address), key)
            keys.setKey(preset.id, null)
        }
        settingsStore.update { it.copy(keysPerAddress = true) }
    }

    suspend fun listModels(settings: AiSettings): List<String> {
        val connection = connection(settings) ?: return emptyList()
        return try {
            client.listModels(connection)
        } catch (e: LlmException) {
            throw withAddressHint(e, connection)
        }
    }

    /** A 404 or 405 from a typed address without a version path says what is likely missing (section 2.4). */
    private fun withAddressHint(e: LlmException, connection: ProviderConnection): LlmException =
        if (lacksVersionPath(e, connection)) LlmException(e.kind, "${e.message} ${AiContract.message("addressVersionHint")}", e.status) else e

    private fun lacksVersionPath(e: LlmException, connection: ProviderConnection): Boolean =
        connection.preset.addressEditable && e.status in ADDRESS_STATUSES && AiSettings.mayLackVersionPath(connection.address)

    /** The model list, one structured answer (json_schema, then prompt) and one tool call. */
    suspend fun test(settings: AiSettings, model: String): ConnectionTest {
        val connection = connection(settings) ?: return ConnectionTest(null, null, false, listOf("Choose a provider and its address first."))
        val problems = mutableListOf<String>()
        var hint = false
        val models = try {
            client.listModels(connection)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            if (e.kind == LlmException.Kind.AUTH) problems += "The key was not accepted: ${e.message}"
            hint = hint || lacksVersionPath(e, connection)
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
                hint = hint || lacksVersionPath(e, connection)
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
            hint = hint || lacksVersionPath(e, connection)
            false
        }
        if (hint) problems += AiContract.message("addressVersionHint")
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
        val ADDRESS_STATUSES = setOf(404, 405)
    }
}
