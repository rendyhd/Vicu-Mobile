package com.rendyhd.vicu.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.local.SavedFilterRef
import com.rendyhd.vicu.data.sync.SavedFilterImporter
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.ui.components.shared.RuleEditing
import com.rendyhd.vicu.ui.components.shared.SORT_BY_OPTIONS
import com.rendyhd.vicu.ui.components.shared.orderOptions
import com.rendyhd.vicu.util.SavedFilterImport
import com.rendyhd.vicu.util.SavedFilterImport.Change
import com.rendyhd.vicu.util.SavedFilterImport.Part
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of the review sheet; [rule] is the source's rule with label and project names. */
data class SavedFilterRow(
    val preview: SavedFilterImporter.Preview,
    val checked: Boolean,
    val rule: String = preview.rule,
) {
    /** Whether the row can be ticked: readable, and not already the same as its list. */
    val importable: Boolean get() = preview.result.list != null && preview.comparison?.status != Change.SAME
}

/** The review sheet: the saved filters, the project views, or why they could not be read. */
data class SavedFilterSheet(
    val loading: Boolean = true,
    val rows: List<SavedFilterRow> = emptyList(),
    val error: String? = null,
    val viewsLoading: Boolean = true,
    val views: List<SavedFilterRow> = emptyList(),
    val viewsError: String? = null,
) {
    val checkedCount: Int get() = (rows + views).count { it.checked && it.importable }
}

/** One part of a list that differs from Vikunja's version, in words for the question. */
data class PartLine(val part: Part, val change: Change, val label: String, val vikunja: String, val yours: String)

/** Overwrite or Skip for one source that has a list (cross-app semantics 3.7). */
data class UpdateQuestion(
    val preview: SavedFilterImporter.Preview,
    val listName: String,
    val lines: List<PartLine>,
    /** How many questions come after this one. */
    val remaining: Int,
)

/**
 * Settings, Custom Lists: the offer of saved filters that are new or changed in Vikunja, the
 * "Import saved filters and views" row, the review sheet and the questions for sources imported
 * before (cross-app semantics 3.7 and 3.9).
 */
class SavedFilterImportViewModel(
    private val importer: SavedFilterImporter,
    private val labelRepository: LabelRepository,
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    /** Filters never imported and not put off on this device. */
    val offer: StateFlow<List<SavedFilterRef>> =
        importer.offer.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Imported filters changed in Vikunja and not answered or put off. */
    val changedOffer: StateFlow<List<SavedFilterRef>> =
        importer.changedOffer.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sheet = MutableStateFlow<SavedFilterSheet?>(null)
    val sheet: StateFlow<SavedFilterSheet?> = _sheet.asStateFlow()

    private val _question = MutableStateFlow<UpdateQuestion?>(null)
    val question: StateFlow<UpdateQuestion?> = _question.asStateFlow()

    /** The questions still to ask, the answers given, and the new lists of the same Import. */
    private var pending: List<SavedFilterImporter.Preview> = emptyList()
    private val answers = mutableListOf<Pair<SavedFilterImporter.Preview, SavedFilterImporter.Answer>>()
    private var newOnes: List<SavedFilterImporter.Preview> = emptyList()

    private var names: Pair<List<RuleEditing.Named>, List<RuleEditing.Named>> = emptyList<RuleEditing.Named>() to emptyList()

    /** Opens the sheet on every saved filter and project view; see [SavedFilterImport.startsTicked]. */
    fun open() {
        _sheet.value = SavedFilterSheet(loading = true)
        viewModelScope.launch {
            // Read from the store: nothing on screen keeps [known] collected.
            val filters = importer.known.first()
            // The rule as the list editor shows it: names instead of label and project ids.
            val labels = labelRepository.getAll().first().map { RuleEditing.Named(it.id, it.title) }
            val projects = projectRepository.getAll().first().map { RuleEditing.Named(it.id, it.title) }
            names = labels to projects
            launch {
                val views = importer.viewPreviews()
                _sheet.update { current ->
                    current?.copy(
                        viewsLoading = false,
                        views = views.orEmpty().map(::row),
                        viewsError = if (views == null) "Vicu couldn't read the project views." else null,
                    )
                }
            }
            val previews = importer.previews(filters)
            _sheet.update { current ->
                when {
                    current == null -> null
                    previews == null -> current.copy(loading = false, error = "Vicu couldn't read the saved filters. Check the connection and try again.")
                    else -> current.copy(loading = false, rows = previews.map(::row))
                }
            }
        }
    }

    private fun display(rule: String?): String {
        if (rule.isNullOrBlank()) return ""
        return RuleEditing.toDisplay(rule, names.first, names.second).ifBlank { rule }
    }

    private fun row(preview: SavedFilterImporter.Preview): SavedFilterRow {
        val status = preview.comparison?.status
        val ticked = when {
            preview.result.list == null -> false
            status != null -> SavedFilterImport.startsTicked(status)
            else -> !preview.importedBefore
        }
        return SavedFilterRow(preview, checked = ticked, rule = display(preview.rule))
    }

    fun toggle(key: String) {
        val flip = { row: SavedFilterRow -> if (row.preview.key == key && row.importable) row.copy(checked = !row.checked) else row }
        _sheet.update { sheet -> sheet?.copy(rows = sheet.rows.map(flip), views = sheet.views.map(flip)) }
    }

    fun close() {
        _sheet.value = null
    }

    /** Import: new sources at once, then a question for each ticked source that has a list. */
    fun importChecked() {
        val sheet = _sheet.value ?: return
        val ticked = (sheet.rows + sheet.views).filter { it.checked && it.importable }.map { it.preview }
        _sheet.value = null
        newOnes = ticked.filter { it.existing == null }
        pending = ticked.filter { it.existing != null }
        answers.clear()
        if (pending.isEmpty()) {
            viewModelScope.launch { importer.import(newOnes) }
            return
        }
        askNext()
    }

    private fun askNext() {
        val next = pending.firstOrNull()
        if (next == null) {
            _question.value = null
            finish()
            return
        }
        _question.value = UpdateQuestion(
            preview = next,
            listName = next.existing?.name.orEmpty(),
            lines = lines(next),
            remaining = pending.size - 1,
        )
    }

    /** Overwrite or Skip for the question on screen; with [forAll], for every remaining one too. */
    fun answer(answer: SavedFilterImporter.Answer, forAll: Boolean) {
        if (_question.value == null) return
        val now = if (forAll) pending else pending.take(1)
        now.forEach { answers += it to answer }
        pending = pending.drop(now.size)
        askNext()
    }

    /** The question dismissed: what was answered is written, the rest is left as it is. */
    fun stopAsking() {
        if (_question.value == null) return
        pending = emptyList()
        _question.value = null
        finish()
    }

    private fun finish() {
        val added = newOnes
        val given = answers.toList()
        newOnes = emptyList()
        answers.clear()
        viewModelScope.launch {
            if (given.isEmpty()) {
                importer.import(added)
            } else {
                importer.answer(given, added)
            }
        }
    }

    /** Not now on the offer: hidden on this device until another filter appears or changes. */
    fun notNow() {
        val filters = offer.value
        val changed = changedOffer.value
        viewModelScope.launch { importer.dismiss(filters, changed) }
    }

    private fun lines(preview: SavedFilterImporter.Preview): List<PartLine> {
        val list = preview.existing ?: return emptyList()
        val fresh = preview.fresh ?: return emptyList()
        val comparison = preview.comparison ?: return emptyList()
        return comparison.changed.map { part ->
            PartLine(
                part = part,
                change = comparison.parts.getValue(part),
                label = partLabel(part),
                vikunja = partText(fresh, part),
                yours = partText(list, part),
            )
        }
    }

    private fun partText(list: CustomList, part: Part): String = when (part) {
        Part.NAME -> list.name
        Part.RULE -> display(list.filter.ruleText).ifEmpty { "No rule" }
        Part.INCLUDE_NULLS -> if (list.filter.ruleIncludeNulls) "Included" else "Left out"
        Part.SORT -> sortText(list)
    }
}

