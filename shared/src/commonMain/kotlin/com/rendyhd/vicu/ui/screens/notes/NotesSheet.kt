package com.rendyhd.vicu.ui.screens.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.ai.notes.NotesChoice
import com.rendyhd.vicu.ai.notes.NotesController
import com.rendyhd.vicu.ai.notes.NotesDraft
import com.rendyhd.vicu.ai.notes.NotesFilter
import com.rendyhd.vicu.ai.notes.NotesOwnerWorld
import com.rendyhd.vicu.ai.notes.NotesPhase
import com.rendyhd.vicu.ai.notes.NotesRules
import com.rendyhd.vicu.ai.notes.NotesSourcePage
import com.rendyhd.vicu.ai.notes.NotesUi
import com.rendyhd.vicu.ai.notes.OwnerAction
import com.rendyhd.vicu.ai.notes.PageKind
import com.rendyhd.vicu.ai.notes.PageState
import com.rendyhd.vicu.i18n.text
import com.rendyhd.vicu.resources.Res
import com.rendyhd.vicu.resources.*
import com.rendyhd.vicu.ui.components.assistant.DraftCard
import com.rendyhd.vicu.ui.components.picker.MonthCalendar
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.copyPlainText
import com.rendyhd.vicu.ui.screens.dictation.newProjectPlace
import com.rendyhd.vicu.ui.screens.dictation.planGroups
import com.rendyhd.vicu.util.DateDisplay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Make tasks from notes (`docs/ai-assistant-v1.md`, section 4.9) over the screens: the pages (typed
 * text, files, PDFs, scans) with their transcripts to read and correct, then the plan of all batches
 * like a brain dump's, with who each card is for, a tick per card, the decisions and open questions,
 * and Add with one Undo. Closing keeps the notes; Discard throws them away.
 */
@Composable
fun NotesSheet(
    onOpenSettings: () -> Unit,
    controller: NotesController = koinInject(),
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val ui = state ?: return
    if (!ui.open) return
    val owners by controller.owners.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = true) {
        if (ui.session.phase == NotesPhase.REVIEW) controller.backToPages() else controller.close()
    }
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 2.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().statusBarsPadding().fillMaxHeight(0.92f),
        ) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                Header(ui, owners, onClose = controller::close)
                Box(Modifier.weight(1f)) {
                    when (ui.session.phase) {
                        NotesPhase.REVIEW -> PlanBody(ui, owners, controller)
                        else -> PagesBody(ui, controller, onOpenSettings)
                    }
                }
                Footer(ui, owners, controller, onDiscard = { confirmDiscard = true })
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(Res.string.notes_discard_title)) },
            text = { Text(stringResource(Res.string.notes_discard_text)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; controller.discard() }) { Text(stringResource(Res.string.notes_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(Res.string.notes_keep)) } },
        )
    }
    if (ui.askImages) ImagesConsent(controller)
}

@Composable
private fun Header(ui: NotesUi, owners: NotesOwnerWorld, onClose: () -> Unit) {
    val plan = ui.session.plan
    Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            if (ui.session.phase == NotesPhase.REVIEW && plan != null) {
                pluralStringResource(Res.plurals.notes_plan_ready, plan.drafts.size, plan.drafts.size)
            } else {
                stringResource(Res.string.notes_title)
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.notes_close)) }
    }
}

