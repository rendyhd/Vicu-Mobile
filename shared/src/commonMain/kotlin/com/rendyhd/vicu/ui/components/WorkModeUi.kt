package com.rendyhd.vicu.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.resources.Res
import com.rendyhd.vicu.resources.work_hidden_hide
import com.rendyhd.vicu.resources.work_hidden_in_personal
import com.rendyhd.vicu.resources.work_hidden_in_work
import com.rendyhd.vicu.resources.work_hidden_show
import com.rendyhd.vicu.resources.work_mode_all
import com.rendyhd.vicu.resources.work_mode_control
import com.rendyhd.vicu.resources.work_mode_personal
import com.rendyhd.vicu.resources.work_mode_work
import com.rendyhd.vicu.util.WorkMode
import org.jetbrains.compose.resources.stringResource

/**
 * What a project or label screen says about the tasks the mode leaves out (contract 22.4): how
 * many, which mode they belong to, and whether they are shown for now. The choice lasts until the
 * screen is left.
 */
data class WorkHidden(
    val count: Int = 0,
    /** The mode the left-out tasks belong to; null when none are left out. */
    val other: WorkMode? = null,
    val showing: Boolean = false,
) {
    companion object {
        val NONE = WorkHidden()
    }
}

/** The name of a mode, as the control and the marks show it. */
@Composable
fun workModeName(mode: WorkMode): String = when (mode) {
    WorkMode.ALL -> stringResource(Res.string.work_mode_all)
    WorkMode.WORK -> stringResource(Res.string.work_mode_work)
    WorkMode.PERSONAL -> stringResource(Res.string.work_mode_personal)
}

/** All, Work and Personal at the top of the drawer; drawn only while the feature is on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkModeControl(mode: WorkMode, onMode: (WorkMode) -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.work_mode_control)
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth().semantics { contentDescription = description }) {
        val options = WorkMode.entries
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onMode(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(workModeName(option), maxLines = 1)
            }
        }
    }
}

/** A line under a list: "3 more in Personal", with Show (and Hide once they are shown). */
@Composable
fun WorkHiddenSummary(hidden: WorkHidden, onShowingChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val other = hidden.other ?: return
    if (hidden.count <= 0) return
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (other == WorkMode.PERSONAL) {
                stringResource(Res.string.work_hidden_in_personal, hidden.count)
            } else {
                stringResource(Res.string.work_hidden_in_work, hidden.count)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onShowingChange(!hidden.showing) }) {
            Text(stringResource(if (hidden.showing) Res.string.work_hidden_hide else Res.string.work_hidden_show))
        }
    }
}

/** The summary as the last item of a lazy list. */
fun LazyListScope.workHiddenItem(hidden: WorkHidden, onShowingChange: (Boolean) -> Unit) {
    if (hidden.other != null && hidden.count > 0) {
        item(key = "work_hidden", contentType = "work_hidden") { WorkHiddenSummary(hidden, onShowingChange) }
    }
}

/** The small word that says which mode a project or task of search belongs to. */
@Composable
fun WorkModeMark(work: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = workModeName(if (work) WorkMode.WORK else WorkMode.PERSONAL),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}