internal fun partLabel(part: Part): String = when (part) {
    Part.NAME -> "Name"
    Part.RULE -> "Rule"
    Part.INCLUDE_NULLS -> "Tasks without a value"
    Part.SORT -> "Sort"
}

/** A list's sort in words: "Priority, highest first, then Due date, soonest first". */
internal fun sortText(list: CustomList): String {
    fun one(sortBy: String, orderBy: String): String {
        val name = SORT_BY_OPTIONS.firstOrNull { it.first == sortBy }?.second ?: sortBy
        val order = orderOptions(sortBy).firstOrNull { it.first == orderBy }?.second?.lowercase() ?: orderBy
        return "$name, $order"
    }
    val keys = listOf(one(list.filter.sortBy, list.filter.orderBy)) + list.filter.thenBy.map { one(it.sortBy, it.orderBy) }
    return keys.joinToString(", then ")
}

/** What an import will do, in words for the sheet. */
internal fun importStatusText(row: SavedFilterRow): String {
    val result = row.preview.result
    if (result.list == null) return "Can't import: Vicu can't read this yet"
    when (row.preview.comparison?.status) {
        Change.SAME -> return "Same as your list"
        Change.VIKUNJA -> return "Changed in Vikunja since you imported it"
        Change.BOTH -> return "Changed in Vikunja and in Vicu"
        Change.VICU -> return "Your list differs from Vikunja's"
        Change.DIFFERS -> return "Differs from Vikunja"
        null -> Unit
    }
    if (row.preview.importedBefore && !row.checked) return "Imported before"
    if (result.status == SavedFilterImport.Status.EXACT) return "Imports exactly"
    val parts = result.reasons.map {
        when (it) {
            SavedFilterImport.Reason.KANBAN_COLUMN -> "the kanban column"
            SavedFilterImport.Reason.OTHER_FIELD -> "a condition Vicu can't check"
            SavedFilterImport.Reason.MANUAL_ORDER -> "the manual order"
            SavedFilterImport.Reason.SORT_KEY -> "a sort order"
        }
    }
    val joined = if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    return "Leaves out $joined"
}

/** The words under the question's title. */
internal fun questionText(question: UpdateQuestion): String {
    val preview = question.preview
    val source = if (preview.isView) "The project view \"${preview.title}\"" else "The saved filter \"${preview.title}\""
    return when (preview.comparison?.status) {
        Change.VIKUNJA -> "$source changed in Vikunja since you imported it."
        Change.BOTH -> "$source changed in Vikunja, and the list changed in Vicu."
        Change.VICU -> "Your list differs from ${source.replaceFirstChar { it.lowercase() }}. Overwrite puts Vikunja's version back."
        else -> "Your list differs from ${source.replaceFirstChar { it.lowercase() }}. Vicu can't tell which side changed."
    }
}

/** Who changed a part, in words for its chip. */
internal fun changeText(change: Change): String = when (change) {
    Change.VIKUNJA -> "Changed in Vikunja"
    Change.VICU -> "Changed in Vicu"
    Change.BOTH -> "Changed in both"
    Change.DIFFERS -> "Differs"
    Change.SAME -> "Same"
}