// --- Pages ------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PagesBody(ui: NotesUi, controller: NotesController, onOpenSettings: () -> Unit) {
    val session = ui.session
    val pickFiles = rememberNotesFilePicker { controller.addFiles(it) }
    val scan = rememberNotesScanner { controller.addFiles(it) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var pickDate by remember { mutableStateOf(false) }
    val fmt = LocalDateFormat.current
    val date = runCatching { LocalDate.parse(session.date) }.getOrNull()
    val settingsStore: AiSettingsStore = koinInject()
    val aiSettings by settingsStore.settings.collectAsStateWithLifecycle(null)
    val reader = aiSettings?.readingModel()?.model
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "intro") {
            Text(stringResource(Res.string.notes_intro), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        item(key = "title") {
            OutlinedTextField(
                value = session.title,
                onValueChange = controller::setTitle,
                label = { Text(stringResource(Res.string.notes_notes_title)) },
                placeholder = { Text(stringResource(Res.string.notes_notes_title_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "date") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.notes_date), style = MaterialTheme.typography.labelMedium)
                    Text(date?.let { DateDisplay.formatFullDateWithYear(it, fmt) } ?: session.date, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(Res.string.notes_date_help), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                TextButton(onClick = { pickDate = true }) { Text(stringResource(Res.string.notes_change_date)) }
            }
        }
        item(key = "sources") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = pickFiles) { Text(stringResource(Res.string.notes_add_files)) }
                if (scan != null) OutlinedButton(onClick = scan) { Text(stringResource(Res.string.notes_scan)) }
                OutlinedButton(onClick = { typing = !typing }) { Text(stringResource(Res.string.notes_add_text)) }
            }
        }
        if (typing) {
            item(key = "typing") {
                Column {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text(stringResource(Res.string.notes_text_label)) },
                        placeholder = { Text(stringResource(Res.string.notes_text_placeholder)) },
                        minLines = 3,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilledTonalButton(
                        onClick = {
                            controller.addText(typed)
                            typed = ""
                            typing = false
                        },
                        enabled = typed.isNotBlank(),
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text(stringResource(Res.string.notes_add_this_text)) }
                }
            }
        }
        if (ui.problems.isNotEmpty()) {
            item(key = "problems") {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        for (problem in ui.problems) Text(problem.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = controller::dismissProblems) { Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.notes_close)) }
                }
            }
        }
        item(key = "count") {
            Text(
                if (session.pages.isEmpty()) stringResource(Res.string.notes_empty) else pluralStringResource(Res.plurals.notes_pages, session.pages.size, session.pages.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        val pictures = session.pages.any { it.kind.isImage }
        if (pictures && reader != null) {
            item(key = "reader") { Text(stringResource(Res.string.notes_read_by, reader), style = MaterialTheme.typography.bodySmall, color = muted) }
        }
        ui.checkingReader?.let { model ->
            item(key = "checking") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.notes_testing_reader, model), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (ui.noReader) {
            item(key = "noReader") {
                Column {
                    Text(stringResource(Res.string.notes_no_reader), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { controller.dismissNoReader(); controller.close(); onOpenSettings() }) {
                        Text(stringResource(Res.string.settings_assistant_read_model))
                    }
                }
            }
        }
        items(session.pages, key = { it.id }) { page ->
            val index = session.pages.indexOf(page)
            PageCard(page, index + 1, isFirst = index == 0, isLast = index == session.pages.lastIndex, controller)
        }
        session.organizeError?.let { error ->
            item(key = "organizeError") {
                Column {
                    Text(
                        stringResource(Res.string.notes_organize_failed, session.batchesDone + 1, session.batches, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    val soFar = session.plan?.drafts?.size ?: 0
                    if (soFar > 0) {
                        TextButton(onClick = controller::reviewSoFar) { Text(pluralStringResource(Res.plurals.notes_review_so_far, soFar, soFar)) }
                    }
                }
            }
        }
    }
    if (pickDate) {
        AlertDialog(
            onDismissRequest = { pickDate = false },
            title = { Text(stringResource(Res.string.notes_date)) },
            text = {
                MonthCalendar(selected = date, onSelect = {
                    controller.setDate(it)
                    pickDate = false
                })
            },
            confirmButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(Res.string.notes_close)) } },
        )
    }
}

@Composable
private fun PageCard(page: NotesSourcePage, n: Int, isFirst: Boolean, isLast: Boolean, controller: NotesController) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val kind = when (page.kind) {
        PageKind.TEXT -> stringResource(Res.string.notes_kind_text)
        PageKind.PDF_TEXT -> stringResource(Res.string.notes_kind_pdf)
        PageKind.PDF_IMAGE -> stringResource(Res.string.notes_kind_pdf_image)
        PageKind.IMAGE -> stringResource(Res.string.notes_kind_image)
    }
    val name = page.name.ifBlank { stringResource(Res.string.notes_typed_text) }
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.notes_page, n), style = MaterialTheme.typography.titleSmall)
                    Text(listOf(kind, name).joinToString(SEPARATOR), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { controller.movePage(page.id, -1) }, enabled = !isFirst) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(Res.string.notes_move_up, n))
                }
                IconButton(onClick = { controller.movePage(page.id, 1) }, enabled = !isLast) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = stringResource(Res.string.notes_move_down, n))
                }
                IconButton(onClick = { controller.removePage(page.id) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(Res.string.notes_remove_page, n))
                }
            }
            if (page.pdfPath != null) {
                when (page.kind) {
                    PageKind.PDF_TEXT -> TextButton(onClick = { controller.readAsImage(page.id) }) { Text(stringResource(Res.string.notes_read_as_image)) }
                    PageKind.PDF_IMAGE -> if (page.textLayer != null) {
                        TextButton(onClick = { controller.useTextLayer(page.id) }) { Text(stringResource(Res.string.notes_use_text_layer)) }
                    }
                    else -> Unit
                }
            }
            if (page.kind.isImage && page.state != PageState.READ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    when (page.state) {
                        PageState.READING -> {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(Res.string.notes_reading), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                        }
                        PageState.FAILED -> {
                            Text(
                                stringResource(Res.string.notes_failed, page.error.orEmpty()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { controller.read(page.id) }) { Text(stringResource(Res.string.notes_retry)) }
                        }
                        else -> Text(stringResource(Res.string.notes_unread), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
            if (!page.kind.isImage || page.state == PageState.READ) {
                val description = stringResource(Res.string.notes_page_text, n)
                OutlinedTextField(
                    value = page.text,
                    onValueChange = { controller.setPageText(page.id, it) },
                    minLines = 2,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp).semantics { contentDescription = description },
                )
            }
        }
    }
}

