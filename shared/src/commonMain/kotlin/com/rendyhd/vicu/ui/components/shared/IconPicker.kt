package com.rendyhd.vicu.ui.components.shared

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The icons of [IconRegistry.PRESET_ICONS] as a grid, each a 48 dp radio target with the icon's name.
 * [selectedKey] that is none of them (from a newer version) selects nothing and is kept until one is
 * tapped. The chosen icon is drawn in [selectedTint], the primary colour unless given.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPicker(
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedTint: Color? = null,
) {
    FlowRow(modifier = modifier.selectableGroup()) {
        IconRegistry.PRESET_ICONS.forEach { option ->
            IconTile(
                option = option,
                selected = option.key == selectedKey,
                selectedTint = selectedTint ?: MaterialTheme.colorScheme.primary,
                onClick = { onSelect(option.key) },
            )
        }
    }
}

@Composable
private fun IconTile(option: IconOption, selected: Boolean, selectedTint: Color, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = Modifier
            .size(48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = option.label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) scheme.primary else scheme.outlineVariant,
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                option.icon,
                // The tile carries the name.
                contentDescription = null,
                tint = if (selected) selectedTint else scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
