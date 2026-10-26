package com.rendyhd.vicu.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.ai.provider.StructuredMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The assistant's settings on this device (`docs/ai-assistant-v1.md`, section 7.2): they belong to
 * the device, not the account, and never sync. The key is not here but in [AiKeyStore].
 */
@Serializable
data class AiSettings(
    /** A preset id of the fixture, or null before the user chose one (everything off). */
    val providerId: String? = null,
    /** The address requests go to; the preset's own unless it lets the user type one. */
    val address: String = "",
    val dictationModel: String = "",
    val askModel: String = "",
    val dictationOn: Boolean = false,
    val askOn: Boolean = false,
    val talkOn: Boolean = false,
    /** What Test connection found per model, keyed by [modelKey]. */
    val tested: Map<String, ModelCapabilities> = emptyMap(),
    /** Provider and address pairs the user agreed to send data to (section 7.1), keyed by [consentKey]. */
    val consented: Set<String> = emptySet(),
    /** The chosen speech model of `speech.models`, or null for the platform recogniser. */
    val speechModelId: String? = null,
    /** `auto` or an ISO 639-1 code (section 3.3). */
    val speechLanguage: String = "auto",
    /** Speech model downloads may use mobile data. */
    val allowMobileData: Boolean = false,
    /** Send the recording to the provider's transcription instead of local speech (section 3.6). */
    val providerTranscription: Boolean = false,
    /** Talk reads answers aloud. */
    val speakAnswers: Boolean = true,
) {
    val isSetUp: Boolean get() = providerId != null && address.isNotBlank()

    fun modelKey(model: String): String = "$providerId|${address.trimEnd('/')}|$model"

    fun consentKey(): String = "$providerId|${address.trimEnd('/')}"

    fun capabilities(model: String): ModelCapabilities? = tested[modelKey(model)]

    fun structuredMode(model: String): StructuredMode =
        StructuredMode.of(capabilities(model)?.structured) ?: StructuredMode.JSON_SCHEMA

    /** Whether the user agreed to send data here, or the address is a model on this device or network. */
    fun hasConsent(): Boolean = consentKey() in consented || isLocalAddress(address)

    /** Dictation can run: set up, switched on, a model that gave a structured answer, consent. */
    val dictationReady: Boolean
        get() = isSetUp && dictationOn && dictationModel.isNotBlank() && capabilities(dictationModel)?.structured != null && hasConsent()

    /** Ask can run: set up, switched on, a model that called a tool, consent. */
    val askReady: Boolean
        get() = isSetUp && askOn && askModel.isNotBlank() && capabilities(askModel)?.tools == true && hasConsent()

    val talkReady: Boolean get() = askReady && talkOn

    companion object {
        /**
         * A local server (section 7.1): localhost, a private IPv4 range, a `.local` or `.lan` name, or
         * a name without a dot. Its address skips the consent sheet; the http warning uses the opposite.
         */
        fun isLocalAddress(address: String): Boolean {
            val host = hostOf(address) ?: return false
            if (host == "localhost" || host.endsWith(".localhost") || host == "::1" || host == "[::1]") return true
            if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa") || host.endsWith(".internal")) return true
            if (!host.contains('.') && !host.contains(':')) return true
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size == 4) {
                val (a, b) = parts
                return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254) ||
                    (a == 100 && b in 64..127)
            }
            return false
        }

        fun hostOf(address: String): String? {
            val rest = address.trim().substringAfter("://", "").ifEmpty { return null }
            val authority = rest.substringBefore('/').substringAfterLast('@')
            if (authority.startsWith("[")) return authority.substringBefore(']') + "]"
            return authority.substringBefore(':').lowercase().ifEmpty { null }
        }

        /** Plain http to a server outside the local network: the setup screen's warning. */
        fun isInsecureRemote(address: String): Boolean =
            address.trim().startsWith("http://", ignoreCase = true) && !isLocalAddress(address)
    }
}

@Serializable
data class ModelCapabilities(
    /** The structured mode that worked (`json_schema` or `prompt`), or null when neither did. */
    val structured: String? = null,
    /** The model called the probe tool. */
    val tools: Boolean = false,
)

class AiSettingsStore(private val dataStore: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    val settings: Flow<AiSettings> = dataStore.data.map { prefs ->
        prefs[KEY]?.let { runCatching { json.decodeFromString(AiSettings.serializer(), it) }.getOrNull() } ?: AiSettings()
    }

    suspend fun current(): AiSettings = settings.first()

    suspend fun update(change: (AiSettings) -> AiSettings) {
        dataStore.edit { prefs ->
            val now = prefs[KEY]?.let { runCatching { json.decodeFromString(AiSettings.serializer(), it) }.getOrNull() } ?: AiSettings()
            prefs[KEY] = json.encodeToString(AiSettings.serializer(), change(now))
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val KEY = stringPreferencesKey("settings")
    }
}