@Composable
private fun ImagesConsent(controller: NotesController) {
    val settingsStore: AiSettingsStore = koinInject()
    val settings by settingsStore.settings.collectAsStateWithLifecycle(null)
    val provider = settings?.providerId?.let { AiContract.provider(it)?.name } ?: settings?.address.orEmpty()
    AlertDialog(
        onDismissRequest = { controller.answerImages(false) },
        title = { Text(stringResource(Res.string.notes_consent_title, provider)) },
        text = { Text(stringResource(Res.string.notes_consent_text, provider)) },
        confirmButton = { TextButton(onClick = { controller.answerImages(true) }) { Text(stringResource(Res.string.notes_consent_agree)) } },
        dismissButton = { TextButton(onClick = { controller.answerImages(false) }) { Text(stringResource(Res.string.notes_consent_cancel)) } },
    )
}

// --- The plan -----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanBody(ui: NotesUi, owners: NotesOwnerWorld, controller: NotesController) {
    val session = ui.session
    val plan = session.plan ?: return
    val editable = !ui.adding && !ui.updating
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val byRef = plan.drafts.associateBy { it.ref }
    val ticked = plan.drafts.count { owners.choice(it, session).second }
    val repeated = plan.dropped.count { it.field == "duplicate" }
    val overLimit = plan.dropped.any { it.field == "task" && it.ref == "" && plan.drafts.size >= AiContract.limits.notesTasksMax }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ui.updating || ui.adding) item(key = "busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item(key = "bar") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (filter in NotesFilter.entries) {
                        FilterChip(
                            selected = session.filter == filter,
                            onClick = { controller.setFilter(filter) },
                            enabled = editable,
                            label = {
                                Text(
                                    when (filter) {
                                        NotesFilter.MINE -> stringResource(Res.string.notes_plan_filter_mine)
                                        NotesFilter.MINE_AND_WAITING -> stringResource(Res.string.notes_plan_filter_known)
                                        NotesFilter.EVERYTHING -> stringResource(Res.string.notes_plan_filter_all)
                                    },
                                )
                            },
                        )
                    }
                }
                Text(stringResource(Res.string.notes_plan_ticked, ticked, plan.drafts.size), style = MaterialTheme.typography.bodySmall, color = muted)
                if (owners.people.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = session.agreed, role = Role.Switch, enabled = editable, onValueChange = controller::setAgreed),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(Res.string.notes_plan_agreed), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Switch(checked = session.agreed, onCheckedChange = null)
                    }
                    Text(stringResource(Res.string.notes_plan_agreed_help), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                if (repeated > 0) Text(pluralStringResource(Res.plurals.notes_plan_repeated, repeated, repeated), style = MaterialTheme.typography.bodySmall, color = muted)
                if (overLimit) Text(stringResource(Res.string.notes_plan_over_limit, AiContract.limits.notesTasksMax), style = MaterialTheme.typography.bodySmall, color = muted)
                if (!NotesRules.canUpdate(session.batches, plan.drafts.size)) {
                    Text(stringResource(Res.string.notes_plan_update_off, AiContract.limits.brainDumpTasksMax), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
        for (group in planGroups(plan.projects, plan.drafts.map { it.draft })) {
            item(key = group.key) {
                val project = group.newProject
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (project != null) Icons.Outlined.CreateNewFolder else Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = if (project != null) MaterialTheme.colorScheme.primary else muted,
                    )
                    if (project == null) {
                        Text(
                            ui.context?.projects?.firstOrNull { it.id == group.projectId }?.path.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 12.dp).weight(1f),
                        )
                    } else {
                        Column(Modifier.padding(start = 8.dp).weight(1f)) {
                            OutlinedTextField(
                                value = project.title,
                                onValueChange = { controller.renameProject(project.ref, it) },
                                enabled = editable,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(newProjectPlace(project, plan.projects, ui.context), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { controller.removeProject(project.ref) }, enabled = editable) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.notes_discard))
                        }
                    }
                }
            }
            items(group.drafts, key = { it.ref }) { draft ->
                val card = byRef.getValue(draft.ref)
                NotesCard(card, ui, owners, editable, controller)
            }
        }
        if (plan.decisions.isNotEmpty() || plan.questions.isNotEmpty()) {
            item(key = "decisions") { Decisions(ui, owners, controller) }
        }
        if (plan.unclear.isNotEmpty()) {
            item(key = "unclear") {
                Column { for (note in plan.unclear) Text(BULLET + note, style = MaterialTheme.typography.bodySmall, color = muted) }
            }
        }
        if (NotesRules.canUpdate(session.batches, plan.drafts.size)) {
            item(key = "update") { UpdateRow(ui, controller) }
        }
    }
}

