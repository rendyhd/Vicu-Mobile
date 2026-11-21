package com.rendyhd.vicu.i18n

import com.rendyhd.vicu.resources.Res
import com.rendyhd.vicu.resources.pro_text_absent
import com.rendyhd.vicu.resources.pro_text_action_delivered
import com.rendyhd.vicu.resources.pro_text_action_delivered_late
import com.rendyhd.vicu.resources.pro_text_action_dropped
import com.rendyhd.vicu.resources.pro_text_action_for_person
import com.rendyhd.vicu.resources.pro_text_action_for_person_no_date
import com.rendyhd.vicu.resources.pro_text_action_new_date
import com.rendyhd.vicu.resources.pro_text_action_new_date_blocker
import com.rendyhd.vicu.resources.pro_text_action_no_owner
import com.rendyhd.vicu.resources.pro_text_action_no_owner_no_date
import com.rendyhd.vicu.resources.pro_text_action_not_yet
import com.rendyhd.vicu.resources.pro_text_action_not_yet_blocker
import com.rendyhd.vicu.resources.pro_text_action_owner
import com.rendyhd.vicu.resources.pro_text_action_owner_no_date
import com.rendyhd.vicu.resources.pro_text_action_taken_over
import com.rendyhd.vicu.resources.pro_text_agenda_close
import com.rendyhd.vicu.resources.pro_text_agenda_title_standup
import com.rendyhd.vicu.resources.pro_text_agenda_title_weekly
import com.rendyhd.vicu.resources.pro_text_check_close
import com.rendyhd.vicu.resources.pro_text_check_intro
import com.rendyhd.vicu.resources.pro_text_check_item
import com.rendyhd.vicu.resources.pro_text_check_item_no_date
import com.rendyhd.vicu.resources.pro_text_check_one
import com.rendyhd.vicu.resources.pro_text_check_one_no_date
import com.rendyhd.vicu.resources.pro_text_decision_by
import com.rendyhd.vicu.resources.pro_text_decision_plain
import com.rendyhd.vicu.resources.pro_text_heading_actions_due
import com.rendyhd.vicu.resources.pro_text_heading_actions_open
import com.rendyhd.vicu.resources.pro_text_heading_blockers
import com.rendyhd.vicu.resources.pro_text_heading_blockers_raised
import com.rendyhd.vicu.resources.pro_text_heading_changed
import com.rendyhd.vicu.resources.pro_text_heading_commitments
import com.rendyhd.vicu.resources.pro_text_heading_decisions
import com.rendyhd.vicu.resources.pro_text_heading_delivered
import com.rendyhd.vicu.resources.pro_text_heading_issues
import com.rendyhd.vicu.resources.pro_text_heading_issues_solved
import com.rendyhd.vicu.resources.pro_text_heading_new_actions
import com.rendyhd.vicu.resources.pro_text_heading_questions
import com.rendyhd.vicu.resources.pro_text_heading_stops
import com.rendyhd.vicu.resources.pro_text_note_blocker
import com.rendyhd.vicu.resources.pro_text_note_decision
import com.rendyhd.vicu.resources.pro_text_note_dropped
import com.rendyhd.vicu.resources.pro_text_note_issue
import com.rendyhd.vicu.resources.pro_text_note_late
import com.rendyhd.vicu.resources.pro_text_note_next_week
import com.rendyhd.vicu.resources.pro_text_note_no_groups
import com.rendyhd.vicu.resources.pro_text_note_nothing
import com.rendyhd.vicu.resources.pro_text_note_on_time
import com.rendyhd.vicu.resources.pro_text_note_past_due
import com.rendyhd.vicu.resources.pro_text_note_slipped_new_date
import com.rendyhd.vicu.resources.pro_text_note_slipped_new_date_blocker
import com.rendyhd.vicu.resources.pro_text_note_slipped_not_yet
import com.rendyhd.vicu.resources.pro_text_note_slipped_not_yet_blocker
import com.rendyhd.vicu.resources.pro_text_note_taken_over
import com.rendyhd.vicu.resources.pro_text_note_title
import com.rendyhd.vicu.resources.pro_text_readback_nothing
import com.rendyhd.vicu.resources.pro_text_readback_title
import com.rendyhd.vicu.resources.pro_text_recap_nothing
import com.rendyhd.vicu.resources.pro_text_recap_title_standup
import com.rendyhd.vicu.resources.pro_text_recap_title_weekly
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.pro.DocBlock
import com.rendyhd.vicu.util.pro.DocLine
import com.rendyhd.vicu.util.pro.DocParam
import com.rendyhd.vicu.util.pro.Documents
import com.rendyhd.vicu.util.pro.ProDocument
import com.rendyhd.vicu.util.pro.RenderedDocument
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * One sentence of text for other people (docs/cross-app-semantics-v1.md section 21.19): the key a
 * document line names (`action.owner`), its parameters in the order of the catalog's `%1$s`,
 * `%2$s` ([params]), and the string or plural that holds it (`pro_text_action_owner`). A plural
 * sentence is listed once, without its `_one` / `_other` ending; its `count` is a `%n$d`.
 */
