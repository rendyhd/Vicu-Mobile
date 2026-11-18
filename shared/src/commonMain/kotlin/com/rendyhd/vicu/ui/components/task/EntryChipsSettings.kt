package com.rendyhd.vicu.ui.components.task

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
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.util.EntryChip
import com.rendyhd.vicu.util.EntryChipLayout
import com.rendyhd.vicu.util.EntryChipPreset
import com.rendyhd.vicu.util.EntryChips
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableColumn

/** Under the presets: what pinning a chip means. */
internal const val ENTRY_CHIPS_INTRO =
    "Choose which chips are always on the quick-add row. The others show once they hold something, " +
        "and under Details."

internal fun entryChipName(chip: EntryChip): String = when (chip) {
    EntryChip.WHEN -> "When"
    EntryChip.REMIND -> "Remind"
    EntryChip.PRIORITY -> "Priority"
    EntryChip.LABELS -> "Labels"
    EntryChip.PROJECT -> "Project"
    EntryChip.REPEAT -> "Repeat"
    EntryChip.TEMPLATE -> "Template"
}

internal fun entryChipIcon(chip: EntryChip): ImageVector = when (chip) {
    EntryChip.WHEN -> Icons.Outlined.CalendarMonth
    EntryChip.REMIND -> Icons.Outlined.Notifications
    EntryChip.PRIORITY -> Icons.Outlined.Flag
    EntryChip.LABELS -> Icons.AutoMirrored.Outlined.Label
    EntryChip.PROJECT -> Icons.Outlined.Folder
    EntryChip.REPEAT -> Icons.Outlined.Repeat
    EntryChip.TEMPLATE -> Icons.Outlined.Description
}

private fun presetName(preset: EntryChipPreset): String = when (preset) {
    EntryChipPreset.MINIMAL -> "Minimal"
    EntryChipPreset.STANDARD -> "Standard"
    EntryChipPreset.EVERYTHING -> "Everything"
}

/**
 * The quick-add chip setting (Settings, Input, and Customize on the sheet): the presets, then every
 * chip with a handle to drag it into place and a switch that pins it to the row. A switch changed
 * by hand makes the choice custom, and no preset shows as selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryChipsEditor(
    layout: EntryChipLayout,
    onPinned: (Set<EntryChip>) -> Unit,
    onOrder: (List<EntryChip>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = ENTRY_CHIPS_INTRO,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val presets = EntryChipPreset.entries
            presets.forEachIndexed { index, preset ->
                SegmentedButton(
                    selected = layout.preset == preset,
                    onClick = { onPinned(EntryChips.pinnedOf(preset)) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = presets.size),
                ) { Text(presetName(preset)) }
            }
        }
        if (layout.preset == null) {
            Text(
                text = "Your own choice",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // The order on show: the dropped one at once, until the stored order says the same.
        var live by remember(layout.order) { mutableStateOf(layout.order) }
        val haptic = LocalHapticFeedback.current
        val arrange = { next: List<EntryChip> ->
            if (next != live) {
                live = next
                onOrder(next)
            }
        }
        ReorderableColumn(
            list = live,
            onSettle = { from, to -> arrange(EntryChips.moved(live, from, to)) },
            // A reorder step (design-system-v1, haptics).
            onMove = { haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) },
        ) { index, chip, isDragging ->
            key(chip) {
                EntryChipRow(
                    chip = chip,
                    pinned = chip in layout.pinned,
                    dragging = isDragging,
                    handle = Modifier.draggableHandle(),
                    onPinnedChange = { on -> onPinned(if (on) layout.pinned + chip else layout.pinned - chip) },
                    // The drag has no equivalent for a screen reader: the same move, one place at a time.
                    onMoveUp = if (index > 0) ({ arrange(EntryChips.moved(live, index, index - 1)) }) else null,
                    onMoveDown = if (index < live.lastIndex) ({ arrange(EntryChips.moved(live, index, index + 1)) }) else null,
                )
            }
        }
    }
}

@Composable
private fun EntryChipRow(
    chip: EntryChip,
    pinned: Boolean,
    dragging: Boolean,
    handle: Modifier,
    onPinnedChange: (Boolean) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val name = entryChipName(chip)
    Surface(
        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        shadowElevation = if (dragging) 4.dp else 0.dp,
    ) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = handle
                    .size(48.dp)
                    .semantics { contentDescription = "Move $name" }
                    .moveCustomActions(onMoveUp, onMoveDown),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                // The row is the switch, so a screen reader finds one control.
                modifier = Modifier
                    .weight(1f)
                    .toggleable(value = pinned, role = Role.Switch, onValueChange = onPinnedChange)
                    .heightIn(min = 56.dp)
                    .padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = entryChipIcon(chip),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    if (chip == EntryChip.TEMPLATE) {
                        Text(
                            "Only while you have a template",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(checked = pinned, onCheckedChange = null)
            }
        }
    }
}

/** "Open at full height": the sheet starts with every field instead of the compact row. */
@Composable
internal fun EntryOpenFullRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Open at full height", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Start with every field instead of the compact sheet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * The chip setting over the quick-add sheet (its Customize chip), full screen; the draft under it
 * is kept. It writes the same device setting as Settings, Input.
 */
@Composable
internal fun EntryChipsDialog(onDismiss: () -> Unit) {
    val store = koinInject<BehaviorPrefsStore>()
    val scope = rememberCoroutineScope()
    val prefs by store.getPrefs().collectAsState(initial = null)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().padding(WindowInsets.systemBars.asPaddingValues())) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        "Quick add chips",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp).semantics { heading() },
                    )
                }
                val current = prefs ?: return@Column
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    EntryChipsEditor(
                        layout = current.entryChips,
                        onPinned = { pinned -> scope.launch { store.setEntryChipsPinned(pinned) } },
                        onOrder = { order -> scope.launch { store.setEntryChipOrder(order) } },
                    )
                    EntryOpenFullRow(
                        checked = current.entryChips.openFull,
                        onChange = { on -> scope.launch { store.setEntryOpenFull(on) } },
                    )
                }
            }
        }
    }
}