@Composable
private fun UpdateRow(ui: NotesUi, controller: NotesController) {
    var words by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = words,
            onValueChange = { words = it },
            placeholder = { Text(stringResource(Res.string.notes_plan_change)) },
            maxLines = 4,
            enabled = !ui.adding && !ui.updating,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
            onClick = {
                controller.update(words)
                words = ""
            },
            enabled = words.isNotBlank() && !ui.adding && !ui.updating,
            modifier = Modifier.padding(start = 8.dp),
        ) { Text(stringResource(Res.string.notes_plan_update)) }
    }
}

/** A card of the plan: its tick and who it is for above the draft, the line it came from; dimmed while not ticked. */
@Composable
private fun NotesCard(card: NotesDraft, ui: NotesUi, owners: NotesOwnerWorld, editable: Boolean, controller: NotesController) {
    val session = ui.session
    val plan = session.plan ?: return
    val (action, ticked) = owners.choice(card, session)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.alpha(if (ticked) 1f else 0.55f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tickLabel = stringResource(Res.string.notes_plan_include, card.draft.title)
            Checkbox(
                checked = ticked,
                onCheckedChange = { controller.setChoice(card.ref, (session.choices[card.ref] ?: NotesChoice()).copy(ticked = it)) },
                enabled = editable,
                modifier = Modifier.semantics { contentDescription = tickLabel },
            )
            OwnerChip(card, action, owners, editable) { chosen ->
                controller.setChoice(card.ref, (session.choices[card.ref] ?: NotesChoice()).copy(action = chosen))
            }
        }
        card.page?.let { page ->
            Text(
                card.from?.let { stringResource(Res.string.notes_plan_from, page, it) } ?: stringResource(Res.string.notes_page, page),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
            )
        }
        DraftCard(
            draft = card.draft,
            context = ui.context,
            dropped = plan.dropped.filter { it.ref == card.ref && it.field !in NOTES_FIELDS },
            changed = emptySet(),
            editable = editable,
            onChange = { controller.updateDraft(card.ref, it) },
            onRemove = { controller.removeDraft(card.ref) },
            newProjects = plan.projects,
            brainDump = true,
        )
    }
}

@Composable
private fun OwnerChip(card: NotesDraft, action: OwnerAction, owners: NotesOwnerWorld, editable: Boolean, onChoose: (OwnerAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val projectId = if (card.draft.projectRef != null) null else card.draft.projectId
    val members = owners.members(projectId)
    val owner = card.owner?.takeIf { it != "me" }
    // The Pro people the owner's name could be, and the one chosen.
    val chosenPerson = (action as? OwnerAction.Confirm)?.personId ?: (action as? OwnerAction.Promise)?.personId
    val people = if (owner == null) emptyList() else owners.people.filter { p ->
        p.id == chosenPerson || NotesRules.defaultOwnerAction(owner, emptyList(), listOf(p), false) != OwnerAction.Name
    }
    val options = buildList<Pair<OwnerAction, String>> {
        add(OwnerAction.Mine to stringResource(Res.string.notes_plan_mine))
        for (m in members) add(OwnerAction.Assign(m.id) to stringResource(Res.string.notes_plan_assign, m.title))
        for (p in people) {
            add(OwnerAction.Confirm(p.id) to stringResource(Res.string.notes_plan_confirm, p.title))
            add(OwnerAction.Promise(p.id) to stringResource(Res.string.notes_plan_promise, p.title))
        }
        if (owner != null) add(OwnerAction.Name to stringResource(Res.string.notes_plan_named, owner))
    }
    val current = options.firstOrNull { it.first == action }?.second ?: stringResource(Res.string.notes_plan_mine)
    val description = stringResource(Res.string.notes_plan_owner)
    Box {
        AssistChip(
            onClick = { open = true },
            enabled = editable,
            label = { Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.semantics { contentDescription = description },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((option, label) in options) {
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onChoose(option) })
            }
        }
    }
}

