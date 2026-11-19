package com.rendyhd.vicu.ui.components.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.util.ProjectNotifyMode
import com.rendyhd.vicu.util.parseHexColor

private val MODES = listOf(
    ProjectNotifyMode.ALL to "All",
    ProjectNotifyMode.MINE to "Mine",
    ProjectNotifyMode.OFF to "Off",
)

/** What a mode does, under the project's title. */
internal fun describe(mode: ProjectNotifyMode): String = when (mode) {
    ProjectNotifyMode.ALL -> "Every reminder"
    ProjectNotifyMode.MINE -> "Assigned to you or to nobody"
    ProjectNotifyMode.OFF -> "Silent on this device"
}

/**
 * The "Projects" part of the Notifications tab: for each project, whether this device rings for
 * all of its reminders, only for tasks that are yours or nobody's, or not at all. Applies to task
 * reminders, the daily summary and location reminders.
 */
@Composable
fun ProjectNotificationsSection(viewModel: ProjectNotificationsViewModel) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "Projects",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        Text(
            text = "Which projects remind you on this device. You can still add tasks to every project " +
                "and assign them to others. \"Mine\" keeps quiet about tasks assigned to someone else.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        if (rows.isEmpty()) {
            Text(
                text = "Your projects appear here after the first sync.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        rows.forEach { row ->
            ProjectNotifyRowItem(row, onMode = { viewModel.setMode(row.project.id, it) })
        }
    }
}

@Composable
private fun ProjectNotifyRowItem(row: ProjectNotifyRow, onMode: (ProjectNotifyMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (16 + row.depth * 20).dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = parseHexColor(row.project.hexColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.project.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = describe(row.mode) + if (row.chosen) "" else " (default)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        SingleChoiceSegmentedButtonRow {
            MODES.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = row.mode == mode,
                    onClick = { onMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, MODES.size),
                    icon = {},
                    modifier = Modifier.semantics { contentDescription = "${row.project.title}: $label, ${describe(mode)}" },
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
