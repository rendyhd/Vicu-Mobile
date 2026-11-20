package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.WeekStart
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonObject

/** A parameter of a document line: text, a number, or a day written out when the line is rendered. */
sealed class DocParam {
    data class Text(val value: String) : DocParam()
    data class Number(val value: Long) : DocParam()
    data class Day(val day: LocalDate) : DocParam()
}

/** A catalog key with its parameters. */
data class DocLine(val key: String, val params: Map<String, DocParam> = emptyMap())

data class DocBlock(
    val heading: DocLine? = null,
    /** A paragraph, before the items. */
    val text: DocLine? = null,
    val items: List<DocLine> = emptyList(),
)

data class ProDocument(val title: DocLine? = null, val blocks: List<DocBlock>)

data class RenderedDocument(val text: String, val html: String, val markdown: String)

/** Names for documents: a person by label id, and you. */
class DocNames(val person: (Long) -> String, val me: String)

/** One item of a check message. */
data class CheckItem(val title: String, val due: LocalDate?)

data class SessionRecap(
    val document: ProDocument,
    /** The promises in it, to mark as sent. */
    val taskIds: List<Long>,
    /** The decisions in it, to mark as shared. */
    val decisionIds: List<String>,
)

/**
 * The catalog: a key and its parameters (days already written out; a number stays a [Long] so a
 * plural form can be picked) to text.
 */
typealias DocTranslate = (key: String, params: Map<String, Any>) -> String

/**
 * Text for other people (docs/cross-app-semantics-v1.md section 21.19), the same as the desktop's
 * `src/shared/pro/documents.ts`: a session's agenda, read-back and recap, the check message and the
 * weekly note, as documents made of lines. A line is a catalog key with parameters, so the text can
 * be written in any language; names and titles are never translated. Rendering gives plain text,
 * HTML and Markdown.
 */
object Documents {
    const val LITERAL_KEY = "literal"

    private fun line(key: String, vararg params: Pair<String, DocParam>): DocLine = DocLine(key, linkedMapOf(*params))
    private fun literal(text: String): DocLine = line(LITERAL_KEY, "text" to DocParam.Text(text))
    private fun text(value: String) = DocParam.Text(value)
    private fun day(value: LocalDate) = DocParam.Day(value)

    // --- Rendering -------------------------------------------------------------------------

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** One line as text: `literal` is its `text`; days go through [formatDay] first. */
    fun renderLine(entry: DocLine, t: DocTranslate, formatDay: (LocalDate) -> String): String {
        val params = LinkedHashMap<String, Any>()
        for ((name, value) in entry.params) {
            params[name] = when (value) {
                is DocParam.Text -> value.value
                is DocParam.Number -> value.value
                is DocParam.Day -> formatDay(value.day)
            }
        }
        if (entry.key == LITERAL_KEY) return params["text"]?.toString().orEmpty()
        return t(entry.key, params)
    }

    /**
     * The three forms of a document. Plain text: the title, an empty line, then each block (heading,
     * paragraph, items as "- " lines) with an empty line between blocks. HTML: the title and
     * headings as bold paragraphs, items as a list. Markdown: `## ` title, `### ` headings, `- ` items.
     */
    fun renderDocument(doc: ProDocument, t: DocTranslate, formatDay: (LocalDate) -> String): RenderedDocument {
        fun write(entry: DocLine) = renderLine(entry, t, formatDay)
        val text = mutableListOf<String>()
        val html = StringBuilder()
        val markdown = mutableListOf<String>()
        doc.title?.let {
            val title = write(it)
            text.add(title)
            html.append("<p><strong>${escapeHtml(title)}</strong></p>")
            markdown.add("## $title")
        }
        for (block in doc.blocks) {
            val lines = mutableListOf<String>()
            val md = mutableListOf<String>()
            block.heading?.let {
                val heading = write(it)
                lines.add(heading)
                html.append("<p><strong>${escapeHtml(heading)}</strong></p>")
                md.add("### $heading")
                md.add("")
            }
            block.text?.let {
                val paragraph = write(it)
                lines.add(paragraph)
                html.append("<p>${escapeHtml(paragraph)}</p>")
                md.add(paragraph)
                if (block.items.isNotEmpty()) md.add("")
            }
            if (block.items.isNotEmpty()) {
                html.append("<ul>")
                for (item in block.items) {
                    val written = write(item)
                    lines.add("- $written")
                    html.append("<li>${escapeHtml(written)}</li>")
                    md.add("- $written")
                }
                html.append("</ul>")
            }
            if (lines.isEmpty()) continue
            if (text.isNotEmpty()) text.add("")
            text.addAll(lines)
            if (markdown.isNotEmpty()) markdown.add("")
            markdown.addAll(md)
        }
        return RenderedDocument(text.joinToString("\n"), html.toString(), markdown.joinToString("\n"))
    }