@Composable
private fun Decisions(ui: NotesUi, owners: NotesOwnerWorld, controller: NotesController) {
    val plan = ui.session.plan ?: return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf<String?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    @Composable
    fun list(key: String, heading: String, items: List<Pair<String, Int?>>) {
        if (items.isEmpty()) return
        OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(heading, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch {
                            clipboard.copyPlainText(heading, items.joinToString("\n") { DASH + it.first })
                            copied = key
                        }
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(if (copied == key) stringResource(Res.string.notes_plan_copied) else stringResource(Res.string.notes_plan_copy))
                    }
                }
                for ((text, page) in items) {
                    val pageText = page?.let { SEPARATOR + stringResource(Res.string.notes_page, it) }.orEmpty()
                    Text(BULLET + text + pageText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list("decisions", stringResource(Res.string.notes_plan_decisions), plan.decisions.map { (it.by?.let { by -> "${it.what} ($by)" } ?: it.what) to it.page })
        list("questions", stringResource(Res.string.notes_plan_questions), plan.questions.map { (it.ask?.let { ask -> "${it.what} ($ask)" } ?: it.what) to it.page })
        if (owners.people.isNotEmpty() && plan.decisions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().toggleable(value = ui.session.toLog, role = Role.Checkbox, onValueChange = controller::setToLog),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = ui.session.toLog, onCheckedChange = null)
                Text(stringResource(Res.string.notes_plan_to_log), style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

// --- Buttons ----------------------------------------------------------------------------------

@Composable
private fun Footer(ui: NotesUi, owners: NotesOwnerWorld, controller: NotesController, onDiscard: () -> Unit) {
    val session = ui.session
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        when (session.phase) {
            NotesPhase.ORGANIZING -> {
                Text(
                    stringResource(Res.string.notes_organizing, (session.batchesDone + 1).coerceAtMost(session.batches), session.batches),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = controller::cancelOrganize) { Text(stringResource(Res.string.notes_consent_cancel)) }
                }
            }
            NotesPhase.PAGES -> {
                val toRead = session.pages.count { it.needsReading }
                val reading = session.pages.count { it.state == PageState.READING }
                if (reading > 0) {
                    val done = session.pages.count { it.kind.isImage && it.state == PageState.READ }
                    val total = session.pages.count { it.kind.isImage }
                    Text(stringResource(Res.string.notes_reading_progress, done, total), style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDiscard) { Text(stringResource(Res.string.notes_discard)) }
                    Spacer(Modifier.weight(1f))
                    if (toRead > 0) {
                        Button(onClick = { controller.read() }, enabled = reading == 0 && ui.checkingReader == null) {
                            Text(pluralStringResource(Res.plurals.notes_read, toRead, toRead))
                        }
                    } else {
                        Button(
                            onClick = controller::organize,
                            enabled = session.pages.any { it.text.isNotBlank() },
                        ) { Text(stringResource(if (session.organizeError != null) Res.string.notes_retry else Res.string.notes_organize)) }
                    }
                }
            }
            NotesPhase.REVIEW -> {
                val plan = session.plan
                val ticked = plan?.drafts?.count { owners.choice(it, session).second } ?: 0
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = controller::backToPages, enabled = !ui.adding) { Text(stringResource(Res.string.notes_plan_back_to_pages)) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = controller::add, enabled = ticked > 0 && !ui.adding && !ui.updating) {
                        Text(if (ui.adding) stringResource(Res.string.notes_plan_adding) else pluralStringResource(Res.plurals.notes_plan_add, ticked, ticked))
                    }
                }
            }
        }
    }
}

/** The fields of 4.9.6 the cards do not explain one by one: a page or a line not found. */
private val NOTES_FIELDS = setOf("page", "from", "duplicate")

private const val SEPARATOR = " · " // i18n-ignore: a middle dot between parts
private const val BULLET = "• " // i18n-ignore: a bullet
private const val DASH = "- " // i18n-ignore: a list dash when copied
