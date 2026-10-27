package com.rendyhd.vicu.ui.screens.ask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.util.AgendaItem
import com.rendyhd.vicu.util.Guided

/**
 * What a conversation without messages starts with (`docs/ai-assistant-v1.md`, section 5.7): the
 * agenda of what needs a decision (semantics 14.8), each opening its guided session, and the
 * starters for the list Ask was opened from, sent exactly as they read.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskAgenda(
    items: List<AgendaItem>,
    starters: List<String>,
    enabled: Boolean,
    onItem: (String) -> Unit,
    onStarter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        if (items.isNotEmpty()) {
            Text(
                "Needs a decision",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).semantics { heading() },
            )
            for (item in items) AgendaRow(item, onClick = { onItem(item.id) })
        }
        if (starters.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                for (text in starters) {
                    SuggestionChip(onClick = { onStarter(text) }, enabled = enabled, label = { Text(text) })
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(item: AgendaItem, onClick: () -> Unit) {
    val icon = when (item.id) {
        Guided.STEP_INBOX -> Icons.Outlined.Inbox
        Guided.STEP_OVERDUE -> Icons.Outlined.Schedule
        Guided.STEP_REVIEW -> Icons.Outlined.RateReview
        else -> Icons.Outlined.EventRepeat
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item.count?.let {
            Text(it.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