internal class ProTextEntry(
    val key: String,
    val params: List<String>,
    val string: StringResource? = null,
    val plural: PluralStringResource? = null,
) {
    /** The resource's name in `strings.xml`. */
    val name: String get() = string?.key ?: plural?.key.orEmpty()
}

/**
 * Text Vicu Pro writes for other people: every key the document builders of `util/pro/Documents.kt`
 * can name, mapped to its string resource (the desktop's `PRO_TEXT_KEYS`). Written out in full so
 * the compiler checks each name; `ProTextCatalogTest` checks that the English equals the contract's
 * messages and that nothing a builder writes is missing.
 */
internal object ProText {
    val ENTRIES: Map<String, ProTextEntry> = listOf(
        ProTextEntry("agenda.title.weekly", listOf("group", "date"), string = Res.string.pro_text_agenda_title_weekly),
        ProTextEntry("agenda.title.standup", listOf("group", "date"), string = Res.string.pro_text_agenda_title_standup),
        ProTextEntry("agenda.close", listOf(), string = Res.string.pro_text_agenda_close),
        ProTextEntry("recap.title.weekly", listOf("group", "date"), string = Res.string.pro_text_recap_title_weekly),
        ProTextEntry("recap.title.standup", listOf("group", "date"), string = Res.string.pro_text_recap_title_standup),
        ProTextEntry("recap.nothing", listOf(), string = Res.string.pro_text_recap_nothing),
        ProTextEntry("readback.title", listOf("group", "date"), string = Res.string.pro_text_readback_title),
        ProTextEntry("readback.nothing", listOf(), string = Res.string.pro_text_readback_nothing),
        ProTextEntry("heading.actionsDue", listOf(), string = Res.string.pro_text_heading_actions_due),
        ProTextEntry("heading.actionsOpen", listOf(), string = Res.string.pro_text_heading_actions_open),
        ProTextEntry("heading.issues", listOf(), string = Res.string.pro_text_heading_issues),
        ProTextEntry("heading.decisions", listOf(), string = Res.string.pro_text_heading_decisions),
        ProTextEntry("heading.newActions", listOf(), string = Res.string.pro_text_heading_new_actions),
        ProTextEntry("heading.delivered", listOf(), string = Res.string.pro_text_heading_delivered),
        ProTextEntry("heading.changed", listOf(), string = Res.string.pro_text_heading_changed),
        ProTextEntry("heading.issuesSolved", listOf(), string = Res.string.pro_text_heading_issues_solved),
        ProTextEntry("heading.questions", listOf(), string = Res.string.pro_text_heading_questions),
        ProTextEntry("heading.blockers", listOf(), string = Res.string.pro_text_heading_blockers),
        ProTextEntry("heading.blockersRaised", listOf(), string = Res.string.pro_text_heading_blockers_raised),
        ProTextEntry("heading.commitments", listOf(), string = Res.string.pro_text_heading_commitments),
        ProTextEntry("heading.stops", listOf(), string = Res.string.pro_text_heading_stops),
        ProTextEntry("action.owner", listOf("owner", "title", "date"), string = Res.string.pro_text_action_owner),
        ProTextEntry("action.ownerNoDate", listOf("owner", "title"), string = Res.string.pro_text_action_owner_no_date),
        ProTextEntry("action.forPerson", listOf("owner", "requester", "title", "date"), string = Res.string.pro_text_action_for_person),
        ProTextEntry("action.forPersonNoDate", listOf("owner", "requester", "title"), string = Res.string.pro_text_action_for_person_no_date),
        ProTextEntry("action.noOwner", listOf("title", "date"), string = Res.string.pro_text_action_no_owner),
        ProTextEntry("action.noOwnerNoDate", listOf("title"), string = Res.string.pro_text_action_no_owner_no_date),
        ProTextEntry("action.delivered", listOf("owner", "title"), string = Res.string.pro_text_action_delivered),
        ProTextEntry("action.deliveredLate", listOf("owner", "title"), string = Res.string.pro_text_action_delivered_late),
        ProTextEntry("action.dropped", listOf("owner", "title"), string = Res.string.pro_text_action_dropped),
        ProTextEntry("action.takenOver", listOf("owner", "title", "me"), string = Res.string.pro_text_action_taken_over),
        ProTextEntry("action.newDate", listOf("owner", "title", "date"), string = Res.string.pro_text_action_new_date),
        ProTextEntry("action.newDateBlocker", listOf("owner", "title", "date", "blocker"), string = Res.string.pro_text_action_new_date_blocker),
        ProTextEntry("action.notYet", listOf("owner", "title"), string = Res.string.pro_text_action_not_yet),
        ProTextEntry("action.notYetBlocker", listOf("owner", "title", "blocker"), string = Res.string.pro_text_action_not_yet_blocker),
        ProTextEntry("decision.plain", listOf("text"), string = Res.string.pro_text_decision_plain),
        ProTextEntry("decision.by", listOf("text", "by"), string = Res.string.pro_text_decision_by),
        ProTextEntry("absent", listOf("names"), string = Res.string.pro_text_absent),
        ProTextEntry("check.one", listOf("name", "title", "date"), string = Res.string.pro_text_check_one),
        ProTextEntry("check.oneNoDate", listOf("name", "title"), string = Res.string.pro_text_check_one_no_date),
        ProTextEntry("check.intro", listOf("name", "count"), plural = Res.plurals.pro_text_check_intro),
        ProTextEntry("check.item", listOf("title", "date"), string = Res.string.pro_text_check_item),
        ProTextEntry("check.itemNoDate", listOf("title"), string = Res.string.pro_text_check_item_no_date),
        ProTextEntry("check.close", listOf(), string = Res.string.pro_text_check_close),
        ProTextEntry("note.title", listOf("from", "to"), string = Res.string.pro_text_note_title),
        ProTextEntry("note.onTime", listOf("count"), plural = Res.plurals.pro_text_note_on_time),
        ProTextEntry("note.late", listOf("count"), plural = Res.plurals.pro_text_note_late),
        ProTextEntry("note.dropped", listOf("count"), plural = Res.plurals.pro_text_note_dropped),
        ProTextEntry("note.takenOver", listOf("count", "me"), plural = Res.plurals.pro_text_note_taken_over),
        ProTextEntry("note.slipped.newDate", listOf("owner", "title", "date"), string = Res.string.pro_text_note_slipped_new_date),
        ProTextEntry("note.slipped.newDateBlocker", listOf("owner", "title", "date", "blocker"), string = Res.string.pro_text_note_slipped_new_date_blocker),
        ProTextEntry("note.slipped.notYet", listOf("owner", "title"), string = Res.string.pro_text_note_slipped_not_yet),
        ProTextEntry("note.slipped.notYetBlocker", listOf("owner", "title", "blocker"), string = Res.string.pro_text_note_slipped_not_yet_blocker),
        ProTextEntry("note.decision", listOf("text"), string = Res.string.pro_text_note_decision),
        ProTextEntry("note.issue", listOf("text"), string = Res.string.pro_text_note_issue),
        ProTextEntry("note.blocker", listOf("text"), string = Res.string.pro_text_note_blocker),
        ProTextEntry("note.pastDue", listOf("count"), plural = Res.plurals.pro_text_note_past_due),
        ProTextEntry("note.nextWeek", listOf("count"), plural = Res.plurals.pro_text_note_next_week),
        ProTextEntry("note.nothing", listOf(), string = Res.string.pro_text_note_nothing),
        ProTextEntry("note.noGroups", listOf(), string = Res.string.pro_text_note_no_groups),
    ).associateBy { it.key }

