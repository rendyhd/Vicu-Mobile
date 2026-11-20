package com.rendyhd.vicu.ui.screens.pro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.repository.ProActions
import com.rendyhd.vicu.data.repository.ProWorld
import com.rendyhd.vicu.data.repository.ProWorldSource
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.i18n.ProText
import com.rendyhd.vicu.i18n.UiText
import com.rendyhd.vicu.resources.Res
import com.rendyhd.vicu.resources.pro_follow_ups_bring
import com.rendyhd.vicu.resources.pro_follow_ups_bring_meta
import com.rendyhd.vicu.resources.pro_follow_ups_bring_meta_no_one
import com.rendyhd.vicu.resources.pro_follow_ups_check
import com.rendyhd.vicu.resources.pro_follow_ups_checked
import com.rendyhd.vicu.resources.pro_follow_ups_checked_toast
import com.rendyhd.vicu.resources.pro_follow_ups_dismiss
import com.rendyhd.vicu.resources.pro_follow_ups_empty
import com.rendyhd.vicu.resources.pro_follow_ups_note
import com.rendyhd.vicu.resources.pro_follow_ups_note_where
import com.rendyhd.vicu.resources.pro_follow_ups_open
import com.rendyhd.vicu.resources.pro_follow_ups_recap_standup
import com.rendyhd.vicu.resources.pro_follow_ups_recap_weekly
import com.rendyhd.vicu.resources.pro_follow_ups_recap_where
import com.rendyhd.vicu.resources.pro_follow_ups_share_message
import com.rendyhd.vicu.resources.pro_follow_ups_shared
import com.rendyhd.vicu.resources.pro_follow_ups_title
import com.rendyhd.vicu.resources.pro_off_notice
import com.rendyhd.vicu.resources.pro_off_settings
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.screens.shared.CompletionToast
import com.rendyhd.vicu.ui.screens.shared.NoCompletionToast
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.PlatformFiles
import com.rendyhd.vicu.util.pro.CheckItem
import com.rendyhd.vicu.util.pro.Documents
import com.rendyhd.vicu.util.pro.FollowUpLine
import com.rendyhd.vicu.util.pro.Groups
import com.rendyhd.vicu.util.pro.LedgerRules
import com.rendyhd.vicu.util.pro.Promises
import com.rendyhd.vicu.util.pro.localDayOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** One line of Follow-ups as the screen draws it (contract 21.18). */
sealed class FollowUpRow {
    abstract val key: String

    /** A verdict due: the task's row, whose person mark opens the verdicts. */
    data class Verdict(override val key: String, val task: Task) : FollowUpRow()

    /** Checks before due for one person: their actions by due day, the first due on [due]. */
    data class Check(
        override val key: String,
        val person: Long,
        val name: String,
        val tasks: List<Task>,
        val due: LocalDate,
    ) : FollowUpRow()

    /** A promise to look at again on [checkOn]; [person] is its owner's name, when it has one. */
    data class Bring(override val key: String, val task: Task, val person: String?, val checkOn: LocalDate) : FollowUpRow()

    /** A session whose recap was not copied (the recap is copied on the desktop or the web). */
    data class Recap(override val key: String, val group: String, val standup: Boolean, val day: LocalDate) : FollowUpRow()

    /** The weekly note is due (written on the desktop or the web). */
    data class Note(override val key: String) : FollowUpRow()
}

data class FollowUpsUiState(val on: Boolean = false, val rows: List<FollowUpRow> = emptyList())

/**
 * Follow-ups (contract 21.18, rule `FollowUps.followUps`): everything that needs a move from you,
 * as one list. A verdict through the row's own verdict sheet, a check message through the share
 * sheet (which marks its promises as checked, with Undo), "Checked" for a bring-back line, and
 * "Dismiss for today" on every line. Sessions, their recaps and the weekly note are desktop and web
 * features: their lines say so here.
 */
