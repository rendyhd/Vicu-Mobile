package com.rendyhd.vicu.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiKeyStore
import com.rendyhd.vicu.ai.AiProviders
import com.rendyhd.vicu.ai.AiSettings
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.ai.ConnectionTest
import com.rendyhd.vicu.ai.ModelCapabilities
import com.rendyhd.vicu.ai.ask.ConversationStore
import com.rendyhd.vicu.ai.dictation.KeptTranscriptStore
import com.rendyhd.vicu.ai.provider.LlmException
import com.rendyhd.vicu.speech.ModelFileState
import com.rendyhd.vicu.speech.SpeechEngineHost
import com.rendyhd.vicu.speech.SpeechModelManager
import com.rendyhd.vicu.speech.SpeechModelSpec
import com.rendyhd.vicu.speech.SpeechStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A feature waiting for the consent sheet before it is switched on (section 7.1). */
enum class AssistantFeature { DICTATION, ASK, TALK }

data class AssistantUiState(
    val settings: AiSettings = AiSettings(),
    val hasKey: Boolean = false,
    val models: List<String> = emptyList(),
    val loadingModels: Boolean = false,
    val modelsError: String? = null,
    val testing: Boolean = false,
    val test: ConnectionTest? = null,
    val testedModel: String? = null,
    val speechStates: Map<String, ModelFileState> = emptyMap(),
    val suggestedSpeechModel: String? = null,
    /** The native speech engine is on this device. */
    val localSpeechAvailable: Boolean = false,
    val consentFor: AssistantFeature? = null,
    val message: String? = null,
)

/**
 * The assistant's settings (`docs/ai-assistant-v1.md`, sections 2, 3.5 and 7): the provider, its
 * address and key, the models, Test connection, the features, the speech model, and the consent
 * sheet before a cloud provider is first used.
 */
