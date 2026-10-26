package com.rendyhd.vicu.ui.screens.dictation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.rendyhd.vicu.ai.dictation.CheckedDraft
import com.rendyhd.vicu.ai.dictation.CheckedProject
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.dictation.DictationController
import com.rendyhd.vicu.ai.dictation.DictationMic
import com.rendyhd.vicu.ai.dictation.DictationMicUi
import com.rendyhd.vicu.ai.dictation.DictationPhase
import com.rendyhd.vicu.ai.dictation.DictationUi
import com.rendyhd.vicu.ui.components.assistant.DraftCard
import com.rendyhd.vicu.ui.components.assistant.droppedText
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.theme.VicuMotion
import com.rendyhd.vicu.ui.theme.rememberReduceMotion
import org.koin.compose.koinInject

/**
 * Smart dictation's draft cards over the screens (`docs/ai-assistant-v1.md`, sections 3.7 and 4.4 to
 * 4.6): the microphone that stays on while the cards appear and change, the model at work, the drafts
 * to check and change (by hand or by saying what to change), Add with one Undo, and Retry or Add as
 * one task when the model failed. With Brain dump on (section 4.8) the words collect in the dump
 * until Organize, and the plan shows new projects with their tasks under them. Like Ask, the sheet
 * starts small at the bottom and grows once there are cards to check.
 */
@Composable
fun DictationSheet(
    controller: DictationController = koinInject(),
    mic: DictationMic = koinInject(),
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val ui = state
    // The microphone never stays on behind another app.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { mic.off() }
    var confirmDiscard by remember { mutableStateOf(false) }
    // A brain dump can be minutes of talking: it is not thrown away by one tap.
    val discard = {
        val current = controller.state.value
        if (current != null && current.brainDump && (current.transcript.isNotBlank() || current.hasPlan)) confirmDiscard = true else controller.discard()
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this brain dump?") },
            text = { Text("What you said and the plan made from it are gone.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; controller.discard() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }
    val reduceMotion = rememberReduceMotion()
    AnimatedVisibility(
        visible = ui != null,
        enter = if (reduceMotion) fadeIn() else fadeIn() + slideInVertically(VicuMotion.defaultSpatialSpec()) { it / 4 },
        exit = if (reduceMotion) fadeOut() else fadeOut() + slideOutVertically(VicuMotion.fastEffectsSpec()) { it / 4 },
    ) {
        if (ui == null) return@AnimatedVisibility
        // Grown once there are cards (or a plan) to check; until then it stays small over the list.
        val grown = ui.phase == DictationPhase.REVIEW
        NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = true) {
            when {
                ui.phase != DictationPhase.THINKING -> discard()
                ui.brainDump -> controller.backToDump()
            }
        }
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = if (grown) 0.32f else 0.12f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().statusBarsPadding()
                    .then(if (grown) Modifier.fillMaxHeight(GROWN_HEIGHT) else Modifier)
                    .then(if (reduceMotion) Modifier else Modifier.animateContentSize(VicuMotion.defaultSpatialSpec())),
            ) {
                val micUi by mic.state.collectAsStateWithLifecycle()
                Column((if (grown) Modifier.fillMaxSize() else Modifier).navigationBarsPadding().imePadding()) {
                    Header(ui, onClose = discard, onBrainDump = mic::setBrainDump)
                    Box(if (grown) Modifier.weight(1f) else Modifier) { Body(ui, micUi, controller) }
                    Footer(ui, micUi, controller, mic, onDiscard = discard)
                }
            }
        }
    }
}

