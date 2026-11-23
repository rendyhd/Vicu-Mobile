package com.rendyhd.vicu.ui.screens.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.resources.Res
import com.rendyhd.vicu.resources.project_label_bar
import com.rendyhd.vicu.resources.project_label_choice
import com.rendyhd.vicu.resources.project_label_clear
import com.rendyhd.vicu.resources.project_label_filter
import com.rendyhd.vicu.resources.project_label_filter_picked
import com.rendyhd.vicu.resources.project_label_more
import com.rendyhd.vicu.resources.project_label_show_all
import com.rendyhd.vicu.ui.components.task.labelChipColors
import com.rendyhd.vicu.util.LabelChoice
import com.rendyhd.vicu.util.parseHexArgb
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The filter button next to a project's options (contract 17.2). It opens the bar of labels; while
 * labels are picked it takes the accent colour and carries their number, also with the bar closed.
 */
@Composable
internal fun LabelFilterButton(picked: Int, open: Boolean, onClick: () -> Unit) {
    val name = if (picked > 0) {
        pluralStringResource(Res.plurals.project_label_filter_picked, picked, picked)
    } else {
        stringResource(Res.string.project_label_filter)
    }
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (picked > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            containerColor = if (open) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        ),
        modifier = Modifier.semantics { contentDescription = name },
    ) {
        BadgedBox(badge = { if (picked > 0) Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Text(picked.toString()) } }) {
            Icon(Icons.Outlined.FilterList, contentDescription = null)
        }
    }
}

/** The bar under the top bar: one chip per label with its count, and Clear once something is picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LabelFilterBar(
    choices: List<LabelChoice>,
    picked: Set<Long>,
    onToggle: (Label) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(Res.string.project_label_bar)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
                .semantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            choices.forEach { (label, count) ->
                val on = label.id in picked
                val rgb = remember(label.hexColor) { parseHexArgb(label.hexColor) }
                val chip = remember(rgb, isDark) { rgb?.let { labelChipColors(it, isDark) } }
                val dot = rgb?.let { Color(0xFF000000.toInt() or (it and 0xFFFFFF)) } ?: MaterialTheme.colorScheme.outline
                val choiceName = pluralStringResource(Res.plurals.project_label_choice, count, label.title, count)
                FilterChip(
                    selected = on,
                    onClick = { onToggle(label) },
                    modifier = Modifier.semantics { contentDescription = choiceName },
                    leadingIcon = { Box(Modifier.size(8.dp).background(dot, CircleShape)) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(label.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                            Text(count.toString(), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    colors = if (chip != null) {
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = chip.fill,
                            selectedLabelColor = chip.text,
                        )
                    } else {
                        FilterChipDefaults.filterChipColors()
                    },
                    shape = CircleShape,
                )
            }
            if (picked.isNotEmpty()) {
                TextButton(onClick = onClear) { Text(stringResource(Res.string.project_label_clear)) }
            }
        }
        HorizontalDivider()
    }
}

/** "3 more tasks in this project" with Show all, under the list while labels are picked. */
internal fun LazyListScope.labelHiddenItem(count: Int, onShowAll: () -> Unit) {
    if (count <= 0) return
    item(key = "label_hidden", contentType = "label_hidden") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(Res.plurals.project_label_more, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onShowAll) { Text(stringResource(Res.string.project_label_show_all)) }
        }
    }
}