class FollowUpsViewModel(
    worldSource: ProWorldSource,
    taskRepository: TaskRepository,
    private val actions: ProActions,
    private val platformFiles: PlatformFiles,
    private val appMessages: AppMessages,
    navigationTicker: NavigationTicker = NavigationTicker(),
    completionToast: CompletionToast = NoCompletionToast,
) : ProTaskListViewModel(worldSource, taskRepository, navigationTicker, completionToast) {

    private fun build(world: ProWorld): FollowUpsUiState {
        if (!world.promisesOn) return FollowUpsUiState(on = false)
        val byId = world.openById
        val rows = world.followUps.mapNotNull { line ->
            when (line) {
                is FollowUpLine.Verdict -> byId[line.taskId]?.let { FollowUpRow.Verdict(line.key, it) }
                is FollowUpLine.Check -> {
                    val tasks = line.tasks.mapNotNull { byId[it] }
                    if (tasks.isEmpty()) null else FollowUpRow.Check(line.key, line.person, world.nameOf(line.person), tasks, line.due)
                }
                is FollowUpLine.Bring -> byId[line.taskId]?.let { task ->
                    FollowUpRow.Bring(line.key, task, line.person?.let(world::nameOf), line.checkOn)
                }
                is FollowUpLine.Recap -> {
                    val group = Groups.groupOfMeeting(world.ledger, line.meeting)
                    val round = world.ledger.value("rounds", line.round)
                    val day = localDayOf(line.ended, world.zone) ?: return@mapNotNull null
                    FollowUpRow.Recap(
                        key = line.key,
                        group = group?.let { world.groupName(it.label) }.orEmpty(),
                        standup = (round?.get("kind") as? kotlinx.serialization.json.JsonPrimitive)?.content == "standup",
                        day = day,
                    )
                }
                is FollowUpLine.Note -> FollowUpRow.Note(line.key)
            }
        }
        // Verdicts come first (21.18). A verdict row completed here stays a moment, as on every list.
        val verdicts = completions.merge(rows.filterIsInstance<FollowUpRow.Verdict>().map { it.task })
        return FollowUpsUiState(
            on = true,
            rows = verdicts.map { FollowUpRow.Verdict("verdict:${it.id}", it) } + rows.filterNot { it is FollowUpRow.Verdict },
        )
    }

    val uiState: StateFlow<FollowUpsUiState> = combine(worldSource.world, completions.state) { world, _ -> build(world) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(worldSource.world.value))

    /** "Dismiss for today": the line shows again tomorrow (per device). */
    fun dismiss(key: String) = actions.dismissFollowUp(key)

    /**
     * The check message of [row] through the share sheet, written in the language of the group of
     * its first action (else the Pro setting, else English); then its promises are marked as
     * checked, with Undo.
     */
    fun shareCheck(row: FollowUpRow.Check, fmt: DateDisplayFormat) {
        viewModelScope.launch {
            val world = worldSource.world.value
            val items = row.tasks.map { CheckItem(it.title, Promises.effectiveDue(it, world.promiseFor(it), world.zone)) }
            val document = Documents.checkDocument(row.name, items) ?: return@launch
            val groupLabel = row.tasks.first().labels.map { it.id }.filter { it in world.groupLabelIds }.minOrNull()
            val group = groupLabel?.let { world.groupByLabel[it] }
            val language = Documents.textLanguage(group?.language, LedgerRules.settings(world.ledger).value)
            val rendered = ProText.render(document, language, fmt, world.today)
            val problem = platformFiles.shareText(rendered.text)
            if (problem != null) {
                appMessages.post(UiText.Raw(problem))
                return@launch
            }
            val ids = row.tasks.map { it.id }
            actions.markChecked(ids, UiText.Plural(Res.plurals.pro_follow_ups_shared, ids.size))
        }
    }

    /** "Checked" on a bring-back line: the promise is marked as checked, with Undo. */
    fun checked(row: FollowUpRow.Bring) =
        actions.markChecked(listOf(row.task.id), UiText.Res(Res.string.pro_follow_ups_checked_toast, row.task.title))
}

