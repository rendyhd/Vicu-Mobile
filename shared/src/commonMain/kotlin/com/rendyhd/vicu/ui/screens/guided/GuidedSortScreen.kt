package com.rendyhd.vicu.ui.screens.guided

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.assistant.dashedOutline
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.navigation.ScreenReady
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.GuidedQueue
import org.koin.compose.viewmodel.koinViewModel

/** The title of a session over [queue]. */
internal fun GuidedQueue.sessionTitle(): String = when (this) {
    GuidedQueue.INBOX -> "Sort the Inbox"
    GuidedQueue.OVERDUE -> "Sort out overdue"
    GuidedQueue.WAITING -> "Waiting a long time"
}

/**
 * A guided session over the Inbox, the overdue tasks or the ones waiting a long time
 * (docs/cross-app-semantics-v1.md, section 14.3): one card at a time with how far along the person
 * is, the card's choices, and at the end what changed with Undo all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuidedSortScreen(
    onClose: () -> Unit,
    onTaskClick: (Long) -> Unit,
    viewModel: GuidedSortViewModel = koinViewModel(),
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
                            Text(state.queue.sessionTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!state.finished && state.total > 0) {
                                Text(
                                    "${state.index + 1} of ${state.total}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
                    },
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
            if (!state.loading && !state.finished && state.task != null) {
                CardButtons(
                    canGoBack = state.canGoBack,
                    busy = state.busy,
                    onBack = viewModel::back,
                    onSkip = viewModel::skip,
                    onNext = viewModel::next,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val task = state.task
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.finished -> FinishedPane(
                    title = if (state.total == 0) "Nothing to sort" else "All done",
                    summary = if (state.total == 0) "There is nothing here right now." else state.summary,
                    canUndo = state.canUndo,
                    busy = state.busy,
                    onUndo = viewModel::undoAll,
                    onDone = onClose,
                )
                task != null -> SortCard(
                    task = task,
                    state = state,
                    viewModel = viewModel,
                    onOpenTask = { onTaskClick(task.id) },
                    onDelete = { if (state.confirmDelete) confirmDelete = task else viewModel.delete() },
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
                    viewModel.delete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SortCard(
    task: Task,
    state: GuidedSortUiState,
    viewModel: GuidedSortViewModel,
    onOpenTask: () -> Unit,
    onDelete: () -> Unit,
) {
    val day = LocalClockDay.current
    val format = LocalDateFormat.current
    val draft = state.draft
    val outline = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Column(Modifier.widthIn(max = 640.dp).align(Alignment.CenterHorizontally)) {
            OutlinedTextField(
                value = draft.title ?: task.title,
                onValueChange = viewModel::setTitle,
                textStyle = MaterialTheme.typography.titleMedium,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth().dashedOutline(outline, CardDraft.FIELD_TITLE in draft.suggested),
            )
            val meta = buildList {
                add(state.projectPath)
                DateDisplay.formatDue(DateContext.ROW, task.dueDate, day.date, day.zone, format).takeIf { it.isNotBlank() }?.let(::add)
                state.added?.let(::add)
            }.filter { it.isNotBlank() }.joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (DueDates.isOverdue(task.dueDate, day.date, day.zone)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenTask) { Text("Open") }
            }
            SuggestionLine(state.suggestion, state.suggesting)

            ChoiceHeading("When")
            WhenChoices(
                due = draft.due,
                currentDue = task.dueDate,
                suggested = CardDraft.FIELD_WHEN in draft.suggested,
                onChoose = viewModel::setDue,
            )

            ChoiceHeading("Project")
            val projectId = draft.projectId ?: task.projectId
            ProjectChoice(
                path = state.pathOf(projectId).ifBlank { state.projectPath },
                selectedProjectId = projectId,
                changed = draft.projectId != null,
                suggested = CardDraft.FIELD_PROJECT in draft.suggested,
                projects = state.pickerProjects,
                inboxId = state.inboxId,
                onChoose = viewModel::setProject,
                currentProjectId = task.projectId,
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val close = state.suggestion?.close
                if (close == "done") {
                    FilledTonalButton(onClick = viewModel::complete, enabled = !state.busy) { CompleteLabel() }
                } else {
                    OutlinedButton(onClick = viewModel::complete, enabled = !state.busy) { CompleteLabel() }
                }
                if (close == "delete") {
                    FilledTonalButton(
                        onClick = onDelete,
                        enabled = !state.busy,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { DeleteLabel() }
                } else {
                    TextButton(
                        onClick = onDelete,
                        enabled = !state.busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { DeleteLabel() }
                }
            }
        }
    }
}

@Composable
private fun CompleteLabel() {
    Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
    Text("Complete", modifier = Modifier.padding(start = 8.dp))
}

@Composable
private fun DeleteLabel() {
    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
    Text("Delete", modifier = Modifier.padding(start = 8.dp))
}

/** Back, Skip and [nextLabel] under every card or page; Next on a card without changes is Skip. */
@Composable
internal fun CardButtons(
    canGoBack: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    nextLabel: String = "Next",
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, enabled = canGoBack && !busy) { Text("Back") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onSkip, enabled = !busy) { Text("Skip") }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onNext, enabled = !busy) { Text(nextLabel) }
        }
    }
}
