package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiSettings
import com.rendyhd.vicu.speech.ModelFileState
import com.rendyhd.vicu.speech.SpeechModelSpec
import com.rendyhd.vicu.util.formatByteSize

/** The languages offered for local speech (Whisper's codes); `auto` detects per phrase. */
internal val SPEECH_LANGUAGES: List<Pair<String, String>> = listOf(
    "auto" to "Detect automatically",
    "en" to "English", "nl" to "Dutch", "de" to "German", "fr" to "French", "es" to "Spanish",
    "it" to "Italian", "pt" to "Portuguese", "pl" to "Polish", "sv" to "Swedish", "da" to "Danish",
    "no" to "Norwegian", "fi" to "Finnish", "cs" to "Czech", "uk" to "Ukrainian", "ru" to "Russian",
    "tr" to "Turkish", "el" to "Greek", "hu" to "Hungarian", "ro" to "Romanian", "ja" to "Japanese",
    "zh" to "Chinese", "ko" to "Korean", "hi" to "Hindi", "ar" to "Arabic", "id" to "Indonesian",
)

/**
 * Settings, Assistant: the model provider the user brings, the features, local speech and what is
 * sent (`docs/ai-assistant-v1.md`). Everything is off until a provider is set up and tested.
 */
@Composable
internal fun AssistantTab(
    state: AssistantUiState,
    viewModel: AssistantSettingsViewModel,
    listState: LazyListState,
) {
    val settings = state.settings
    val preset = settings.providerId?.let { AiContract.provider(it) }
    var providerPicker by rememberSaveable { mutableStateOf(false) }
    var languagePicker by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "intro") {
            Text(
                "Bring your own model: a key for OpenAI, Anthropic, Gemini or OpenRouter, or a model running on your own " +
                    "computer with Ollama or Open WebUI. Vicu calls it straight from this device; you pay your provider " +
                    "for what you use. Nothing is sent until you set it up and agree.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        // --- Provider ---
        item(key = "provider_header") { SectionHeader(Icons.Outlined.AutoAwesome, "Model provider") }
        item(key = "provider") {
            SettingsValueRow("Provider", preset?.name ?: "None", onClick = { providerPicker = true })
        }
        if (preset != null) {
            item(key = "address") {
                var address by remember(settings.providerId) { mutableStateOf(settings.address) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Address") },
                        singleLine = true,
                        enabled = preset.addressEditable,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (preset.addressEditable && address.trim() != settings.address) {
                        TextButton(onClick = { viewModel.setAddress(address) }) { Text("Save address") }
                    }
                    if (AiSettings.isInsecureRemote(address)) {
                        Text(
                            "This address uses plain http outside your local network: your tasks would travel unencrypted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item(key = "key") {
                KeyField(
                    hasKey = state.hasKey,
                    required = preset.keyRequired,
                    onSave = viewModel::setKey,
                )
            }

            // --- Models ---
            item(key = "models_header") { SubsectionHeading("Models") }
            item(key = "model_dictation") {
                ModelField("Dictation model", settings.dictationModel, state.models, viewModel::setDictationModel)
            }
            item(key = "model_ask") {
                ModelField("Ask model", settings.askModel, state.models, viewModel::setAskModel)
            }
            item(key = "model_actions") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = viewModel::loadModels, enabled = !state.loadingModels) { Text("Load models") }
                    OutlinedButton(onClick = viewModel::test, enabled = !state.testing) { Text("Test connection") }
                    if (state.testing || state.loadingModels) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                state.modelsError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            item(key = "test_result") { TestResult(state) }

            // --- Features ---
            item(key = "features_header") { SubsectionHeading("Features") }
            item(key = "dictation") {
                SwitchRow(
                    label = "Smart dictation",
                    description = "Hold + and talk freely: the model turns what you said into separate tasks with dates, " +
                        "projects and labels, for you to check before adding.",
                    checked = settings.dictationOn,
                    onCheckedChange = { viewModel.setFeature(AssistantFeature.DICTATION, it) },
                )
            }
            item(key = "ask") {
                SwitchRow(
                    label = "Ask",
                    description = "Chat with your tasks from the menu. The model looks things up and proposes changes; " +
                        "nothing changes until you apply it.",
                    checked = settings.askOn,
                    onCheckedChange = { viewModel.setFeature(AssistantFeature.ASK, it) },
                )
            }
            item(key = "talk") {
                SwitchRow(
                    label = "Talk",
                    description = "Ask by voice: hold the microphone in Ask, and hear the answer.",
                    checked = settings.talkOn,
                    onCheckedChange = { viewModel.setFeature(AssistantFeature.TALK, it) },
                )
            }
            if (settings.talkOn) {
                item(key = "speak") {
                    SwitchRow(
                        label = "Read answers aloud",
                        description = "In Talk, the device voice reads each answer.",
                        checked = settings.speakAnswers,
                        onCheckedChange = viewModel::setSpeakAnswers,
                    )
                }
            }
        }

        // --- Speech ---
        item(key = "speech_header") { SectionHeader(Icons.Outlined.GraphicEq, "Speech recognition") }
        item(key = "speech_intro") {
            Text(
                if (state.localSpeechAvailable) {
                    "With a speech model on this phone, what you say is turned into text here, and audio never leaves " +
                        "the device. Without one, the system's speech recognition is used."
                } else {
                    "Local speech is not available on this device; the system's speech recognition is used."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (state.localSpeechAvailable) {
            item(key = "speech_device") {
                SpeechChoiceRow(
                    title = "System speech recognition",
                    subtitle = "No download",
                    selected = settings.speechModelId == null,
                    onSelect = { viewModel.chooseSpeechModel(null) },
                )
            }
            for (spec in SpeechModelSpec.catalog) {
                item(key = "speech_${spec.id}") {
                    SpeechModelRow(
                        spec = spec,
                        fileState = state.speechStates[spec.id] ?: ModelFileState.Absent,
                        selected = settings.speechModelId == spec.id,
                        suggested = spec.id == state.suggestedSpeechModel,
                        onSelect = { viewModel.chooseSpeechModel(spec.id) },
                        onDownload = { viewModel.downloadSpeechModel(spec.id) },
                        onCancel = { viewModel.cancelSpeechDownload(spec.id) },
                        onDelete = { viewModel.deleteSpeechModel(spec.id) },
                    )
                }
            }
            item(key = "speech_language") {
                SettingsValueRow(
                    "Language",
                    SPEECH_LANGUAGES.firstOrNull { it.first == settings.speechLanguage }?.second ?: settings.speechLanguage,
                    onClick = { languagePicker = true },
                )
            }
            item(key = "speech_mobile_data") {
                SwitchRow(
                    label = "Download on mobile data",
                    description = "Speech models are large; without this they download on Wi-Fi only.",
                    checked = settings.allowMobileData,
                    onCheckedChange = viewModel::setAllowMobileData,
                )
            }
            val attributions = SpeechModelSpec.catalog.mapNotNull { it.attribution }.distinct()
            if (attributions.isNotEmpty()) {
                item(key = "speech_attribution") {
                    Text(
                        attributions.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        // --- Privacy ---
        item(key = "privacy_header") { SectionHeader(Icons.Outlined.Lock, "What is sent") }
        item(key = "privacy") {
            Text(
                PRIVACY_TEXT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item(key = "clear") {
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Delete assistant data", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(96.dp))
        }
    }

    if (providerPicker) {
        ProviderDialog(
            selected = settings.providerId,
            onPick = {
                providerPicker = false
                viewModel.chooseProvider(it)
            },
            onDismiss = { providerPicker = false },
        )
    }
    if (languagePicker) {
        ChoiceDialog(
            title = "Speech language",
            options = SPEECH_LANGUAGES,
            selected = settings.speechLanguage,
            onPick = {
                languagePicker = false
                viewModel.setSpeechLanguage(it)
            },
            onDismiss = { languagePicker = false },
        )
    }
    state.consentFor?.let { feature ->
        AlertDialog(
            onDismissRequest = { viewModel.answerConsent(false) },
            title = { Text("Send to ${preset?.name ?: "your provider"}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("To use the assistant, Vicu sends this to ${settings.address}:")
                    Text("Dictation: the text of what you said (never audio), and the names of your projects, labels and places, the list you are in, the date, time and time zone.")
                    Text("Ask and Talk: your messages, counts and names of your projects, labels and lists, and the tasks the model looks up, including names of the people they are shared with.")
                    Text("Never: your Vikunja password or token, your server's address, comments, attachments, or where your places are.")
                    Text("Your provider's terms decide what happens to it there, and you pay them for what you use.")
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.answerConsent(true) }) { Text("Agree") } },
            dismissButton = { TextButton(onClick = { viewModel.answerConsent(false) }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete assistant data?") },
            text = { Text("Removes the key, the assistant settings, your Ask conversations and a kept dictation from this device. Downloaded speech models stay until you delete them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearAll()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private const val PRIVACY_TEXT =
    "Dictation sends the text of what you said and the names of your projects, labels and places. Ask and Talk " +
        "send your messages and the tasks the model looks up. Audio stays on this phone unless you choose your " +
        "provider for speech. Hidden tasks, comments, attachments, your Vikunja token and your server address are " +
        "never sent. The key is kept in this device's secure storage and never syncs."

@Composable
private fun KeyField(hasKey: Boolean, required: Boolean, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(if (required) "API key" else "API key (optional)") },
            placeholder = { if (hasKey) Text("Saved. Type a new key to replace it.") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (visible) "Hide key" else "Show key")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onSave(key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
            if (hasKey) TextButton(onClick = { onSave("") }) { Text("Remove key") }
        }
    }
}

@Composable
private fun ModelField(label: String, value: String, models: List<String>, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                if (models.isNotEmpty()) {
                    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Choose a model") }
                }
            },
            supportingText = if (text.trim() != value) ({ TextButton(onClick = { onChange(text) }) { Text("Save") } }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (model in models.take(200)) {
                DropdownMenuItem(text = { Text(model) }, onClick = {
                    open = false
                    text = model
                    onChange(model)
                })
            }
        }
    }
}

@Composable
private fun TestResult(state: AssistantUiState) {
    val test = state.test ?: run {
        val s = state.settings
        val dictation = s.capabilities(s.dictationModel)
        val ask = s.capabilities(s.askModel)
        if (dictation == null && ask == null) return
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            if (dictation != null) ResultLine(dictation.structured != null, "Dictation model: " + if (dictation.structured != null) "ready" else "no structured answers")
            if (ask != null) ResultLine(ask.tools && ask.structured != null, "Ask model: " + if (ask.tools) "ready" else "does not call tools")
        }
        return
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ResultLine(test.models != null, if (test.models != null) "Model list: ${test.models.size} models" else "Model list: not available (type the name)")
        ResultLine(test.canDictate, if (test.canDictate) "Structured answers work (${test.structured?.wire})" else "No structured answers: dictation cannot use this model")
        ResultLine(test.tools, if (test.tools) "Tool calls work: Ask and Talk can use this model" else "No tool calls: Ask and Talk cannot use this model")
        for (problem in test.problems.take(4)) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = if (ok) "Works" else "Does not work",
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SpeechChoiceRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpeechModelRow(
    spec: SpeechModelSpec,
    fileState: ModelFileState,
    selected: Boolean,
    suggested: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val installed = fileState is ModelFileState.Installed
    val languages = if (spec.languageCodes == null) "${spec.languageCount} languages" else "${spec.languageCount} European languages"
    val subtitle = buildString {
        append(formatByteSize(spec.size)).append(", ").append(languages)
        if (suggested) append(", suggested for this phone")
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = selected, enabled = installed, onClick = onSelect, role = Role.RadioButton)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = installed)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(spec.label, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (fileState) {
                    is ModelFileState.Downloading -> {
                        LinearProgressIndicator(progress = { fileState.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        Text(
                            "${formatByteSize(fileState.bytes)} of ${formatByteSize(fileState.total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ModelFileState.Checking -> Text("Checking the download", style = MaterialTheme.typography.bodySmall)
                    is ModelFileState.Failed -> Text(fileState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
            }
            when (fileState) {
                is ModelFileState.Downloading, ModelFileState.Checking -> TextButton(onClick = onCancel) { Text("Cancel") }
                ModelFileState.Installed -> TextButton(onClick = onDelete) { Text("Delete") }
                else -> TextButton(onClick = onDownload) { Text("Download") }
            }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
    }
}

@Composable
private fun ProviderDialog(selected: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ChoiceDialog(
        title = "Model provider",
        options = AiContract.providers.map { it.id to it.name },
        selected = selected,
        onPick = onPick,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((id, name) in options) {
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = id == selected, onClick = { onPick(id) }, role = Role.RadioButton)
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = id == selected, onClick = null)
                        Text(name, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Shows the view model's one-off messages (key saved, a feature that needs a test first). */
@Composable
internal fun AssistantMessages(state: AssistantUiState, viewModel: AssistantSettingsViewModel, show: suspend (String) -> Unit) {
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        show(message)
        viewModel.clearMessage()
    }
}

@Suppress("unused")
private fun Modifier.clickableRow(onClick: () -> Unit) = clickable(onClick = onClick)