/** Follow-ups (contract 21.18): one ordered list of what needs a move from you. */
@Composable
fun FollowUpsScreen(
    onTaskClick: (Long) -> Unit,
    onOpenDrawer: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: FollowUpsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val held by viewModel.completedIds.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val fmt = LocalDateFormat.current
    val today = LocalClockDay.current.date
    ProScreenScaffold(stringResource(Res.string.pro_follow_ups_title), onOpenDrawer, onNavigateToSearch, error, viewModel::clearError) {
        if (!state.on) {
            item(key = "off") { ProOffNoticeText(onOpenSettings) }
            return@ProScreenScaffold
        }
        if (state.rows.isEmpty()) {
            item(key = "empty") {
                EmptyState(icon = Icons.Outlined.TaskAlt, title = stringResource(Res.string.pro_follow_ups_empty))
            }
        }
        state.rows.forEach { row ->
            when (row) {
                is FollowUpRow.Verdict -> item(key = row.key, contentType = "task") {
                    Column(Modifier.animateItem()) {
                        ProTaskRow(row.task, held, viewModel, onTaskClick)
                        if (row.task.id !in held) {
                            DismissRow(onDismiss = { viewModel.dismiss(row.key) })
                        }
                    }
                }
                is FollowUpRow.Check -> item(key = row.key, contentType = "follow_up") {
                    FollowUpLineBox(Modifier.animateItem()) {
                        Text(
                            pluralStringResource(
                                Res.plurals.pro_follow_ups_check,
                                row.tasks.size,
                                Documents.firstName(row.name),
                                row.tasks.size,
                                DateDisplay.formatWeekdayDate(row.due, today, fmt),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        row.tasks.forEach { task ->
                            Text(
                                task.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        LineButtons {
                            OutlinedButton(onClick = { viewModel.shareCheck(row, fmt) }) {
                                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                                Text(stringResource(Res.string.pro_follow_ups_share_message))
                            }
                            TextButton(onClick = { viewModel.dismiss(row.key) }) { Text(stringResource(Res.string.pro_follow_ups_dismiss)) }
                        }
                    }
                }
                is FollowUpRow.Bring -> item(key = row.key, contentType = "follow_up") {
                    FollowUpLineBox(Modifier.animateItem()) {
                        Text(
                            stringResource(Res.string.pro_follow_ups_bring, row.task.title),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val day = DateDisplay.formatWeekdayDate(row.checkOn, today, fmt)
                        Text(
                            if (row.person != null) {
                                stringResource(Res.string.pro_follow_ups_bring_meta, row.person, day)
                            } else {
                                stringResource(Res.string.pro_follow_ups_bring_meta_no_one, day)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LineButtons {
                            OutlinedButton(onClick = { viewModel.checked(row) }) { Text(stringResource(Res.string.pro_follow_ups_checked)) }
                            OutlinedButton(onClick = { onTaskClick(row.task.id) }) { Text(stringResource(Res.string.pro_follow_ups_open)) }
                            TextButton(onClick = { viewModel.dismiss(row.key) }) { Text(stringResource(Res.string.pro_follow_ups_dismiss)) }
                        }
                    }
                }
                is FollowUpRow.Recap -> item(key = row.key, contentType = "follow_up") {
                    FollowUpLineBox(Modifier.animateItem()) {
                        val day = DateDisplay.formatWeekdayDate(row.day, today, fmt)
                        Text(
                            stringResource(
                                if (row.standup) Res.string.pro_follow_ups_recap_standup else Res.string.pro_follow_ups_recap_weekly,
                                row.group,
                                day,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(Res.string.pro_follow_ups_recap_where),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LineButtons {
                            TextButton(onClick = { viewModel.dismiss(row.key) }) { Text(stringResource(Res.string.pro_follow_ups_dismiss)) }
                        }
                    }
                }
                is FollowUpRow.Note -> item(key = row.key, contentType = "follow_up") {
                    FollowUpLineBox(Modifier.animateItem()) {
                        Text(stringResource(Res.string.pro_follow_ups_note), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(Res.string.pro_follow_ups_note_where),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LineButtons {
                            TextButton(onClick = { viewModel.dismiss(row.key) }) { Text(stringResource(Res.string.pro_follow_ups_dismiss)) }
                        }
                    }
                }
            }
        }
    }
}

/** A follow-up line that is not a task row: its text, then its buttons, then a hairline. */
@Composable
private fun FollowUpLineBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineButtons(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** "Dismiss for today" under a verdict's task row. */
@Composable
private fun DismissRow(onDismiss: () -> Unit) {
    FlowRow(modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 16.dp)) {
        TextButton(onClick = onDismiss) { Text(stringResource(Res.string.pro_follow_ups_dismiss)) }
    }
}

/** A Pro page opened while its layer is off, from a link or the history: says so, changes nothing. */
@Composable
internal fun ProOffNoticeText(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(Res.string.pro_off_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onOpenSettings) { Text(stringResource(Res.string.pro_off_settings)) }
    }
}
