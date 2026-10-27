package com.rendyhd.vicu.ui.screens.guided

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.ai.dictation.DictationMic
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.navigation.ScreenReady
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.Guided
import com.rendyhd.vicu.util.GuidedQueue
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** The GTD part a step belongs to, shown above its first step. */
private fun groupOf(stepId: String): String = when (stepId) {
    Guided.STEP_INBOX, Guided.STEP_MIND -> "Get clear"
    Guided.STEP_WAITING -> "Get creative"
    else -> "Get current"
}

/**
 * The weekly review (docs/cross-app-semantics-v1.md, section 14.7): a checklist of steps in GTD
 * order, each opening its session or its list, Next for the first one not done, and Finish.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyReviewScreen(
    onClose: () -> Unit,
    onOpenSort: (GuidedQueue) -> Unit,
    onOpenReview: () -> Unit,
    onQuickAdd: () -> Unit,
    onTaskClick: (Long) -> Unit,
    viewModel: WeeklyReviewViewModel = koinViewModel(),
    dictation: DictationMic = koinInject(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenReady(!state.loading)
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    fun openStep(id: String) {
        when (id) {
            Guided.STEP_INBOX -> onOpenSort(GuidedQueue.INBOX)
            Guided.STEP_OVERDUE -> onOpenSort(GuidedQueue.OVERDUE)
            Guided.STEP_WAITING -> onOpenSort(GuidedQueue.WAITING)
            Guided.STEP_REVIEW -> onOpenReview()
            Guided.STEP_MIND -> {
                viewModel.markDone(id)
                if (state.dictationReady) dictation.tryIt(brainDump = true) else onQuickAdd()
            }
            else -> {
                viewModel.markDone(id)
                open = if (open == id) null else id
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Weekly review")
                        if (state.lastDone.isNotEmpty() && !state.finished) {
                            Text(state.lastDone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close") } },
                actions = { if (!state.finished && !state.loading) TextButton(onClick = viewModel::finish) { Text("Finish") } },
            )
        },
        bottomBar = {
            if (!state.loading && !state.finished) {
                Surface(tonalElevation = 2.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Spacer(Modifier.weight(1f))
                        val next = state.next
                        if (next == null) {
                            Button(onClick = viewModel::finish) { Text("Finish") }
                        } else {
                            Button(onClick = { openStep(next.step.id) }) { Text("Next: ${next.step.title}") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.finished -> FinishedPane(
                    title = "Weekly review done",
                    summary = "${state.steps.count { it.done }} of ${state.steps.size} steps done. Ask will offer the next one in a week.",
                    canUndo = false,
                    busy = false,
                    onUndo = {},
                    onDone = onClose,
                )
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    var group: String? = null
                    for (item in state.steps) {
                        val heading = groupOf(item.step.id)
                        if (heading != group) {
                            group = heading
                            item(key = "h-$heading") {
                                Text(
                                    heading,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                                )
                            }
                        }
                        item(key = item.step.id) {
                            StepRow(item, expanded = open == item.step.id, onClick = { openStep(item.step.id) })
                        }
                        if (open == item.step.id) {
                            val tasks = if (item.step.id == Guided.STEP_DONE) state.recentlyDone else state.lookAhead
                            items(tasks, key = { "${item.step.id}-${it.id}" }) { task ->
                                LookRow(task, done = item.step.id == Guided.STEP_DONE, onClick = { onTaskClick(task.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(item: WeeklyStepUi, expanded: Boolean, onClick: () -> Unit) {
    val step = item.step
    val detail = when {
        step.id == Guided.STEP_MIND -> "Talk or type out whatever is on your mind"
        step.count == 0 -> "Nothing here"
        step.id == Guided.STEP_DONE -> "${step.count} completed this week"
        step.id == Guided.STEP_UPCOMING -> "${step.count} due in the next ${Guided.LOOK_AHEAD_DAYS} days"
        step.id == Guided.STEP_REVIEW -> if (step.count == 1) "1 project due" else "${step.count} projects due"
        else -> if (step.count == 1) "1 task" else "${step.count} tasks"
    }
    val looks = step.id == Guided.STEP_DONE || step.id == Guided.STEP_UPCOMING
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (item.done) "Done" else "Not done",
            tint = if (item.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(step.title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            when {
                looks && expanded -> Icons.Outlined.ExpandLess
                looks -> Icons.Outlined.ExpandMore
                else -> Icons.AutoMirrored.Outlined.KeyboardArrowRight
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A task of Look back or Look ahead: only looked at; a tap opens it. */
@Composable
private fun LookRow(task: Task, done: Boolean, onClick: () -> Unit) {
    val day = LocalClockDay.current
    val format = LocalDateFormat.current
    val date = if (done) {
        DateDisplay.formatDue(DateContext.ROW, task.doneAt, day.date, day.zone, format)
    } else {
        DateDisplay.formatDue(DateContext.ROW, task.dueDate, day.date, day.zone, format)
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 56.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(task.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (date.isNotBlank()) {
            Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