    private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z0-9_]+)\s*\}\}""")

    /**
     * A catalog as a flat table (`"note.onTime_one": "..."`): `{{name}}` placeholders, and with a
     * number `count` the `_one` form for 1 and `_other` otherwise. A key without a text is shown as
     * the key. The app's own catalog must give the same English.
     */
    fun messageFormatter(messages: Map<String, String>): DocTranslate = { key, params ->
        val count = params["count"]
        val plural = if (count is Long || count is Int) messages["${key}_${if (count.toString() == "1") "one" else "other"}"] else null
        val template = plural ?: messages[key]
        template?.replace(PLACEHOLDER) { match -> params[match.groupValues[1]]?.toString().orEmpty() } ?: key
    }

    /** The language a group's text is written in: its own, else the setting, else English. */
    fun textLanguage(groupLanguage: String?, settings: JsonObject?): String {
        if (!groupLanguage.isNullOrEmpty()) return groupLanguage
        val general = settings?.text("text_language")?.trim()
        return if (!general.isNullOrEmpty()) general else "en"
    }

    // --- Lines -----------------------------------------------------------------------------

    private fun ownerName(promise: JsonObject?, names: DocNames): String? {
        val owner = Groups.ownerOf(promise) ?: return null
        return if (owner == Groups.YOU) names.me else names.person(owner)
    }

    /** A person or anyone named in words; a group or you is not written out. */
    private fun requesterName(promise: JsonObject?, names: DocNames): String? = when (val requester = Promises.requesterOf(promise)) {
        is Requester.Person -> names.person(requester.person)
        is Requester.Text -> requester.text
        else -> null
    }

    /** An open action: owner, who it is for when that is a person, title and date. */
    fun actionLine(task: Task, promise: JsonObject?, names: DocNames, zone: TimeZone): DocLine {
        val owner = ownerName(promise, names)
        val requester = requesterName(promise, names)
        val due = Groups.actionDue(task, promise, zone)
        val title = text(task.title.trim())
        if (owner == null) {
            return if (due != null) line("action.noOwner", "title" to title, "date" to day(due)) else line("action.noOwnerNoDate", "title" to title)
        }
        if (requester != null) {
            return if (due != null) {
                line("action.forPerson", "owner" to text(owner), "requester" to text(requester), "title" to title, "date" to day(due))
            } else {
                line("action.forPersonNoDate", "owner" to text(owner), "requester" to text(requester), "title" to title)
            }
        }
        return if (due != null) {
            line("action.owner", "owner" to text(owner), "title" to title, "date" to day(due))
        } else {
            line("action.ownerNoDate", "owner" to text(owner), "title" to title)
        }
    }

    private val LOCAL_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}\$")

    /** An action after a verdict: delivered, late, dropped, done by you, a new date or not yet. */
    fun verdictLine(task: Task, promise: JsonObject?, verdict: JsonObject, names: DocNames): DocLine {
        val owner = text(ownerName(promise, names) ?: names.me)
        val title = text(task.title.trim())
        val blocker = verdict.text("blocker")?.trim()?.takeIf { it.isNotEmpty() }
        return when (verdict.text("kind")) {
            "delivered" -> line("action.delivered", "owner" to owner, "title" to title)
            "late" -> line("action.deliveredLate", "owner" to owner, "title" to title)
            "dropped" -> line("action.dropped", "owner" to owner, "title" to title)
            "taken_over" -> line("action.takenOver", "owner" to owner, "title" to title, "me" to text(names.me))
            else -> {
                val newDue = verdict.text("new_due")?.takeIf { LOCAL_DATE.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                when {
                    newDue != null && blocker != null ->
                        line("action.newDateBlocker", "owner" to owner, "title" to title, "date" to day(newDue), "blocker" to text(blocker))
                    newDue != null -> line("action.newDate", "owner" to owner, "title" to title, "date" to day(newDue))
                    blocker != null -> line("action.notYetBlocker", "owner" to owner, "title" to title, "blocker" to text(blocker))
                    else -> line("action.notYet", "owner" to owner, "title" to title)
                }
            }
        }
    }

    private fun decisionLine(value: JsonObject, names: DocNames): DocLine {
        val decided = text(value.text("text").orEmpty().trim())
        val by = value.whole("by")?.takeIf { it > 0 }
        return if (by != null) line("decision.by", "text" to decided, "by" to text(names.person(by))) else line("decision.plain", "text" to decided)
    }

    private fun block(heading: String, items: List<DocLine>): List<DocBlock> =
        if (items.isEmpty()) emptyList() else listOf(DocBlock(heading = line(heading), items = items))

    private fun issueLines(ledger: ProLedger?, label: Long): List<DocLine> =
        Groups.openIssues(ledger, label).map { literal(it.value.text("text").orEmpty().trim()) }

    private fun titleKey(prefix: String, kind: String): String = "$prefix.title.${Sessions.sessionKindOf(kind).key}"

    // --- A session's documents -------------------------------------------------------------

    /** The agenda: actions due, other open actions, open issues (weekly), the closing line. */
    fun agendaDocument(
        group: Group,
        kind: String,
        date: LocalDate,
        tasks: List<Task>,
        ledger: ProLedger?,
        names: DocNames,
        today: LocalDate,
        weekStart: WeekStart,
        zone: TimeZone,
    ): ProDocument {
        val sessionKind = Sessions.sessionKindOf(kind)
        val byId = tasks.associateBy { it.id }
        val actions = Sessions.sessionActions(group, tasks, ledger, today, sessionKind, weekStart, zone)
        fun lines(ids: List<Long>) = ids.map { actionLine(byId.getValue(it), Promises.promiseOf(ledger, it), names, zone) }
        val blocks = mutableListOf<DocBlock>()
        blocks += block("heading.actionsDue", lines(actions.verdicts))
        blocks += block("heading.actionsOpen", lines(actions.others))
        if (sessionKind == SessionKind.WEEKLY) blocks += block("heading.issues", issueLines(ledger, group.label))
        blocks += DocBlock(text = line("agenda.close"))
        return ProDocument(line(titleKey("agenda", kind), "group" to text(group.name), "date" to day(date)), blocks)
    }

    private class SessionParts(
        val contents: SessionContents,
        val decisions: List<DocLine>,
        val created: List<DocLine>,
        val byId: Map<Long, Task>,
    )

    private fun sessionParts(
        group: Group,
        round: String,
        started: String,
        ended: String,
        tasks: List<Task>,
        ledger: ProLedger?,
        names: DocNames,
        zone: TimeZone,
    ): SessionParts {
        val byId = tasks.associateBy { it.id }
        val contents = Sessions.sessionContents(round, started, ended, group, tasks, ledger)
        val decisions = contents.decisions.mapNotNull { id -> ledger?.value("log", id)?.let { decisionLine(it, names) } }
        val created = contents.created.map { id -> actionLine(byId.getValue(id), Promises.promiseOf(ledger, id), names, zone) }
        return SessionParts(contents, decisions, created, byId)
    }

    /** The read-back: the session's decisions and new actions, to read aloud. */
    fun readBackDocument(
        group: Group,
        date: LocalDate,
        round: String,
        started: String,
        ended: String,
        tasks: List<Task>,
        ledger: ProLedger?,
        names: DocNames,
        zone: TimeZone,
    ): ProDocument {
        val parts = sessionParts(group, round, started, ended, tasks, ledger, names, zone)
        val blocks = (block("heading.decisions", parts.decisions) + block("heading.newActions", parts.created)).toMutableList()
        if (blocks.isEmpty()) blocks += DocBlock(text = line("readback.nothing"))
        return ProDocument(line("readback.title", "group" to text(group.name), "date" to day(date)), blocks)
    }

    /**
     * The recap of one session: decisions, new actions, delivered, changed, issues solved, open
     * issues, open questions asked in it, and who was absent.
     */
    fun recapDocument(
        group: Group,
        kind: String,
        date: LocalDate,
        round: String,
        started: String,
        ended: String,
        tasks: List<Task>,
        ledger: ProLedger?,
        names: DocNames,
        zone: TimeZone,
        absent: List<Long> = emptyList(),
    ): SessionRecap {
        val parts = sessionParts(group, round, started, ended, tasks, ledger, names, zone)
        val delivered = mutableListOf<DocLine>()
        val changed = mutableListOf<DocLine>()
        val inRecap = LinkedHashSet<Long>(parts.contents.created)
        for ((taskId, verdict) in parts.contents.verdicts) {
            val task = parts.byId[taskId] ?: continue
            val written = verdictLine(task, Promises.promiseOf(ledger, taskId), verdict, names)
            val kindOf = verdict.text("kind")
            if (kindOf == "delivered" || kindOf == "late") delivered += written else changed += written
            inRecap += taskId
        }
        fun literals(ids: List<String>) = ids.mapNotNull { id -> ledger?.value("log", id)?.let { literal(it.text("text").orEmpty().trim()) } }
        val blocks = mutableListOf<DocBlock>()
        blocks += block("heading.decisions", parts.decisions)
        blocks += block("heading.newActions", parts.created)
        blocks += block("heading.delivered", delivered)
        blocks += block("heading.changed", changed)
        blocks += block("heading.issuesSolved", literals(parts.contents.solved))
        blocks += block("heading.issues", issueLines(ledger, group.label))
        blocks += block("heading.questions", literals(parts.contents.questions))
        val absentNames = absent.distinct().map { names.person(it) }
        if (absentNames.isNotEmpty()) blocks += DocBlock(text = line("absent", "names" to text(absentNames.joinToString(", "))))
        if (blocks.isEmpty()) blocks += DocBlock(text = line("recap.nothing"))
        return SessionRecap(
            document = ProDocument(line(titleKey("recap", kind), "group" to text(group.name), "date" to day(date)), blocks),
            taskIds = inRecap.filter { Promises.promiseOf(ledger, it) != null }.sorted(),
            decisionIds = parts.contents.decisions,
        )
    }

    // --- The check message -----------------------------------------------------------------

    /** The first word of a name, for a greeting. */
    fun firstName(name: String): String = name.trim().split(Regex("\\s+")).first()

    /** One message to one person: one thing as a sentence, more as a list. Null without items. */
    fun checkDocument(name: String, items: List<CheckItem>): ProDocument? {
        if (items.isEmpty()) return null
        val greeting = text(firstName(name))
        if (items.size == 1) {
            val only = items.single()
            val title = text(only.title.trim())
            val sentence = if (only.due != null) {
                line("check.one", "name" to greeting, "title" to title, "date" to day(only.due))
            } else {
                line("check.oneNoDate", "name" to greeting, "title" to title)
            }
            return ProDocument(blocks = listOf(DocBlock(text = sentence)))
        }
        return ProDocument(
            blocks = listOf(
                DocBlock(
                    text = line("check.intro", "name" to greeting, "count" to DocParam.Number(items.size.toLong())),
                    items = items.map { item ->
                        if (item.due != null) {
                            line("check.item", "title" to text(item.title.trim()), "date" to day(item.due))
                        } else {
                            line("check.itemNoDate", "title" to text(item.title.trim()))
                        }
                    },
                ),
                DocBlock(text = line("check.close")),
            ),
        )
    }

    // --- The weekly note -------------------------------------------------------------------

    private fun count(value: Int) = DocParam.Number(value.toLong())

    /**
     * The weekly note (section 21.19): per group, counts of actions delivered on time, late, dropped
     * and done by you in the period, each date that slipped, decisions, open issues, actions open
     * past their date, and actions due in the 7 days after the period. [tasks] are the tasks
     * carrying a group's label: open ones, and done ones completed in the period.
     */
    fun weeklyNoteDocument(
        groups: List<Group>,
        tasks: List<Task>,
        ledger: ProLedger?,
        from: LocalDate,
        to: LocalDate,
        names: DocNames,
        zone: TimeZone,
    ): ProDocument {
        fun inPeriod(day: LocalDate?) = day != null && day >= from && day <= to
        fun instantInPeriod(at: String?) = inPeriod(localDayOf(at, zone))
        fun dayOf(value: String?) = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val nextWeekEnd = to.plus(7, DateTimeUnit.DAY)

        class Slipped(val rank: Pair<Int, Long>, val due: LocalDate?, val id: Long, val line: DocLine)

        val blocks = mutableListOf<DocBlock>()
        for (group in groups) {
            var onTime = 0
            var late = 0
            var dropped = 0
            var takenOver = 0
            var pastDue = 0
            var next = 0
            val slipped = mutableListOf<Slipped>()
            for (task in Groups.actionsOf(tasks, group.label)) {
                val promise = Promises.promiseOf(ledger, task.id)
                val final = LedgerRules.finalVerdictOf(promise)
                if (promise != null && promise.text("dir") == "theirs" && final != null && instantInPeriod(final.text("at"))) {
                    when (final.text("kind")) {
                        "delivered" -> onTime++
                        "late" -> late++
                        "dropped" -> dropped++
                        "taken_over" -> takenOver++
                    }
                }
                if (task.done || final != null) continue
                val due = Groups.actionDue(task, promise, zone)
                if (due != null && due < to) pastDue++
                if (due != null && due > to && due <= nextWeekEnd) next++
                if (promise == null || promise.text("dir") != "theirs") continue
                val last = promise.list("verdicts").orEmpty().mapNotNull { it.asObject() }.lastOrNull { verdict ->
                    verdict["undone"].asFlag() != true && verdict.text("kind") == "not_yet" && instantInPeriod(verdict.text("at"))
                } ?: continue
                val written = verdictLine(task, promise, last, names)
                slipped += Slipped(
                    Groups.ownerRank(group, Groups.ownerOf(promise)),
                    Promises.effectiveDue(task, promise, zone),
                    task.id,
                    written.copy(key = written.key.replaceFirst(Regex("^action\\."), "note.slipped.")),
                )
            }
            slipped.sortWith { a, b ->
                val rank = Groups.BY_RANK.compare(a.rank, b.rank)
                if (rank != 0) return@sortWith rank
                val due = Groups.BY_DUE.compare(a.due, b.due)
                if (due != 0) due else a.id.compareTo(b.id)
            }
            val items = mutableListOf<DocLine>()
            if (onTime != 0) items += line("note.onTime", "count" to count(onTime))
            if (late != 0) items += line("note.late", "count" to count(late))
            if (dropped != 0) items += line("note.dropped", "count" to count(dropped))
            if (takenOver != 0) items += line("note.takenOver", "count" to count(takenOver), "me" to text(names.me))
            items += slipped.map { it.line }
            val decisions = Groups.groupLog(ledger, group.label, "decision").filter { inPeriod(dayOf(it.value.text("at"))) }.reversed()
            items += decisions.map { line("note.decision", "text" to text(it.value.text("text").orEmpty().trim())) }
            items += Groups.openIssues(ledger, group.label).map { line("note.issue", "text" to text(it.value.text("text").orEmpty().trim())) }
            if (pastDue != 0) items += line("note.pastDue", "count" to count(pastDue))
            if (next != 0) items += line("note.nextWeek", "count" to count(next))
            if (items.isEmpty()) items += line("note.nothing")
            blocks += DocBlock(heading = literal(group.name), items = items)
        }
        if (blocks.isEmpty()) blocks += DocBlock(text = line("note.noGroups"))
        return ProDocument(line("note.title", "from" to day(from), "to" to day(to)), blocks)
    }
}
