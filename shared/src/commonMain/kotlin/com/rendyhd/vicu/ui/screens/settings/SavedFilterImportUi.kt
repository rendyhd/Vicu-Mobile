package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.util.SavedFilterImport
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import com.rendyhd.vicu.data.sync.SavedFilterImporter
import com.rendyhd.vicu.util.SavedFilterImport.Change

/**
 * The notice for saved filters that were never imported, and for imported ones that changed in
 * Vikunja (cross-app semantics 3.7).
 */
@Composable
internal fun SavedFilterOffer(viewModel: SavedFilterImportViewModel) {
    val offer by viewModel.offer.collectAsStateWithLifecycle()
    val changed by viewModel.changedOffer.collectAsStateWithLifecycle()
    if (offer.isEmpty() && changed.isEmpty()) return
    val (title, body) = offerText(offer.size, changed.size)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(end = 8.dp)) {
                Icon(
                    Icons.Outlined.FilterList,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::notNow) { Text("Not now") }
                TextButton(onClick = viewModel::open) { Text("Review") }
            }
        }
    }
}

/** The offer card's title and words for [new] new and [changed] changed saved filters. */
internal fun offerText(new: Int, changed: Int): Pair<String, String> = when {
    changed == 0 -> (if (new == 1) "1 new saved filter on your server" else "$new new saved filters on your server") to
        (if (new == 1) "Import it as a custom list to use it in Vicu." else "Import them as custom lists to use them in Vicu.")
    new == 0 -> (if (changed == 1) "1 saved filter changed in Vikunja" else "$changed saved filters changed in Vikunja") to
        "See what changed and choose whether to update your lists."
    else -> "$new new and $changed changed saved filters on your server" to "Review them to import or update your lists."
}

/** The row under the lists that imports saved filters and project views. */
@Composable
internal fun ImportSavedFiltersRow(viewModel: SavedFilterImportViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Import saved filters and views", role = Role.Button, onClick = viewModel::open)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Outlined.FilterList, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Import saved filters and views", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The review sheet (each saved filter and project view, what importing it does, and Import) and its questions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedFilterSheetHost(viewModel: SavedFilterImportViewModel) {
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val question by viewModel.question.collectAsStateWithLifecycle()
    question?.let { UpdateQuestionDialog(it, viewModel) }
    val current = sheet ?: return
    ModalBottomSheet(
        onDismissRequest = viewModel::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                "Import saved filters and views",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                "Each becomes a custom list in Vicu. Changes you make to these lists won't change anything on your server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
            )
            when {
                current.loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
                current.error != null -> Text(
                    current.error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    if (current.rows.isNotEmpty()) {
                        item(key = "filters-header") { SheetHeader("Saved filters") }
                        items(current.rows, key = { it.preview.key }) { row -> SavedFilterSheetRow(row, viewModel) }
                    }
                    item(key = "views-header") { SheetHeader("Project views") }
                    when {
                        current.viewsLoading -> item(key = "views-loading") {
                            Text(
                                "Reading the project views...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                        current.viewsError != null -> item(key = "views-error") {
                            Text(current.viewsError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                        }
                        current.views.isEmpty() -> item(key = "views-none") {
                            Text(
                                "No project has a view of its own yet. A view with its own filter or sort shows up here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                        else -> items(current.views, key = { it.preview.key }) { row -> SavedFilterSheetRow(row, viewModel) }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = viewModel::close) { Text("Cancel") }
                Button(onClick = viewModel::importChecked, enabled = current.checkedCount > 0) {
                    Text(if (current.checkedCount == 0) "Import" else "Import ${current.checkedCount}")
                }
            }
        }
    }
}

@Composable
private fun SavedFilterSheetRow(row: SavedFilterRow, viewModel: SavedFilterImportViewModel) {
    val result = row.preview.result
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = row.checked,
                enabled = row.importable,
                role = Role.Checkbox,
                onValueChange = { viewModel.toggle(row.preview.key) },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = row.checked, onCheckedChange = null, enabled = row.importable, modifier = Modifier.padding(12.dp))
        Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
            Text(row.preview.title, style = MaterialTheme.typography.bodyLarge)
            if (row.rule.isNotBlank()) {
                Text(
                    row.rule,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                importStatusText(row),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    result.list == null -> MaterialTheme.colorScheme.error
                    row.preview.comparison != null -> when (row.preview.comparison.status) {
                        Change.VIKUNJA, Change.BOTH, Change.DIFFERS -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    row.preview.importedBefore && !row.checked -> MaterialTheme.colorScheme.onSurfaceVariant
                    result.status == SavedFilterImport.Status.EXACT -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SheetHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().semantics { heading() }.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * Overwrite or Skip for one source imported before (cross-app semantics 3.7): each part that
 * differs, who changed it, Vikunja's value and the list's.
 */
@Composable
private fun UpdateQuestionDialog(question: UpdateQuestion, viewModel: SavedFilterImportViewModel) {
    var forAll by remember(question.preview.key) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = viewModel::stopAsking,
        title = { Text("Update \"${question.listName}\"?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(questionText(question), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                question.lines.forEachIndexed { index, line ->
                    if (index > 0) HorizontalDivider()
                    PartLineView(line)
                }
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Kept either way: the icon, its place, widgets and the list's other conditions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (question.remaining > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(value = forAll, role = Role.Checkbox, onValueChange = { forAll = it }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = forAll, onCheckedChange = null)
                        Text(
                            if (question.remaining == 1) "Same for the other one" else "Same for the other ${question.remaining}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.answer(SavedFilterImporter.Answer.OVERWRITE, forAll) }) { Text("Overwrite") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.answer(SavedFilterImporter.Answer.SKIP, forAll) }) { Text("Skip") }
        },
    )
}

@Composable
private fun PartLineView(line: PartLine) {
    val mono = line.part == SavedFilterImport.Part.RULE
    val valueStyle = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(line.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                changeText(line.change),
                style = MaterialTheme.typography.labelMedium,
                color = if (line.change == Change.VICU) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
            )
        }
        if (line.change == Change.VIKUNJA) {
            Text(
                line.yours,
                style = valueStyle.copy(textDecoration = TextDecoration.LineThrough),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Was: ${line.yours}" },
            )
            Text(line.vikunja, style = valueStyle, modifier = Modifier.semantics { contentDescription = "Now: ${line.vikunja}" })
        } else {
            Text("Vikunja: ${line.vikunja}", style = valueStyle)
            Text("Yours: ${line.yours}", style = valueStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
