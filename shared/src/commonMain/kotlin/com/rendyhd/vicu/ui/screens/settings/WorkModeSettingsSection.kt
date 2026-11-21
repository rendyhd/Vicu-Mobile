package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.data.local.BehaviorPrefs
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.repository.WorkModeSource
import com.rendyhd.vicu.data.repository.WorkSettings
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.resources.Res
import com.rendyhd.vicu.resources.work_settings_label
import com.rendyhd.vicu.resources.work_settings_label_description
import com.rendyhd.vicu.resources.work_settings_label_none
import com.rendyhd.vicu.resources.work_settings_no_label_note
import com.rendyhd.vicu.resources.work_settings_pro_only
import com.rendyhd.vicu.resources.work_settings_pro_only_description
import com.rendyhd.vicu.resources.work_settings_projects_note
import com.rendyhd.vicu.resources.work_settings_switch
import com.rendyhd.vicu.resources.work_settings_switch_description
import com.rendyhd.vicu.resources.work_settings_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Whether Work and Personal is on, which projects are work, and the write that marks one (contract 22.7). */
internal class WorkMarking(
    val enabled: Boolean,
    val projects: Set<Long>,
    private val source: WorkModeSource?,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    fun setProject(projectId: Long, want: Boolean, all: List<com.rendyhd.vicu.domain.model.Project>) {
        scope.launch { source?.setProjectWork(projectId, want, all) }
    }
}

/** Read once for a list of projects: collected here, not once per row. */
@Composable
internal fun rememberWorkMarking(): WorkMarking {
    val source = koinInject<WorkModeSource>()
    val settings by source.settings.collectAsStateWithLifecycle(WorkSettings())
    val scope = rememberCoroutineScope()
    // The scope is only read while the feature is on.
    if (!settings.enabled) return WorkMarking(false, emptySet(), source, scope)
    val work by source.scope.collectAsStateWithLifecycle(com.rendyhd.vicu.util.WorkScope.NONE)
    return WorkMarking(true, work.projects, source, scope)
}

/**
 * Settings, General, Work and Personal (contract section 22): the switch for this device, the work
 * label (synced), "Pro only in Work" while Pro is on, and a note about what happens without a
 * label. The projects are marked in their menus.
 */
internal fun LazyListScope.workModeSection() {
    item(key = "work_header") { SubsectionHeading(stringResource(Res.string.work_settings_title)) }
    item(key = "work_settings") { WorkModeSettings() }
}

@Composable
private fun WorkModeSettings() {
    val source = koinInject<WorkModeSource>()
    val behavior by koinInject<BehaviorPrefsStore>().getPrefs().collectAsStateWithLifecycle(BehaviorPrefs())
    val settings = WorkSettings.of(behavior)
    val scope = rememberCoroutineScope()
    Column {
        SwitchRow(
            label = stringResource(Res.string.work_settings_switch),
            description = stringResource(Res.string.work_settings_switch_description),
            checked = settings.enabled,
            onCheckedChange = { on -> scope.launch { source.setEnabled(on) } },
        )
        if (!settings.enabled) return@Column
        WorkLabelRow(source)
        if (behavior.isProSwitchedOn()) {
            SwitchRow(
                label = stringResource(Res.string.work_settings_pro_only),
                description = stringResource(Res.string.work_settings_pro_only_description),
                checked = settings.proOnlyInWork,
                onCheckedChange = { on -> scope.launch { source.setProOnlyInWork(on) } },
            )
        }
        Text(
            stringResource(Res.string.work_settings_projects_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/** The work label: any label, or none. A label that is gone counts as none (22.1), so the row says None. */
@Composable
private fun WorkLabelRow(source: WorkModeSource) {
    val scope = rememberCoroutineScope()
    val workScope by source.scope.collectAsStateWithLifecycle(com.rendyhd.vicu.util.WorkScope.NONE)
    val labels by koinInject<LabelRepository>().getAll().collectAsStateWithLifecycle(emptyList())
    var open by remember { mutableStateOf(false) }
    val current = labels.firstOrNull { it.id == workScope.label }
    Column {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.work_settings_label), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(Res.string.work_settings_label_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    current?.title ?: stringResource(Res.string.work_settings_label_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.work_settings_label_none)) },
                    onClick = {
                        open = false
                        scope.launch { source.setWorkLabel(null) }
                    },
                )
                labels.sortedBy { it.title.lowercase() }.forEach { label ->
                    DropdownMenuItem(
                        text = { Text(label.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            open = false
                            scope.launch { source.setWorkLabel(label.id) }
                        },
                    )
                }
            }
        }
        if (current == null) {
            Text(
                stringResource(Res.string.work_settings_no_label_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}
