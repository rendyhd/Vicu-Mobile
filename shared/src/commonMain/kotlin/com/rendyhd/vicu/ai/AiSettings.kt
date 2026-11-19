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
    /**
     * The model that reads pictures of notes (section 4.9.1), picked only when neither the dictation
     * nor Ask's model reads images; empty otherwise.
     */
    val readModel: String = "",
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
    /** The dictation sheet's Brain dump switch, as the person last left it (section 4.8). */
    val brainDump: Boolean = false,
    /** What each other preset was last set to, so choosing it again brings it back (section 7.2). */
    val remembered: Map<String, ProviderSetup> = emptyMap(),
    /** The saved addresses of each typed preset, the latest first (section 7.2). */
    val savedAddresses: Map<String, List<ProviderSetup>> = emptyMap(),
    /** Keys of typed presets are stored per address; false until a key stored per preset has moved. */
    val keysPerAddress: Boolean = false,
) {
    val isSetUp: Boolean get() = providerId != null && address.isNotBlank()

    /** The id the key is stored under: the preset, and for a typed preset its address too. */
    fun keyId(): String? = providerId?.let { keyIdFor(it, address) }

    /** The saved addresses of the preset in use. */
    fun saved(): List<ProviderSetup> = providerId?.let { savedAddresses[it] }.orEmpty()

    /** The address in use is one of the saved ones. */
    fun isAddressSaved(): Boolean = saved().any { sameAddress(it.address, address) }

    /** Saves the address in use with its models, first in the list, at most `saved_addresses_max`. */
    fun savingAddress(): AiSettings {
        val id = providerId ?: return this
        if (address.isBlank()) return this
        val entry = ProviderSetup(address, dictationModel, askModel, readModel)
        val rest = saved().filterNot { sameAddress(it.address, address) }
        return copy(savedAddresses = savedAddresses + (id to (listOf(entry) + rest).take(AiContract.limits.savedAddressesMax)))
    }

    /** The saved entry of the address in use follows a model change. */
    fun withSavedModels(): AiSettings {
        val id = providerId ?: return this
        val list = saved()
        if (list.none { sameAddress(it.address, address) }) return this
        return copy(
            savedAddresses = savedAddresses + (id to list.map {
                if (sameAddress(it.address, address)) it.copy(dictationModel = dictationModel, askModel = askModel, readModel = readModel) else it
            }),
        )
    }

    /** Another preset: what this one was set to is remembered, and the other's comes back (section 7.2). */
    fun choosingProvider(preset: ProviderPreset): AiSettings {
        if (providerId == preset.id) return this
        val kept = providerId?.let { remembered + (it to ProviderSetup(address, dictationModel, askModel, readModel)) } ?: remembered
        val back = kept[preset.id]
        // One model to start with: the quicker suggestion, for Ask too (Ask's own is a choice).
        val model = preset.suggestedDictation ?: preset.suggestedAsk ?: ""
        val fixed = preset.address.orEmpty()
        return copy(
            providerId = preset.id,
            address = if (preset.addressEditable) back?.address?.ifBlank { null } ?: fixed else fixed,
            dictationModel = back?.dictationModel?.ifBlank { null } ?: model,
            askModel = back?.askModel?.ifBlank { null } ?: model,
            readModel = back?.readModel.orEmpty(),
            dictationOn = false,
            askOn = false,
            talkOn = false,
            remembered = kept - preset.id,
        )
    }

    fun modelKey(model: String): String = "$providerId|${address.trimEnd('/')}|$model"

    fun consentKey(): String = "$providerId|${address.trimEnd('/')}"

    fun capabilities(model: String): ModelCapabilities? = tested[modelKey(model)]

    fun structuredMode(model: String): StructuredMode =
        StructuredMode.of(capabilities(model)?.structured) ?: StructuredMode.JSON_SCHEMA

    /** Whether the user agreed to send data here, or the address is a model on this device or network. */
    fun hasConsent(): Boolean = consentKey() in consented || isLocalAddress(address)

    /** The agreement to send pictures of pages (sections 4.9 and 7.1): `<provider>|<address>|images`. */
    fun imagesConsentKey(): String = consentKey() + "|images"

    fun hasImagesConsent(): Boolean = isLocalAddress(address) || (hasConsent() && imagesConsentKey() in consented)

    /** The dictation and Ask models, once each. */
    fun modelsInUse(): List<String> = listOf(dictationModel, askModel).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * The model that reads pictures of pages (section 4.9.1): the one picked for it, else the
     * dictation model when it reads images, else Ask's when it does. [ReadingModel.untested] lists
     * the models, in that order, never asked whether they read images (ask them before deciding);
     * a null model with none of them means the person picks one.
     */
    fun readingModel(): ReadingModel {
        val picked = readModel.trim()
        if (picked.isNotEmpty()) {
            val vision = capabilities(picked)?.vision
            return ReadingModel(if (vision == false) null else picked, if (vision == null) listOf(picked) else emptyList())
        }
        val untested = mutableListOf<String>()
        for (model in modelsInUse()) {
            when (capabilities(model)?.vision) {
                true -> return ReadingModel(model, untested)
                null -> untested += model
                false -> Unit
            }
        }
        return ReadingModel(null, untested)
    }

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

        fun keyIdFor(providerId: String, address: String): String =
            if (AiContract.provider(providerId)?.addressEditable == true) "$providerId|${address.trim().trimEnd('/')}" else providerId

        fun sameAddress(a: String, b: String): Boolean = a.trim().trimEnd('/') == b.trim().trimEnd('/')

        /**
         * A 404 or 405 from this typed address may mean its version path is missing (section 2.4): no
         * path segment starts with `v` and a digit, and the last one is not `api`.
         */
        fun mayLackVersionPath(address: String): Boolean {
            val rest = address.trim().substringAfter("://")
            val segments = rest.substringBefore('?').split('/').drop(1).filter { it.isNotEmpty() }
            if (segments.any { it.length >= 2 && it[0].lowercaseChar() == 'v' && it[1].isDigit() }) return false
            return !segments.lastOrNull().equals("api", ignoreCase = true)
        }
    }
}

/** What a preset was set to: its address and models (section 7.2). */
@Serializable
data class ProviderSetup(
    val address: String = "",
    val dictationModel: String = "",
    val askModel: String = "",
    val readModel: String = "",
)

/** The model that reads pictures of pages, and the models still to be asked (section 4.9.1). */
data class ReadingModel(val model: String?, val untested: List<String>)

@Serializable
data class ModelCapabilities(
    /** The structured mode that worked (`json_schema` or `prompt`), or null when neither did. */
    val structured: String? = null,
    /** The model called the probe tool. */
    val tools: Boolean = false,
    /** The model read the probe image (section 2.4); null: never asked. */
    val vision: Boolean? = null,
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