@Composable
private fun Header(ui: DictationUi, onClose: () -> Unit, onBrainDump: (Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                when (ui.phase) {
                    DictationPhase.LISTENING -> if (ui.brainDump) "Brain dump" else "Say your tasks"
                    DictationPhase.THINKING -> if (ui.brainDump) "Organizing" else "Making tasks"
                    DictationPhase.REVIEW -> if (ui.brainDump) planTitle(ui.projects, ui.drafts) else if (ui.drafts.size == 1) "1 task to check" else "${ui.drafts.size} tasks to check"
                    DictationPhase.FAILED -> if (ui.brainDump) "Not organized" else "Not turned into tasks"
                    DictationPhase.EMPTY -> "Nothing to add"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            IconButton(onClick = onClose, enabled = ui.phase != DictationPhase.THINKING) {
                Icon(Icons.Outlined.Close, contentDescription = "Discard")
            }
        }
        if (ui.phase == DictationPhase.LISTENING) BrainDumpSwitch(ui.brainDump, onBrainDump)
        // A brain dump shows itself in the field below.
        if (ui.transcript.isNotBlank() && !(ui.brainDump && ui.phase == DictationPhase.LISTENING)) Text(
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
private fun Body(ui: DictationUi, micUi: DictationMicUi, controller: DictationController) {
    when {
        ui.brainDump && ui.phase == DictationPhase.LISTENING -> OutlinedTextField(
            value = ui.transcript,
            onValueChange = controller::setDump,
            placeholder = { Text("Talk, or type here. Everything you say is added to your brain dump.") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "Your brain dump" },
        )
        ui.brainDump && ui.phase == DictationPhase.REVIEW -> PlanList(ui, controller)
        else -> PhaseBody(ui, micUi, controller)
    }
}

@Composable
private fun PhaseBody(ui: DictationUi, micUi: DictationMicUi, controller: DictationController) {
    when (ui.phase) {
        DictationPhase.LISTENING -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                if (micUi.on || micUi.starting) {
                    "Keep talking. Pause, and your tasks appear as cards."
                } else {
                    "Turn on the microphone and say your tasks."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        DictationPhase.THINKING -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(
                if (ui.brainDump) "Sorting your brain dump into projects and tasks" else "Turning what you said into tasks",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        DictationPhase.FAILED, DictationPhase.EMPTY -> Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                if (ui.phase == DictationPhase.EMPTY) "The model found no tasks in what you said." else ui.error ?: "The model did not answer.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (ui.brainDump) {
                    "Your brain dump is kept: try again, or go back to it to change or add to it."
                } else {
                    "What you said is kept: try again, or add it as one task as quick add would read it."
                },
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
                    "A dashed outline marks what Vicu guessed. Tap a chip to change it, or say what to change or what to add.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Footer(ui: DictationUi, micUi: DictationMicUi, controller: DictationController, mic: DictationMic, onDiscard: () -> Unit) {
    if (ui.brainDump) return BrainDumpFooter(ui, micUi, controller, mic, onDiscard)
    val format = LocalDateFormat.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        LiveWords(micUi)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MicToggle(micUi, onToggle = mic::toggle)
            when (ui.phase) {
                DictationPhase.LISTENING -> {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = controller::discard) { Text("Discard") }
                }
                DictationPhase.REVIEW -> {
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

/** The live words of the open microphone, while it is on. */
@Composable
private fun LiveWords(micUi: DictationMicUi) {
    if (micUi.on || micUi.starting) {
        Text(
            micUi.words.ifBlank { "Listening…" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * The Brain dump switch (section 4.8) with a note under it that says what it does, on or off: the
 * person decides before saying anything whether each pause makes cards or everything waits.
 */
@Composable
private fun BrainDumpSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.padding(end = 12.dp, top = 8.dp, bottom = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        ) {
            Text("Brain dump", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = null)
        }
        Text(
            if (on) {
                "Talk as long as you like. Nothing is sent until you tap Organize; then everything is sorted into projects, subprojects, tasks, subtasks and notes."
            } else {
                "Each pause turns what you said into task cards. Turn on Brain dump to talk as long as you like and have it all organized at the end."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A brain dump's buttons: Organize the dump, Update and Add the plan, Edit and Retry after a failure. */
@Composable
private fun BrainDumpFooter(ui: DictationUi, micUi: DictationMicUi, controller: DictationController, mic: DictationMic, onDiscard: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        LiveWords(micUi)
        if (ui.phase == DictationPhase.REVIEW) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                OutlinedTextField(
                    value = ui.pending,
                    onValueChange = controller::setPending,
                    placeholder = { Text("Say or type a change") },
                    maxLines = 4,
                    enabled = !ui.adding,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = { controller.update() },
                    enabled = ui.pending.isNotBlank() && !ui.editing && !ui.adding,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Update") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MicToggle(micUi, onToggle = mic::toggle)
            Spacer(Modifier.weight(1f))
            when (ui.phase) {
                DictationPhase.LISTENING -> {
                    TextButton(onClick = onDiscard) { Text("Discard") }
                    Button(onClick = { controller.organize() }, enabled = ui.transcript.isNotBlank()) { Text("Organize") }
                }
                DictationPhase.THINKING -> TextButton(onClick = controller::backToDump) { Text("Cancel") }
                DictationPhase.REVIEW -> {
                    TextButton(onClick = onDiscard, enabled = !ui.adding) { Text("Discard") }
                    Button(onClick = controller::add, enabled = ui.hasPlan && !ui.adding && !ui.editing) { Text("Add all") }
                }
                DictationPhase.FAILED, DictationPhase.EMPTY -> {
                    TextButton(onClick = onDiscard) { Text("Discard") }
                    FilledTonalButton(onClick = controller::backToDump) { Text("Edit") }
                    Button(onClick = controller::retry, enabled = ui.transcript.isNotBlank()) { Text("Retry") }
                }
            }
        }
    }
}

/** The plan of a brain dump: each project with its tasks under it, new projects to rename or remove (section 4.8.5). */
@Composable
private fun PlanList(ui: DictationUi, controller: DictationController) {
    val editable = !ui.adding && !ui.editing
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ui.editing) item(key = "editing") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        for (group in planGroups(ui.projects, ui.drafts)) {
            item(key = group.key) {
                PlanGroupHeader(group, ui, editable, controller)
            }
            items(group.drafts, key = { it.ref }) { draft ->
                DraftCard(
                    draft = draft,
                    context = ui.context,
                    dropped = ui.dropped.filter { it.ref == draft.ref && it.field !in PROJECT_FIELDS },
                    changed = ui.changed[draft.ref].orEmpty(),
                    editable = editable,
                    onChange = { updated -> controller.updateDraft(draft.ref) { updated } },
                    onRemove = { controller.removeDraft(draft.ref) },
                    newProjects = ui.projects,
                    brainDump = true,
                )
            }
        }
        val placed = ui.projects.map { it.ref }.toSet()
        val loose = ui.dropped.filter { d ->
            if (d.field in PROJECT_FIELDS) d.ref !in placed || d.field != "parent" else ui.drafts.none { it.ref == d.ref }
        }
        if (loose.isNotEmpty() || ui.unclear.isNotEmpty()) {
            item(key = "notes") {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    for (d in loose) Text(droppedText(d, brainDump = true), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (ui.unclear.isNotEmpty()) {
                        Text("Not placed:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                        for (note in ui.unclear) Text("• $note", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item(key = "legend") {
            Text(
                "A dashed outline marks what Vicu guessed. Tap a name or a chip to change it, or say or type a change below and tap Update.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PlanGroupHeader(group: PlanGroup, ui: DictationUi, editable: Boolean, controller: DictationController) {
    val project = group.newProject
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (project != null) Icons.Outlined.CreateNewFolder else Icons.Outlined.Folder,
                contentDescription = null,
                tint = if (project != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (project == null) {
                Text(
                    ui.context?.projects?.firstOrNull { it.id == group.projectId }?.path ?: "Inbox",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
            } else {
                val highlight = project.ref in ui.changedProjects
                Column(Modifier.padding(start = 4.dp).weight(1f)) {
                    TextField(
                        value = project.title,
                        onValueChange = { controller.renameProject(project.ref, it) },
                        enabled = editable,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleSmall,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = if (highlight) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            unfocusedContainerColor = if (highlight) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "New project name" },
                    )
                    Text(
                        "New project " + newProjectPlace(project, ui.projects, ui.context),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                IconButton(onClick = { controller.removeProject(project.ref) }, enabled = editable) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove the new project “${project.title}”; its tasks move to its parent")
                }
            }
        }
        if (project != null) {
            for (d in ui.dropped.filter { it.ref == project.ref && it.field == "parent" }) {
                Text(
                    droppedText(d, brainDump = true),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 40.dp),
                )
            }
        }
    }
}

/** The microphone that stays on (section 3.7): a tap turns it on or off. */
@Composable
private fun MicToggle(micUi: DictationMicUi, onToggle: () -> Unit) {
    val on = micUi.on || micUi.starting
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(50),
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = if (on) "Microphone on. Tap to stop listening" else "Microphone off. Tap to keep talking"
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (on) Icons.Outlined.Mic else Icons.Outlined.MicOff, contentDescription = null)
        }
    }
}

private fun <T> Set<T>?.orEmpty(): Set<T> = this ?: emptySet()

/** The sheet's height once there are cards to check, as Ask's once something was asked. */
private const val GROWN_HEIGHT = 0.8f

/** The dropped-field names of a brain dump's projects (section 4.8.3). */
private val PROJECT_FIELDS = setOf("project", "project_title", "parent")

/** One project of a brain dump's plan and the tasks that go in it. */
internal data class PlanGroup(val newProject: CheckedProject?, val projectId: Long?, val drafts: List<CheckedDraft>) {
    val key: String get() = newProject?.let { "new:${it.ref}" } ?: "project:$projectId"
}

/** The plan as the sheet lists it: projects in the order of their first task, then new projects without tasks. */
internal fun planGroups(projects: List<CheckedProject>, drafts: List<CheckedDraft>): List<PlanGroup> {
    val groups = LinkedHashMap<String, PlanGroup>()
    for (d in drafts) {
        val newProject = d.projectRef?.let { ref -> projects.firstOrNull { it.ref == ref } }
        val group = PlanGroup(newProject, if (newProject == null) d.projectId else null, emptyList())
        val found = groups[group.key] ?: group
        groups[group.key] = found.copy(drafts = found.drafts + d)
    }
    for (p in projects) {
        val group = PlanGroup(p, null, emptyList())
        if (group.key !in groups) groups[group.key] = group
    }
    return groups.values.toList()
}

/** Where a new project goes: "in Home / Garden", or "at the top level". */
internal fun newProjectPlace(project: CheckedProject, projects: List<CheckedProject>, context: DictationContext?): String {
    fun pathOf(p: CheckedProject, depth: Int): String {
        val parent = when {
            depth > projects.size -> null
            p.parentRef != null -> projects.firstOrNull { it.ref == p.parentRef }?.let { pathOf(it, depth + 1) }
            p.parentId != null -> context?.projects?.firstOrNull { it.id == p.parentId }?.path
            else -> null
        }
        return if (parent == null) p.title else "$parent / ${p.title}"
    }
    val parent = when {
        project.parentRef != null -> projects.firstOrNull { it.ref == project.parentRef }?.let { pathOf(it, 0) }
        project.parentId != null -> context?.projects?.firstOrNull { it.id == project.parentId }?.path
        else -> null
    }
    return if (parent == null) "at the top level" else "in $parent"
}

/** "2 new projects, 14 tasks" for the plan's header. */
internal fun planTitle(projects: List<CheckedProject>, drafts: List<CheckedDraft>): String {
    val tasks = if (drafts.size == 1) "1 task" else "${drafts.size} tasks"
    return when (projects.size) {
        0 -> tasks
        1 -> "1 new project, $tasks"
        else -> "${projects.size} new projects, $tasks"
    }
}