class AssistantSettingsViewModel(
    private val store: AiSettingsStore,
    private val keys: AiKeyStore,
    private val providers: AiProviders,
    private val speechModels: SpeechModelManager,
    private val speechHost: SpeechEngineHost,
    private val speechStorage: SpeechStorage,
    private val conversations: ConversationStore,
    private val keptTranscripts: KeptTranscriptStore,
) : ViewModel() {
    private val local = MutableStateFlow(AssistantUiState())

    val uiState: StateFlow<AssistantUiState> = combine(local, store.settings, speechModels.states) { ui, settings, speech ->
        ui.copy(settings = settings, speechStates = speech)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssistantUiState())

    private var testJob: Job? = null

    init {
        speechModels.refresh()
        local.update {
            it.copy(
                localSpeechAvailable = speechHost.isAvailable,
                suggestedSpeechModel = SpeechModelSpec.suggestedForPhone(speechStorage.totalMemoryBytes()).id,
            )
        }
        viewModelScope.launch { refreshKey() }
    }

    private suspend fun refreshKey() {
        val settings = store.current()
        if (settings.providerId == null) return local.update { it.copy(hasKey = false) }
        local.update { it.copy(hasKey = !providers.key(settings).isNullOrBlank()) }
    }

    /** Another preset; what it was set to before comes back (section 7.2). */
    fun chooseProvider(id: String) {
        val preset = AiContract.provider(id) ?: return
        viewModelScope.launch {
            store.update { it.choosingProvider(preset) }
            local.update { it.copy(models = emptyList(), test = null, modelsError = null) }
            refreshKey()
        }
    }

    /**
     * Save address of a typed preset. [replacing] (Edit) puts the address in place of the one in use,
     * which takes its key along; otherwise (Add address) it is saved next to it.
     */
    fun saveAddress(address: String, replacing: Boolean) {
        val typed = address.trim()
        if (typed.isEmpty()) return
        viewModelScope.launch {
            val before = store.current()
            val id = before.providerId ?: return@launch
            if (AiSettings.sameAddress(before.address, typed)) {
                store.update { it.savingAddress() }
                return@launch
            }
            val oldKey = if (replacing) providers.key(before) else null
            store.update { s ->
                val kept = if (replacing) {
                    s.copy(savedAddresses = s.savedAddresses + (id to s.saved().filterNot { AiSettings.sameAddress(it.address, s.address) }))
                } else {
                    s.savingAddress()
                }
                kept.copy(address = typed).savingAddress()
            }
            if (replacing && before.address.isNotBlank()) {
                val now = store.current()
                if (!oldKey.isNullOrBlank() && providers.key(now).isNullOrBlank()) providers.setKey(now, oldKey)
                providers.forgetKey(id, before.address)
            }
            local.update { it.copy(models = emptyList(), test = null, modelsError = null) }
            refreshKey()
            testNewModels()
        }
    }

    /** A saved address of the preset in use, with the models and key it was used with. */
    fun useSavedAddress(address: String) {
        viewModelScope.launch {
            store.update { s ->
                val entry = s.saved().firstOrNull { AiSettings.sameAddress(it.address, address) } ?: return@update s
                s.withSavedModels().copy(
                    address = entry.address,
                    dictationModel = entry.dictationModel.ifBlank { s.dictationModel },
                    askModel = entry.askModel.ifBlank { s.askModel },
                ).savingAddress()
            }
            local.update { it.copy(models = emptyList(), test = null, modelsError = null) }
            refreshKey()
        }
    }

    /** Forgets a saved address and its key; the address in use stays. */
    fun removeSavedAddress(address: String) {
        viewModelScope.launch {
            val s = store.current()
            val id = s.providerId ?: return@launch
            if (AiSettings.sameAddress(s.address, address)) return@launch
            store.update { it.copy(savedAddresses = it.savedAddresses + (id to it.saved().filterNot { e -> AiSettings.sameAddress(e.address, address) })) }
            providers.forgetKey(id, address)
        }
    }

    fun setKey(key: String) {
        viewModelScope.launch {
            val settings = store.current()
            if (settings.providerId == null) return@launch
            providers.setKey(settings, key.trim().ifEmpty { null })
            local.update { it.copy(test = null, message = if (key.isBlank()) "Key removed" else "Key saved on this device") }
            refreshKey()
            testNewModels()
        }
    }

    fun setDictationModel(model: String) {
        viewModelScope.launch {
            store.update { it.copy(dictationModel = model.trim()).withSavedModels() }
            testNewModels()
        }
    }

    /** The one model: dictation's, and Ask's too unless Ask has its own ([askToo] false). */
    fun setModel(model: String, askToo: Boolean) {
        viewModelScope.launch {
            store.update { it.copy(dictationModel = model.trim(), askModel = if (askToo) model.trim() else it.askModel).withSavedModels() }
            testNewModels()
        }
    }

    fun setAskModel(model: String) {
        viewModelScope.launch {
            store.update { it.copy(askModel = model.trim()).withSavedModels() }
            testNewModels()
        }
    }

    /**
     * A model chosen or a key saved: models not tested yet are tested at once, so the features work
     * (and Ask shows next to Search) without pressing Test connection.
     */
    private suspend fun testNewModels() {
        val s = store.current()
        val preset = s.providerId?.let { AiContract.provider(it) } ?: return
        if (!s.isSetUp || (preset.keyRequired && !local.value.hasKey)) return
        if (listOf(s.dictationModel, s.askModel).any { it.isNotBlank() && s.capabilities(it) == null }) test()
    }

    fun loadModels() {
        viewModelScope.launch {
            local.update { it.copy(loadingModels = true, modelsError = null) }
            try {
                val models = providers.listModels(store.current())
                local.update { it.copy(models = models, loadingModels = false, modelsError = if (models.isEmpty()) "The provider lists no models. Type the model's name." else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                local.update { it.copy(loadingModels = false, modelsError = e.message) }
            }
        }
    }

    /** Test connection on the dictation model, and on the Ask model when it is another one. */
    fun test() {
        testJob?.cancel()
        testJob = viewModelScope.launch {
            local.update { it.copy(testing = true, test = null) }
            val settings = store.current()
            val models = listOf(settings.dictationModel, settings.askModel).filter { it.isNotBlank() }.distinct()
            if (models.isEmpty()) {
                local.update { it.copy(testing = false, message = "Choose a model first") }
                return@launch
            }
            var last: ConnectionTest? = null
            for (model in models) {
                val result = providers.test(store.current(), model)
                last = if (last == null) result else ConnectionTest(
                    models = result.models ?: last.models,
                    structured = result.structured,
                    tools = result.tools,
                    problems = last.problems + result.problems,
                )
                store.update { s ->
                    s.copy(tested = s.tested + (s.modelKey(model) to ModelCapabilities(result.structured?.wire, result.tools)))
                }
                if (result.models != null && local.value.models.isEmpty()) local.update { it.copy(models = result.models) }
            }
            local.update { it.copy(testing = false, test = last, testedModel = models.joinToString(", ")) }
        }
    }

    /** Switching a feature on asks for consent first when the provider is not on this device or network. */
    fun setFeature(feature: AssistantFeature, on: Boolean) {
        viewModelScope.launch {
            val settings = store.current()
            if (on && !settings.hasConsent()) {
                local.update { it.copy(consentFor = feature) }
                return@launch
            }
            apply(feature, on)
        }
    }

    fun answerConsent(agreed: Boolean) {
        val feature = local.value.consentFor ?: return
        local.update { it.copy(consentFor = null) }
        if (!agreed) return
        viewModelScope.launch {
            store.update { it.copy(consented = it.consented + it.consentKey()) }
            apply(feature, true)
        }
    }

    private suspend fun apply(feature: AssistantFeature, on: Boolean) {
        val before = store.current()
        val model = if (feature == AssistantFeature.DICTATION) before.dictationModel else before.askModel
        // Not tested yet: test it now rather than asking the user to.
        if (on && model.isNotBlank() && before.capabilities(model) == null) {
            test()
            testJob?.join()
        }
        val s = store.current()
        // The test's own words say best why it failed (no key, no connection, a refused request).
        val why = local.value.test?.problems?.firstOrNull()
        val problem = when {
            !on -> null
            model.isBlank() -> "Choose a model first."
            s.capabilities(model) == null -> why ?: "The model could not be tested. Check the key and the connection."
            feature == AssistantFeature.DICTATION && s.capabilities(model)?.structured == null ->
                why ?: "This model did not give a structured answer in the test, which dictation needs. Choose another model."
            feature != AssistantFeature.DICTATION && s.capabilities(model)?.tools != true ->
                why ?: "This model did not call a tool in the test, which Ask needs. Choose another model."
            else -> null
        }
        if (problem != null) {
            local.update { it.copy(message = problem) }
            return
        }
        store.update {
            when (feature) {
                AssistantFeature.DICTATION -> it.copy(dictationOn = on)
                AssistantFeature.ASK -> it.copy(askOn = on, talkOn = if (on) it.talkOn else false)
                AssistantFeature.TALK -> it.copy(talkOn = on, askOn = it.askOn || on)
            }
        }
    }

    fun setSpeakAnswers(on: Boolean) {
        viewModelScope.launch { store.update { it.copy(speakAnswers = on) } }
    }

    /** Smart dictation opens as a brain dump (section 4.8); the same switch as the dictation sheet's. */
    fun setBrainDump(on: Boolean) {
        viewModelScope.launch { store.update { it.copy(brainDump = on) } }
    }

    fun chooseSpeechModel(id: String?) {
        viewModelScope.launch {
            store.update { it.copy(speechModelId = id) }
            speechHost.release()
        }
    }

    fun downloadSpeechModel(id: String) {
        val spec = SpeechModelSpec.byId(id) ?: return
        viewModelScope.launch {
            if (!speechModels.canDownloadNow(store.current().allowMobileData)) {
                local.update { it.copy(message = "Connect to Wi-Fi to download ${spec.label}, or allow mobile data below.") }
                return@launch
            }
            speechModels.download(spec)
            if (store.current().speechModelId == null) store.update { it.copy(speechModelId = id) }
        }
    }

    fun cancelSpeechDownload(id: String) {
        SpeechModelSpec.byId(id)?.let(speechModels::cancel)
    }

    fun deleteSpeechModel(id: String) {
        val spec = SpeechModelSpec.byId(id) ?: return
        viewModelScope.launch {
            if (store.current().speechModelId == id) {
                speechHost.release()
                store.update { it.copy(speechModelId = null) }
            }
            speechModels.delete(spec)
        }
    }

    fun setSpeechLanguage(code: String) {
        viewModelScope.launch { store.update { it.copy(speechLanguage = code) } }
    }

    fun setAllowMobileData(allowed: Boolean) {
        viewModelScope.launch { store.update { it.copy(allowMobileData = allowed) } }
    }

    /** Removes the key, the settings, the conversations and a kept transcript (speech models stay). */
    fun clearAll() {
        viewModelScope.launch {
            keys.clear()
            store.clear()
            conversations.clear()
            keptTranscripts.clear()
            speechHost.release()
            local.update { AssistantUiState(localSpeechAvailable = it.localSpeechAvailable, suggestedSpeechModel = it.suggestedSpeechModel, message = "Assistant data deleted") }
        }
    }

    fun clearMessage() = local.update { it.copy(message = null) }
}
