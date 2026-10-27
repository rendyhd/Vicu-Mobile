package com.rendyhd.vicu.ui.screens.guided

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.assistant.dashedOutline
import com.rendyhd.vicu.ui.components.picker.WhenSheet
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.navigation.ScreenReady
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.Guided
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

/**
 * The project review session (docs/cross-app-semantics-v1.md, section 14.4), one project per page:
 * how it is doing, whether it is still going, its untouched tasks, the next step, and Mark
 * reviewed. Changes on a page are written at once; the end says what the session did, with Undo all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuidedReviewScreen(
    onClose: () -> Unit,
    onTaskClick: (Long) -> Unit,
    viewModel: GuidedReviewViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenReady(!state.loading)
    var confirmDelete by remember { mutableStateOf<Task?>(null) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Review projects")
                            if (!state.finished && state.total > 0) {
                                Text(
                                    "${state.index + 1} of ${state.total}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close") } },
                )
                if (!state.finished && state.total > 0) {
                    LinearProgressIndicator(
                        progress = { state.index.toFloat() / state.total.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
        },
        bottomBar = {
            if (!state.loading && !state.finished && state.project != null) {
                CardButtons(
                    canGoBack = state.canGoBack,
                    busy = state.busy,
                    onBack = viewModel::back,
                    onSkip = viewModel::skip,
                    onNext = viewModel::markReviewed,
                    nextLabel = "Mark reviewed",
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.finished -> FinishedPane(
                    title = if (state.total == 0) "Nothing to review" else "Review done",
                    summary = if (state.total == 0) "No project is due for review." else listOf(state.summary, state.taskSummary).filter { it.isNotBlank() }.joinToString("\n"),
                    canUndo = state.canUndo,
                    busy = state.busy,
                    onUndo = viewModel::undoAll,
                    onDone = onClose,
                )
                state.project != null -> ReviewPage(
                    state = state,
                    viewModel = viewModel,
                    onTaskClick = onTaskClick,
                    onDelete = { task -> if (state.confirmDelete) confirmDelete = task else viewModel.delete(task) },
                )
            }
        }
    }

    confirmDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${task.title}\"?") },
            text = { Text("Deleting cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    viewModel.delete(task)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReviewPage(
    state: GuidedReviewUiState,
    viewModel: GuidedReviewViewModel,
    onTaskClick: (Long) -> Unit,
    onDelete: (Task) -> Unit,
) {
    val project = state.project ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Column(Modifier.widthIn(max = 640.dp).align(Alignment.CenterHorizontally)) {
            Text(state.path, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                listOfNotNull(state.lastReviewed, state.health?.line).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (state.suggesting) SuggestionLine(null, waiting = true)

            ChoiceHeading("Still working on it?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = {}, label = { Text("Keep") })
                if (state.canArchive) {
                    FilterChip(
                        selected = false,
                        onClick = viewModel::archive,
                        enabled = !state.busy,
                        label = { Text("Archive") },
                        leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }

            if (state.untouched.isNotEmpty()) {
                ChoiceHeading("Untouched for ${Guided.REVIEW_UNTOUCHED_DAYS}+ days")
                OutlinedCard(Modifier.fillMaxWidth()) {
                    state.untouched.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        UntouchedTaskRow(row, state.busy, viewModel, onOpen = { onTaskClick(row.task.id) }, onDelete = { onDelete(row.task) })
                    }
                }
            }

            ChoiceHeading("Next step")
            val next = state.nextTask
            if (next != null) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onTaskClick(next.id) }) {
                            if (state.nextSuggested) {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = "Suggested", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                            }
                            Text(next.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            DueText(next)
                        }
                        Column(Modifier.padding(top = 8.dp)) {
                            WhenChoices(
                                due = state.nextDue,
                                currentDue = next.dueDate,
                                suggested = state.nextDue != null,
                                onChoose = { due -> viewModel.schedule(next, due) },
                                toggle = false,
                            )
                        }
                    }
                }
            } else {
                Text("No open task yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NewStepField(suggestedTitle = state.newStepTitle, projectTitle = project.title, busy = state.busy, onAdd = viewModel::addStep)
        }
    }
}

/** An untouched task: left as it is, given a date or none, completed or deleted; once handled, what happened. */
@Composable
private fun UntouchedTaskRow(row: UntouchedRow, busy: Boolean, viewModel: GuidedReviewViewModel, onOpen: () -> Unit, onDelete: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val task = row.task
    val note = row.note
    val day = LocalClockDay.current
    val format = LocalDateFormat.current
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = task.title.isNotEmpty(), onClick = onOpen)) {
                Text(task.title.ifEmpty { "A task" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = note ?: row.untouchedDays?.let { "Untouched for ${it} days" }
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (note == null) {
                val close = row.suggestion?.close
                IconButton(onClick = { picking = true }, enabled = !busy) { Icon(Icons.Outlined.Event, contentDescription = "Give ${task.title} a date") }
                if (close == "done") {
                    FilledTonalIconButton(onClick = { viewModel.complete(task) }, enabled = !busy) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = "Complete ${task.title}")
                    }
                } else {
                    IconButton(onClick = { viewModel.complete(task) }, enabled = !busy) { Icon(Icons.Outlined.CheckCircle, contentDescription = "Complete ${task.title}") }
                }
                if (close == "delete") {
                    FilledTonalIconButton(
                        onClick = onDelete,
                        enabled = !busy,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${task.title}") }
                } else {
                    IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${task.title}") }
                }
            }
        }
        val suggestion = row.suggestion
        if (note == null && suggestion != null) {
            SuggestionLine(suggestion, waiting = false)
            val due = suggestion.due
            if (due != null || suggestion.clearDue) {
                val label = if (due != null) DateDisplay.format(DateContext.CHIP, due, day.date, !suggestion.dueHasTime, format) else "No date"
                FilterChip(
                    selected = false,
                    onClick = { viewModel.schedule(task, if (due != null) DueChoice.On(due, suggestion.dueHasTime) else DueChoice.Clear) },
                    enabled = !busy,
                    label = { Text(label) },
                    leadingIcon = { Icon(Icons.Outlined.Event, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.padding(top = 4.dp).dashedOutline(MaterialTheme.colorScheme.primary, true),
                )
            }
        }
    }
    if (picking) {
        WhenSheet(
            currentDate = task.dueDate.takeIf { DateUtils.parseIsoDate(it) != null },
            onDateSelected = { iso ->
                DateUtils.parseIsoDate(iso)?.let { instant ->
                    val local = instant.toLocalDateTime(day.zone)
                    viewModel.schedule(task, DueChoice.On(LocalDateTime(local.date, local.time), !DueDates.isDateOnly(instant, day.zone)))
                }
            },
            onClearDate = { viewModel.schedule(task, DueChoice.Clear) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun DueText(task: Task) {
    val day = LocalClockDay.current
    val text = DateDisplay.formatDue(DateContext.ROW, task.dueDate, day.date, day.zone, LocalDateFormat.current)
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (DueDates.isOverdue(task.dueDate, day.date, day.zone)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/** "Or name a new task": a new next step in the project; the model's title fills it in, marked as suggested. */
@Composable
private fun NewStepField(suggestedTitle: String?, projectTitle: String, busy: Boolean, onAdd: (String) -> Unit) {
    var typed by rememberSaveable(projectTitle) { mutableStateOf<String?>(null) }
    val shown = typed ?: suggestedTitle.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
        OutlinedTextField(
            value = shown,
            onValueChange = { typed = it },
            placeholder = { Text("Or name a new task") },
            singleLine = true,
            modifier = Modifier.weight(1f).dashedOutline(MaterialTheme.colorScheme.primary, typed == null && suggestedTitle != null),
        )
        TextButton(
            onClick = {
                onAdd(shown)
                typed = ""
            },
            enabled = shown.isNotBlank() && !busy,
        ) { Text("Add") }
    }
}