    private fun linesOf(document: ProDocument): List<DocLine> = buildList {
        document.title?.let(::add)
        for (block: DocBlock in document.blocks) {
            block.heading?.let(::add)
            block.text?.let(::add)
            addAll(block.items)
        }
    }

    /**
     * [document] written out (plain text, HTML and Markdown), its days relative to [today].
     *
     * The contract writes the text in the group's language ([language], from
     * `Documents.textLanguage`). Compose Multiplatform resources read a catalog only in the language
     * the app is shown in (`ResourceEnvironment` cannot be made for another one), so on the phone
     * the text is in the app's language, and its days in the app's date format on the 24-hour
     * clock; a language without a translation of these strings writes English. [language] is kept
     * so the call states what the text should be in. Reads the catalog, so it runs on a device only.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun render(document: ProDocument, language: String, app: DateDisplayFormat, today: LocalDate): RenderedDocument {
        val fmt = app.copy(hour12 = false)
        val formatDay = { day: LocalDate -> DateDisplay.formatWeekdayDate(day, today, fmt) }
        val written = HashMap<Pair<String, Map<String, Any>>, String>()
        for (line in linesOf(document)) {
            if (line.key == Documents.LITERAL_KEY) continue
            val params = LinkedHashMap<String, Any>()
            for ((name, value) in line.params) {
                params[name] = when (value) {
                    is DocParam.Text -> value.value
                    is DocParam.Number -> value.value
                    is DocParam.Day -> formatDay(value.day)
                }
            }
            val entry = ENTRIES[line.key] ?: continue
            val args = entry.params.map { params[it] ?: "" }.toTypedArray()
            val text = when {
                entry.plural != null -> {
                    val count = (params["count"] as? Long)?.toInt() ?: 0
                    getPluralString(entry.plural, count, *args)
                }
                entry.string != null -> getString(entry.string, *args)
                else -> continue
            }
            written[line.key to params] = text
        }
        // A key this version does not know (a document built by a newer rule) is shown as it is.
        return Documents.renderDocument(document, { key, params -> written[key to params] ?: key }, formatDay)
    }
}
