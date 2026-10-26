package com.rendyhd.vicu.ui.screens.dictation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.rendyhd.vicu.ai.dictation.DictationController
import com.rendyhd.vicu.ai.dictation.DictationPhase
import com.rendyhd.vicu.ai.dictation.DictationUi
import com.rendyhd.vicu.ui.components.assistant.DraftCard
import com.rendyhd.vicu.ui.components.assistant.droppedText
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.voice.HoldToTalk
import org.koin.compose.koinInject

/**
 * Smart dictation's draft cards over the screens (`docs/ai-assistant-v1.md`, sections 4.4 to 4.6):
 * the model at work, the drafts to check and change (by hand or by holding the microphone again),
 * Add with one Undo, and Retry or Add as one task when the model failed.
 */
@Composable
fun DictationSheet(
    controller: DictationController = koinInject(),
    holdToTalk: HoldToTalk = koinInject(),
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val ui = state
    AnimatedVisibility(visible = ui != null, enter = fadeIn(), exit = fadeOut()) {
        if (ui == null) return@AnimatedVisibility
        NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = true) {
            if (ui.phase != DictationPhase.THINKING) controller.discard()
        }
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.92f).statusBarsPadding(),
            ) {
                Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                    Header(ui, onClose = controller::discard)
                    Box(Modifier.weight(1f)) { Body(ui, controller) }
                    Footer(ui, controller, holdToTalk)
                }
            }
        }
    }
}

@Composable
private fun Header(ui: DictationUi, onClose: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                when (ui.phase) {
                    DictationPhase.THINKING -> "Making tasks"
                    DictationPhase.REVIEW -> if (ui.drafts.size == 1) "1 task to check" else "${ui.drafts.size} tasks to check"
                    DictationPhase.FAILED -> "Not turned into tasks"
                    DictationPhase.EMPTY -> "Nothing to add"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            IconButton(onClick = onClose, enabled = ui.phase != DictationPhase.THINKING) {
                Icon(Icons.Outlined.Close, contentDescription = "Discard")
            }
        }
        Text(
            "“" + ui.transcript + "”",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp, top = 4.dp, bottom = 8.dp).clickable { expanded = !expanded }
                .semantics { contentDescription = "What you said: ${ui.transcript}" },
        )
    }
}

@Composable
private fun Body(ui: DictationUi, controller: DictationController) {
    when (ui.phase) {
        DictationPhase.THINKING -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Text("Turning what you said into tasks", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        }
        DictationPhase.FAILED, DictationPhase.EMPTY -> Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                if (ui.phase == DictationPhase.EMPTY) "The model found no tasks in what you said." else ui.error ?: "The model did not answer.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "What you said is kept: try again, or add it as one task as quick add would read it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            for (note in ui.unclear) Text("• $note", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        DictationPhase.REVIEW -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.editing) item(key = "editing") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(ui.drafts, key = { it.ref }) { draft ->
                DraftCard(
                    draft = draft,
                    context = ui.context,
                    dropped = ui.dropped.filter { it.ref == draft.ref },
                    changed = ui.changed[draft.ref].orEmpty(),
                    editable = !ui.adding && !ui.editing,
                    onChange = { updated -> controller.updateDraft(draft.ref) { updated } },
                    onRemove = { controller.removeDraft(draft.ref) },
                )
            }
            val loose = ui.dropped.filter { d -> ui.drafts.none { it.ref == d.ref } }
            if (loose.isNotEmpty() || ui.unclear.isNotEmpty()) {
                item(key = "notes") {
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                        for (d in loose) Text(droppedText(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (ui.unclear.isNotEmpty()) {
                            Text("Not placed:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                            for (note in ui.unclear) Text("• $note", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item(key = "legend") {
                Text(
                    "A dashed outline marks what Vicu guessed. Tap a chip to change it, or hold the microphone and say what to change.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Footer(ui: DictationUi, controller: DictationController, holdToTalk: HoldToTalk) {
    val format = LocalDateFormat.current
    val voice by holdToTalk.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        voice?.let { capture ->
            Text(
                if (capture.text.isBlank()) "Listening. Say what to change." else capture.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (ui.phase) {
                DictationPhase.REVIEW -> {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (voice != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(48.dp)
                            .semantics { contentDescription = "Hold and say what to change" }
                            .pointerInput(ui.editing) {
                                if (ui.editing) return@pointerInput
                                detectTapGestures(
                                    onPress = {
                                        holdToTalk.begin(format, handsFree = false, defaults = ui.defaults)
                                        tryAwaitRelease()
                                        holdToTalk.release()
                                    },
                                )
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Mic, contentDescription = null) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = controller::discard, enabled = !ui.adding) { Text("Discard") }
                    Button(onClick = controller::add, enabled = ui.drafts.isNotEmpty() && !ui.adding && !ui.editing) {
                        Text(if (ui.drafts.size == 1) "Add task" else "Add ${ui.drafts.size} tasks")
                    }
                }
                DictationPhase.FAILED, DictationPhase.EMPTY -> {
                    TextButton(onClick = controller::discard) { Text("Discard") }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = { controller.addAsOneTask(format) }) { Text("Add as one task") }
                    Button(onClick = controller::retry) { Text("Retry") }
                }
                DictationPhase.THINKING -> {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = controller::discard) { Text("Cancel") }
                }
            }
        }
    }
}

private fun <T> Set<T>?.orEmpty(): Set<T> = this ?: emptySet()
