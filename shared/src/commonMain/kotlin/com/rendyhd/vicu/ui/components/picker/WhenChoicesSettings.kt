package com.rendyhd.vicu.ui.components.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rendyhd.vicu.data.repository.WhenChoicesPreferences
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.task.moveCustomActions
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DayChoiceId
import com.rendyhd.vicu.util.MoveStep
import com.rendyhd.vicu.util.TimeOfDay
import com.rendyhd.vicu.util.WhenChoiceSet
import com.rendyhd.vicu.util.WhenChoices
import com.rendyhd.vicu.util.WhenLogic
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableColumn
import com.rendyhd.vicu.resources.Res
import com.rendyhd.vicu.resources.when_choices_add_time
import com.rendyhd.vicu.resources.when_choices_back
import com.rendyhd.vicu.resources.when_choices_cancel
import com.rendyhd.vicu.resources.when_choices_change_time
import com.rendyhd.vicu.resources.when_choices_edit_action
import com.rendyhd.vicu.resources.when_choices_move_choice
import com.rendyhd.vicu.resources.when_choices_remove_time
import com.rendyhd.vicu.resources.when_choices_reset
import com.rendyhd.vicu.resources.when_choices_save
import com.rendyhd.vicu.resources.when_choices_time_label
import com.rendyhd.vicu.resources.when_choices_time_name
import org.jetbrains.compose.resources.stringResource

/** A time of day as its chip says it: "Morning 9:00". */
internal fun timeOfDayLabel(timeOfDay: TimeOfDay, fmt: DateDisplayFormat): String =
    "${timeOfDay.name} ${WhenLogic.timeLabel(timeOfDay.time, fmt)}"

/**
 * The When choices (contract section 23), synced to every device: the times of day, the day
 * choices in their order, and the Move by steps. Shown in Settings, Input, and behind the When
 * tray's Edit choices.
 */
