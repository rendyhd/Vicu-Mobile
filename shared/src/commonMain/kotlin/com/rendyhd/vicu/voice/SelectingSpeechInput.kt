package com.rendyhd.vicu.voice

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiSettings
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.speech.AudioRecorder
import com.rendyhd.vicu.speech.LocalSpeechSession
import com.rendyhd.vicu.speech.MicrophoneAccess
import com.rendyhd.vicu.speech.SpeechEngineHost
import com.rendyhd.vicu.speech.SpeechModelManager
import com.rendyhd.vicu.speech.SpeechModelSpec
import com.rendyhd.vicu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The speech input holding + uses: local speech when the user chose a downloaded speech model
 * (`docs/ai-assistant-v1.md`, section 3), otherwise the platform recognizer as before (3.6). Only
 * one of them records at a time. Every call is made on the main thread.
 */
class SelectingSpeechInput(
    private val platform: SpeechInput,
    private val recorder: AudioRecorder,
    private val host: SpeechEngineHost,
    private val models: SpeechModelManager,
    settingsStore: AiSettingsStore,
    private val scope: CoroutineScope,
    /** The vocabulary hint for Whisper, built from the user's names when a recording starts. */
    private val hint: suspend () -> String?,
) : SpeechInput {
    private val settings: StateFlow<AiSettings> = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, AiSettings())

    private var session: LocalSpeechSession? = null
    private var watcher: Job? = null
    private var usingLocal = false

    /** The chosen local model when it is on the device and the engine loads, else null. */
    private fun localModel(): SpeechModelSpec? {
        if (!host.isAvailable) return null
        val spec = SpeechModelSpec.byId(settings.value.speechModelId) ?: return null
        return spec.takeIf { models.isInstalled(it) }
    }

    override fun availability(): SpeechAvailability {
        if (localModel() == null) return platform.availability()
        return when (recorder.access()) {
            MicrophoneAccess.GRANTED -> SpeechAvailability.READY
            MicrophoneAccess.NOT_ASKED -> SpeechAvailability.NEEDS_PERMISSION
            MicrophoneAccess.DENIED -> SpeechAvailability.DENIED
        }
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        if (localModel() == null) platform.requestPermission(onResult) else recorder.requestAccess(onResult)
    }

    override fun start(listener: SpeechListener) {
        lastListener = listener
        stopped = false
        val spec = localModel()
        usingLocal = spec != null
        Logger.d(TAG, "listening with ${spec?.id ?: "the platform recognizer"} (chosen: ${settings.value.speechModelId})")
        if (spec == null) {
            platform.start(listener)
            return
        }
        val language = settings.value.speechLanguage
        watcher = scope.launch {
            val local = LocalSpeechSession(host, recorder, scope, models.pathOf(spec), spec, language, hint())
            session = local
            if (stopped) {
                // Let go before the session existed: nothing was recorded.
                session = null
                listener.onError(SpeechError.NO_MATCH)
                return@launch
            }
            if (!local.start()) {
                session = null
                listener.onError(SpeechError.OTHER)
                return@launch
            }
            local.state.collect { state ->
                listener.onLevel(state.level)
                if (state.text.isNotEmpty()) listener.onPartial(state.text)
            }
        }
    }

    override fun stop() {
        if (!usingLocal) {
            platform.stop()
            return
        }
        stopped = true
        val local = session ?: return
        scope.launch {
            val text = local.finish().trim()
            watcher?.cancel()
            session = null
            val listener = lastListener
            if (listener != null) {
                if (text.isEmpty()) listener.onError(SpeechError.NO_MATCH) else listener.onFinal(text)
            }
        }
    }

    override fun cancel() {
        if (!usingLocal) {
            platform.cancel()
            return
        }
        session?.cancel()
        session = null
        watcher?.cancel()
    }

    /**
     * A microphone that stays on and reports turns (section 3.7): local speech with the voice
     * detector when a model is chosen, else the platform recognizer an utterance at a time. Check
     * [availability] first.
     */
    fun openMic(listener: TurnListener, turns: TurnSettings = TurnSettings.fromContract()): OpenMic {
        val spec = localModel() ?: return PlatformOpenMic(platform, scope, turns, listener).also { it.start() }
        val language = settings.value.speechLanguage
        return LocalOpenMic(scope, turns, listener, AiContract.limits.audioMaxSeconds) {
            LocalSpeechSession(host, recorder, scope, models.pathOf(spec), spec, language, hint(), turns.endOfTurnMs)
        }.also { it.start() }
    }

    override fun openSystemSpeech(): Boolean = platform.openSystemSpeech()

    override fun openSettings() = platform.openSettings()

    override val finalWaitMs: Long get() = if (localModel() != null) LOCAL_FINAL_WAIT_MS else platform.finalWaitMs

    /** [stop] came; a session still being built reports nothing heard. */
    private var stopped = false

    /** The listener of the recording in progress: [stop] reports the transcript to it once the last phrase is done. */
    private var lastListener: SpeechListener? = null

    private companion object {
        /** Transcribing the last phrases after the finger lifts can take a while on a phone. */
        const val LOCAL_FINAL_WAIT_MS = 180_000L
        const val TAG = "SelectingSpeech"
    }
}
