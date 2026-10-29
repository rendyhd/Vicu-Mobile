package com.rendyhd.vicu.voice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.data.local.ThemeMode
import com.rendyhd.vicu.data.local.ThemePrefsStore
import com.rendyhd.vicu.speech.LocalSpeechSession
import com.rendyhd.vicu.speech.MicrophoneAccess
import com.rendyhd.vicu.ui.components.fab.VoiceAddCard
import com.rendyhd.vicu.ui.theme.VicuTheme
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.TextTaskCreator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Adds a task by voice without opening the app (the Quick add widget's mic, the task list widget's
 * mic, a pinned copy of the old "Add by voice" shortcut). The words are read like quick add ("Call mom tomorrow
 * 3pm #Family") and stored on the device; a toast says what was added.
 *
 * With a speech model chosen in Settings (Parakeet, Whisper) a card over the home screen listens
 * with it ([VoiceAddSession]), so the task can be said in any language the model knows; the first
 * use asks for the microphone. Otherwise, or when the microphone was refused, the system speech
 * screen hears the task, which needs no permission. Without a speech service it opens quick add
 * instead, where the keyboard's mic works.
 */
class VoiceAddActivity : ComponentActivity() {
    private val creator: TextTaskCreator by inject()
    private val speech: SelectingSpeechInput by inject()
    private val platformSpeech: AndroidSpeechInput by inject()
    private val themePrefsStore: ThemePrefsStore by inject()
    private val appScope: CoroutineScope by inject()

    /** Keeps the local session through a rotation. */
    class Held : ViewModel() {
        val session = MutableStateFlow<VoiceAddSession?>(null)

        /** Listening, the permission prompt or the speech screen started; a recreated activity waits for it. */
        var begun = false
    }

    private val held: Held by lazy { ViewModelProvider(this)[Held::class.java] }

    private val systemSpeech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode != RESULT_OK || text.isNullOrBlank()) {
            finish()
            return@registerForActivityResult
        }
        val format = DateDisplayFormat.system(DateFormat.is24HourFormat(this))
        lifecycleScope.launch {
            add(applicationContext, creator, text, format)
            finish()
        }
    }

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lifecycleScope.launch { begin() } else openSystemSpeech()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation) already listens, or waits for the prompt's or speech screen's answer.
        if (!held.begun && savedInstanceState?.getBoolean(KEY_BEGUN) != true) lifecycleScope.launch { begin() }
        setContent {
            val session by held.session.collectAsState()
            val current = session ?: return@setContent
            val state by current.state.collectAsState()
            val themeMode by themePrefsStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val useDeviceColors by themePrefsStore.useDeviceColors.collectAsStateWithLifecycle(initialValue = false)
            LaunchedEffect(state == null) { if (state == null) finish() }
            VicuTheme(themeMode = themeMode, dynamicColor = useDeviceColors) {
                state?.let { VoiceAddCard(it, onDone = current::done, onCancel = current::cancel) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_BEGUN, held.begun)
    }

    override fun onStop() {
        super.onStop()
        // Gone from the screen while listening (home, screen off): nothing half-said is added. Words
        // still being transcribed after Done or a pause are added all the same.
        if (isChangingConfigurations) return
        val session = held.session.value ?: return
        if (session.state.value?.phase == VoicePhase.LISTENING) {
            session.cancel()
            finish()
        }
    }

    /** Local speech when a model is chosen and the microphone allowed (asked the first time), else the system screen. */
    private suspend fun begin() {
        val recording = speech.localRecording(VoiceAddSession.END_OF_TASK_MS) ?: return openSystemSpeech()
        held.begun = true
        when (speech.microphoneAccess()) {
            MicrophoneAccess.GRANTED -> listen(recording)
            MicrophoneAccess.NOT_ASKED -> {
                platformSpeech.markAsked()
                microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
            MicrophoneAccess.DENIED -> openSystemSpeech()
        }
    }

    private fun listen(recording: LocalSpeechSession) {
        // The session outlives this screen: nothing in it holds the activity.
        val app = applicationContext
        val creator = creator
        val format = DateDisplayFormat.system(DateFormat.is24HourFormat(this))
        held.session.value = VoiceAddSession(
            recording = recording,
            scope = appScope,
            onWords = { text ->
                if (text.isBlank()) toast(app, "Didn't catch that. Try again.") else add(app, creator, text, format)
            },
            onFailed = { toast(app, "Could not listen. Try again.") },
        ).also { it.start() }
    }

    private fun openSystemSpeech() {
        held.begun = true
        val listen = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "What's the task?")
        try {
            systemSpeech.launch(listen)
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SHOW_TASK_ENTRY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
        }
    }

    private companion object {
        const val KEY_BEGUN = "begun"

        suspend fun add(app: Context, creator: TextTaskCreator, text: String, format: DateDisplayFormat) {
            val message = when (val added = creator.create(text, format)) {
                is TextTaskCreator.Result.Created -> added.confirmation
                is TextTaskCreator.Result.Failed -> added.message
            }
            toast(app, message)
        }

        fun toast(app: Context, message: String) = Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }
}