@Composable
internal fun WhenChoicesEditor(
    choices: WhenChoiceSet,
    onTimes: (List<TimeOfDay>) -> Unit,
    onDays: (List<DayChoiceId>) -> Unit,
    onMoveBy: (List<MoveStep>) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = LocalDateFormat.current
    // The time of day being edited: its index, or -1 for a new one; null when none is.
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    Column(modifier = modifier) {
        Note("The choices the When pickers offer, the same on all your devices.")

        SubHeading("Times of day")
        choices.times.forEachIndexed { index, timeOfDay ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(onClickLabel = stringResource(Res.string.when_choices_edit_action)) { editing = index }
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(timeOfDay.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    WhenLogic.timeLabel(timeOfDay.time, fmt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (choices.times.size > 1) {
                    IconButton(onClick = { onTimes(choices.times.filterIndexed { i, _ -> i != index }) }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.when_choices_remove_time, timeOfDay.name))
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
        if (choices.times.size < WhenChoices.MAX_TIMES) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { editing = -1 }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(Res.string.when_choices_add_time), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Note("This evening and Tomorrow morning use the latest time from 17:00 and the earliest before 12:00.")

        SubHeading("Day choices")
        Note("Up to ${WhenChoices.MAX_DAYS}, in this order. No date is always last in quick add.")
        DayChoiceList(choices.days, onDays)

        SubHeading("Move by")
        Note("Offered when a task already has a date. An overdue task moves from today.")
        MoveStep.entries.forEach { step ->
            val on = step in choices.moveBy
            val full = !on && choices.moveBy.size >= WhenChoices.MAX_MOVE_BY
            SwitchLine(
                label = step.label,
                checked = on,
                enabled = !full,
                onChange = { checked ->
                    val next = if (checked) choices.moveBy + step else choices.moveBy - step
                    onMoveBy(MoveStep.entries.filter { it in next })
                },
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            TextButton(onClick = onReset) { Text(stringResource(Res.string.when_choices_reset)) }
        }
    }

    editing?.let { index ->
        TimeOfDayDialog(
            initial = choices.times.getOrNull(index),
            others = choices.times.filterIndexed { i, _ -> i != index },
            onSave = { saved ->
                val next = if (index < 0) choices.times + saved else choices.times.mapIndexed { i, t -> if (i == index) saved else t }
                onTimes(next)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** Every day choice: the ones in use first, in their order (drag to reorder), then the rest. */
@Composable
private fun DayChoiceList(days: List<DayChoiceId>, onDays: (List<DayChoiceId>) -> Unit) {
    val all = days + DayChoiceId.entries.filter { it !in days }
    var live by remember(all) { mutableStateOf(all) }
    val haptic = LocalHapticFeedback.current
    val arrange = { next: List<DayChoiceId> ->
        if (next != live) {
            live = next
            onDays(next.filter { it in days })
        }
    }
    fun moved(from: Int, to: Int) = live.toMutableList().apply { add(to, removeAt(from)) }
    ReorderableColumn(
        list = live,
        onSettle = { from, to -> arrange(moved(from, to)) },
        onMove = { haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) },
    ) { index, choice, isDragging ->
        key(choice) {
            val on = choice in days
            val moveLabel = stringResource(Res.string.when_choices_move_choice, choice.label)
            val full = !on && days.size >= WhenChoices.MAX_DAYS
            Surface(
                color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                shadowElevation = if (isDragging) 4.dp else 0.dp,
            ) {
                Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.draggableHandle()
                            .size(48.dp)
                            .semantics { contentDescription = moveLabel }
                            .moveCustomActions(
                                if (index > 0) ({ arrange(moved(index, index - 1)) }) else null,
                                if (index < live.lastIndex) ({ arrange(moved(index, index + 1)) }) else null,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SwitchLine(
                        label = choice.label,
                        checked = on,
                        enabled = !full,
                        onChange = { checked ->
                            // A choice turned on joins at the end of those in use.
                            onDays(if (checked) days + choice else days - choice)
                        },
                        modifier = Modifier.weight(1f),
                        startPadding = 0,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchLine(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    startPadding: Int = 16,
) {
    Row(
        modifier = modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(start = startPadding.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Adds or edits a time of day: its name and its time, kept to the rules of section 23.1. */
@Composable
private fun TimeOfDayDialog(
    initial: TimeOfDay?,
    others: List<TimeOfDay>,
    onSave: (TimeOfDay) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var minutes by rememberSaveable { mutableStateOf((initial?.time ?: LocalTime(9, 0)).let { it.hour * 60 + it.minute }) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    val time = LocalTime(minutes / 60, minutes % 60)
    val trimmed = name.trim().replace(Regex("\\s+"), " ")
    val problem = when {
        trimmed.isEmpty() -> null
        !WhenChoices.isValidName(trimmed) -> "Use letters and numbers, up to ${WhenChoices.MAX_NAME_LENGTH}"
        others.any { it.name.equals(trimmed, ignoreCase = true) } -> "There is a time with this name"
        others.any { it.time == time } -> "There is a time at ${WhenLogic.timeLabel(time, LocalDateFormat.current)}"
        else -> null
    }
    val canSave = trimmed.isNotEmpty() && problem == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add a time" else "Edit time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.when_choices_time_name)) },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = if (problem != null) {
                        { Text(problem) }
                    } else {
                        null
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = stringResource(Res.string.when_choices_change_time)) { pickingTime = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.when_choices_time_label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        WhenLogic.timeLabel(time, LocalDateFormat.current),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(TimeOfDay(trimmed, time)) }, enabled = canSave) { Text(stringResource(Res.string.when_choices_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.when_choices_cancel)) } },
    )
    if (pickingTime) {
        CustomTimeDialog(
            initial = time,
            onPick = { picked ->
                minutes = picked.hour * 60 + picked.minute
                pickingTime = false
            },
            onDismiss = { pickingTime = false },
        )
    }
}

/**
 * The When choices over a picker (the When tray's Edit choices), full screen; what is under it
 * waits. It writes the same synced settings as Settings, Input.
 */
@Composable
internal fun WhenChoicesDialog(onDismiss: () -> Unit) {
    val preferences = koinInject<WhenChoicesPreferences>()
    val scope = rememberCoroutineScope()
    val choices by preferences.choices.collectAsState(initial = null)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().padding(WindowInsets.systemBars.asPaddingValues())) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.when_choices_back))
                    }
                    Text(
                        "When choices",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp).semantics { heading() },
                    )
                }
                val current = choices ?: return@Column
                WhenChoicesEditor(
                    choices = current,
                    onTimes = { scope.launch { preferences.setTimes(it) } },
                    onDays = { scope.launch { preferences.setDays(it) } },
                    onMoveBy = { scope.launch { preferences.setMoveBy(it) } },
                    onReset = { scope.launch { preferences.reset() } },
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
