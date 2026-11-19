package com.rendyhd.vicu.ai.notes

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.dictation.BrainDumpChecks
import com.rendyhd.vicu.ai.dictation.CheckedDraft
import com.rendyhd.vicu.ai.dictation.CheckedProject
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.dictation.DictationRequests
import com.rendyhd.vicu.ai.dictation.DroppedField
import com.rendyhd.vicu.ai.dictation.KnownProject
import com.rendyhd.vicu.util.pro.People
import com.rendyhd.vicu.util.pro.Person
import com.rendyhd.vicu.util.pro.PersonMatchKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** One page of the transcript: its number (1, 2, ...) and its text. */
@Serializable
data class NotesPage(val n: Int, val text: String)

/** A checked notes draft (section 4.9.6): the brain dump draft with who it is for, its page and its line. */
@Serializable
data class NotesDraft(
    val draft: CheckedDraft,
    /** `me`, the name as written, or null. */
    val owner: String?,
    val page: Int?,
    val from: String?,
) {
    val ref: String get() = draft.ref
}

@Serializable
data class NotesDecision(val what: String, val by: String?, val page: Int?)

@Serializable
data class NotesQuestion(val what: String, val ask: String?, val page: Int?)

/** One checked batch, or the plan of all of them (section 4.9.7). */
@Serializable
data class NotesPlan(
    val projects: List<CheckedProject> = emptyList(),
    val drafts: List<NotesDraft> = emptyList(),
    val decisions: List<NotesDecision> = emptyList(),
    val questions: List<NotesQuestion> = emptyList(),
    val dropped: List<DroppedField> = emptyList(),
    val unclear: List<String> = emptyList(),
)

/** What a card does with its owner (section 4.9.9). */
@Serializable
sealed interface OwnerAction {
    /** A task of mine. */
    @Serializable
    @SerialName("mine")
    data object Mine : OwnerAction

    /** Assigned to a member of the task's shared project (a Vikunja user id). */
    @Serializable
    @SerialName("assign")
    data class Assign(val userId: Long) : OwnerAction

    /** Pro: a promise of theirs (a person label). */
    @Serializable
    @SerialName("promise")
    data class Promise(val personId: Long) : OwnerAction

    /** Pro: my task "Confirm with <name>: <title>" with the person's label (semantics 21.6). */
    @Serializable
    @SerialName("confirm")
    data class Confirm(val personId: Long) : OwnerAction

    /** The name kept in the title: "Lalith: <title>". */
    @Serializable
    @SerialName("name")
    data object Name : OwnerAction
}

/** Which cards the plan's filter ticks (section 4.9.9). */
enum class NotesFilter(val key: String) {
    MINE("mine"),
    MINE_AND_WAITING("mine_and_waiting"),
    EVERYTHING("everything"),
}

/**
 * Notes to tasks (`docs/ai-assistant-v1.md`, section 4.9), the same rules as the desktop's
 * `src/shared/ai/notes.ts`: the pages read from images, the batches a long transcript is cut into,
 * the organize request, the checks after 4.8.3 (page, from, owner, decisions, questions), the merge
 * of the batches into one plan, who an item is for, and the line each added task's notes end with.
 * Vectors: the `notes` block of `ai-tools-v1.json`.
 */
object NotesRules {
    private val limits get() = AiContract.limits

    /** JavaScript's `\s`, so text is flattened as on the desktop. */
    private val WHITESPACE = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")
    private val LINE_BREAKS = Regex("[\\r\\n]+")
    private val LINE_ENDS = Regex("\\r\\n?")
    private val FENCED = Regex("```[^\\n]*\\n([\\s\\S]*?)\\n?```")

    // --- Pages and batches (4.9.2, 4.9.3) ---------------------------------------------------

    /** The text a page's `notesRead` answer gives: line ends as `\n`, trimmed, a whole-answer code fence removed. */
    fun readPageAnswer(answer: String): String {
        var text = answer.replace(LINE_ENDS, "\n").trim()
        FENCED.matchEntire(text)?.let { text = it.groupValues[1].trim() }
        return text
    }

    /** The user message's text of a `notesRead` request, after the image (4.9.2). */
    fun readPageUserText(page: Int, pages: Int, title: String): String {
        val name = title.trim()
        return if (name.isNotEmpty()) "<page>$page of $pages</page>\n<title>$name</title>" else "<page>$page of $pages</page>"
    }

    /** Cuts a long page at the last line break at or before [max], else the last space, else at [max]. */
    private fun cutPage(text: String, max: Int): Pair<String, String> {
        val lineBreak = text.lastIndexOf('\n', max)
        if (lineBreak > 0) return text.substring(0, lineBreak) to text.substring(lineBreak + 1)
        val space = text.lastIndexOf(' ', max)
        if (space > 0) return text.substring(0, space) to text.substring(space + 1)
        return text.substring(0, max) to text.substring(max)
    }

    /**
     * The batches of 4.9.3: the pages in order (empty ones left out), packed while a batch's text
     * stays at most [max] characters; a page longer than [max] is cut into parts that keep its number.
     */
    fun batches(pages: List<NotesPage>, max: Int = limits.notesBatchChars): List<List<NotesPage>> {
        val parts = mutableListOf<NotesPage>()
        for (page in pages) {
            var rest = page.text.replace(LINE_ENDS, "\n").trim()
            while (rest.length > max) {
                val (head, tail) = cutPage(rest, max)
                if (head.isNotBlank()) parts += NotesPage(page.n, head.trim())
                rest = tail.trim()
            }
            if (rest.isNotEmpty()) parts += NotesPage(page.n, rest)
        }
        val batches = mutableListOf<List<NotesPage>>()
        var current = mutableListOf<NotesPage>()
        var size = 0
        for (part in parts) {
            if (current.isNotEmpty() && size + part.text.length > max) {
                batches += current
                current = mutableListOf()
                size = 0
            }
            current += part
            size += part.text.length
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    // --- The request (4.9.4) ------------------------------------------------------------------

    /** System text of a notes request: the brain dump's, then `notes`; an Update (4.9.8) adds `brainDumpEdit`. */
    fun system(editing: Boolean): String {
        val organize = DictationRequests.brainDumpSystem(editing = false) + "\n\n" + AiContract.prompt("notes")
        return if (editing) organize + "\n\n" + AiContract.prompt("brainDumpEdit") else organize
    }

    /** The names of `people`: once each ignoring case (the first spelling kept), spacing collapsed, by lowercased name. */
    fun peopleNames(people: List<String>): List<String> {
        val seen = HashSet<String>()
        val names = mutableListOf<String>()
        for (raw in people) {
            val name = raw.trim().replace(WHITESPACE, " ")
            if (name.isEmpty() || !seen.add(name.lowercase())) continue
            names += name
        }
        return names.sortedWith(compareBy({ it.lowercase() }, { it }))
    }

    /**
     * The context JSON of a notes request: the keys of 4.1 (with `now` at the notes' date), then
     * `me`, `people` and `notes_title` (`notes.contextKeys`).
     */
    fun contextJson(context: DictationContext, me: String?, people: List<String>, title: String): String {
        val base = kotlinx.serialization.json.Json.parseToJsonElement(DictationRequests.contextJson(context)).jsonObject
        return JsonObject(
            base + mapOf(
                "me" to (me?.trim()?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull),
                "people" to JsonArray(peopleNames(people).map { JsonPrimitive(it) }),
                "notes_title" to JsonPrimitive(title.trim()),
            ),
        ).toString()
    }

    /** A page's text inside `<notes>`: its closing tags defused. */
    private fun pageBlock(page: NotesPage): String {
        val text = page.text.replace("</page>", "</ page>").replace("</notes>", "</ notes>")
        return "<page n=\"${page.n}\">\n$text\n</page>"
    }

    /**
     * The one user message of a notes request (4.9.4): context, then the new projects of earlier
     * batches, then (an Update) the plan, then the notes page by page, then (an Update) the words.
     */
    fun userMessage(
        contextJson: String,
        pages: List<NotesPage>,
        soFar: List<KnownProject> = emptyList(),
        plan: String? = null,
        words: String? = null,
    ): String {
        val parts = mutableListOf("<context>\n$contextJson\n</context>")
        if (soFar.isNotEmpty()) {
            val list = buildJsonArray {
                for (p in soFar) add(buildJsonObject { put("ref", p.ref); put("title", p.title); put("path", p.path) })
            }
            parts += "<projects_so_far>\n$list\n</projects_so_far>"
        }
        if (plan != null) parts += "<plan>\n$plan\n</plan>"
        parts += "<notes>\n${pages.joinToString("\n") { pageBlock(it) }}\n</notes>"
        if (words != null) parts += "<transcript>\n${words.replace("</transcript>", "</ transcript>")}\n</transcript>"
        return parts.joinToString("\n")
    }

    /** The `<plan>` of an Update (4.9.8): the brain dump plan with `owner`, `page` and `from` after `project_ref`. */
    fun planJson(projects: List<CheckedProject>, drafts: List<NotesDraft>, context: DictationContext): String = buildJsonObject {
        putJsonArray("projects") {
            for (p in projects) {
                add(
                    buildJsonObject {
                        put("ref", p.ref)
                        put("title", p.title)
                        put("parent_id", p.parentId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("parent_ref", p.parentRef?.let { JsonPrimitive(it) } ?: JsonNull)
                    },
                )
            }
        }
        putJsonArray("tasks") {
            for (d in drafts) {
                val task = DictationRequests.draftJson(d.draft, context, withProjectRef = true)
                val ordered = LinkedHashMap<String, JsonElement>()
                for ((key, value) in task) {
                    ordered[key] = value
                    if (key == "project_ref") {
                        ordered["owner"] = d.owner?.let { JsonPrimitive(it) } ?: JsonNull
                        ordered["page"] = d.page?.let { JsonPrimitive(it) } ?: JsonNull
                        ordered["from"] = d.from?.let { JsonPrimitive(it) } ?: JsonNull
                    }
                }
                add(JsonObject(ordered))
            }
        }
    }.toString()

    // --- The checks (4.9.6) -------------------------------------------------------------------

    /** Lower case, inner whitespace as one space, trimmed: how `from` is found in a page. */
    fun lineKey(text: String): String = text.replace(WHITESPACE, " ").trim().lowercase()

    private fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val space = text.lastIndexOf(' ', max)
        return if (space > 0) text.substring(0, space) else text.substring(0, max)
    }

    /** A name as given: whitespace runs as one space, trimmed, at most `notes_owner_max_chars`; empty or not a string is null. */
    fun cleanName(value: JsonElement?): String? {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val name = cut(text.replace(WHITESPACE, " ").trim(), limits.notesOwnerMaxChars)
        return name.ifEmpty { null }
    }

    /** The initials of a name of two words or more (`Rendy Jansen` is `rj`), else null. */
    private fun initialsOf(name: String): String? {
        val words = People.normalizeName(name).split(' ').filter { it.isNotEmpty() }
        return if (words.size >= 2) words.joinToString("") { it.first().toString() } else null
    }

    private val DOTS_AND_SPACES = Regex("[.\\s]")

    /**
     * The owner of 4.9.6 step 4: `me` for `me` or `I`, for the account's name by semantics 21.5
     * (title or unique first name), and for its initials (dots and spaces ignored); otherwise the
     * name as given; null for none.
     */
    fun ownerOf(value: JsonElement?, me: String?): String? {
        val name = cleanName(value) ?: return null
        val key = People.normalizeName(name)
        if (key == "me" || key == "i") return "me"
        val own = me?.trim().orEmpty()
        if (own.isNotEmpty()) {
            val match = People.matchPerson(name, listOf(Person(0, own)))
            if (match.match == PersonMatchKind.TITLE || match.match == PersonMatchKind.FIRST) return "me"
            val initials = initialsOf(own)
            if (initials != null && key.replace(DOTS_AND_SPACES, "") == initials) return "me"
        }
        return name
    }

    private class Placed(val page: Int?, val from: String?, val droppedPage: Boolean, val droppedFrom: Boolean)

    private fun wholePage(value: JsonElement?): Int? {
        val p = value as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        val d = p.contentOrNull?.toDoubleOrNull() ?: return null
        return if (d == kotlin.math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < Int.MAX_VALUE) d.toInt() else null
    }

    /** The page a line is on (steps 2 and 3): `page` when it is one of the batch's, and `from` when found there. */
    private fun placeLine(page: JsonElement?, from: JsonElement?, pages: List<NotesPage>): Placed {
        val numbers = pages.mapTo(HashSet()) { it.n }
        val whole = wholePage(page)
        val pageOk = whole != null && whole in numbers
        var at: Int? = if (pageOk) whole else null
        val droppedPage = page != null && page !is JsonNull && !pageOk
        val raw = (from as? JsonPrimitive)?.takeIf { it.isString }?.content
        val line = raw?.let { cut(it.replace(LINE_BREAKS, " ").replace(WHITESPACE, " ").trim(), limits.notesFromMaxChars) }.orEmpty()
        if (line.isEmpty()) return Placed(at, null, droppedPage, raw != null && raw.isNotBlank())
        val key = lineKey(line)
        if (at != null) {
            val found = pages.any { it.n == at && lineKey(it.text).contains(key) }
            return if (found) Placed(at, line, droppedPage, false) else Placed(at, null, droppedPage, true)
        }
        val found = pages.firstOrNull { lineKey(it.text).contains(key) } ?: return Placed(null, null, droppedPage, true)
        at = found.n
        return Placed(at, line, droppedPage, false)
    }

    /**
     * The checks of 4.9.6 on one batch's answer (which matches `notes.answerSchema`): 4.8.3 with the
     * projects of earlier batches, every kept project numbered from [firstProjectNumber]; then each
     * draft's `page`, `from` and `owner`, the drafts numbered from [firstDraftNumber]; then the
     * decisions and questions.
     */
    fun check(
        answer: JsonObject,
        context: DictationContext,
        pages: List<NotesPage>,
        me: String?,
        known: List<KnownProject> = emptyList(),
        firstProjectNumber: Int = 1,
        firstDraftNumber: Int = 1,
    ): NotesPlan {
        class Extra(val placed: Placed, val owner: String?, val page: JsonElement?, val from: JsonElement?)
        val extras = mutableListOf<Extra>()
        val result = BrainDumpChecks.check(answer, context, known, firstProjectNumber) { task ->
            extras += Extra(placeLine(task["page"], task["from"], pages), ownerOf(task["owner"], me), task["page"], task["from"])
        }
        val refs = HashMap<String, String>()
        val dropped = mutableListOf<DroppedField>()
        val drafts = result.drafts.mapIndexed { index, draft ->
            val ref = "d${firstDraftNumber + index}"
            refs[draft.ref] = ref
            val extra = extras[index]
            if (extra.placed.droppedPage) dropped += DroppedField(ref, "page", extra.page ?: JsonNull)
            if (extra.placed.droppedFrom) dropped += DroppedField(ref, "from", extra.from ?: JsonNull)
            NotesDraft(draft.copy(ref = ref), extra.owner, extra.placed.page, extra.placed.from)
        }
        val decisions = mutableListOf<NotesDecision>()
        for (raw in (answer["decisions"] as? JsonArray).orEmpty()) {
            val o = raw as? JsonObject ?: continue
            val what = whatOf(o["what"])
            if (what.isEmpty() || decisions.size >= limits.brainDumpTasksMax) continue
            decisions += NotesDecision(what, cleanName(o["by"]), placeLine(o["page"], null, pages).page)
        }
        val questions = mutableListOf<NotesQuestion>()
        for (raw in (answer["questions"] as? JsonArray).orEmpty()) {
            val o = raw as? JsonObject ?: continue
            val what = whatOf(o["what"])
            if (what.isEmpty() || questions.size >= limits.brainDumpTasksMax) continue
            questions += NotesQuestion(what, cleanName(o["ask"]), placeLine(o["page"], null, pages).page)
        }
        // A kept draft's entries take its new ref; the entries of a draft the checks left out (`task`,
        // `title`) have the ref "", so they never land on another card numbered the same.
        val renamed = result.dropped.map { entry ->
            when {
                entry.field == "task" || entry.field == "title" -> entry.copy(ref = "")
                entry.ref != null && entry.ref in refs -> entry.copy(ref = refs.getValue(entry.ref))
                else -> entry
            }
        }
        return NotesPlan(result.projects, drafts, decisions, questions, renamed + dropped, result.unclear)
    }

    private fun whatOf(value: JsonElement?): String {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return ""
        return cut(text.replace(LINE_BREAKS, " ").trim(), limits.titleMaxChars)
    }

    // --- The plan of all batches (4.9.3, 4.9.7) -----------------------------------------------

    /** The `<projects_so_far>` of the next batch: the plan's new projects with their paths. */
    fun knownProjects(projects: List<CheckedProject>, contextPath: (Long) -> String): List<KnownProject> {
        val byRef = projects.associateBy { it.ref }
        fun pathOf(project: CheckedProject, seen: MutableSet<String>): String {
            if (!seen.add(project.ref)) return project.title
            if (project.parentId != null) {
                val top = contextPath(project.parentId)
                return if (top.isNotEmpty()) "$top / ${project.title}" else project.title
            }
            val parent = project.parentRef?.let { byRef[it] }
            return if (parent != null) "${pathOf(parent, seen)} / ${project.title}" else project.title
        }
        return projects.map { KnownProject(it.ref, it.title, pathOf(it, HashSet())) }
    }

    /**
     * A checked batch added to the plan (4.9.7): its projects and drafts after the plan's, a draft
     * whose title and owner are already there left out (`duplicate`), at most `notes_tasks_max`
     * drafts (`task`), the decisions and questions not seen before.
     */
    fun merge(plan: NotesPlan, batch: NotesPlan): NotesPlan {
        fun key(d: NotesDraft) = lineKey(d.draft.title) + "\u0000" + lineKey(d.owner.orEmpty())
        val seen = plan.drafts.mapTo(HashSet()) { key(it) }
        val drafts = plan.drafts.toMutableList()
        val dropped = (plan.dropped + batch.dropped).toMutableList()
        for (draft in batch.drafts) {
            if (key(draft) in seen) {
                dropped += DroppedField("", "duplicate", JsonPrimitive(draft.draft.title))
                continue
            }
            if (drafts.size >= limits.notesTasksMax) {
                dropped += DroppedField("", "task", JsonPrimitive(draft.draft.title))
                continue
            }
            seen += key(draft)
            drafts += draft
        }
        return NotesPlan(
            projects = plan.projects + batch.projects,
            drafts = drafts,
            decisions = plan.decisions + batch.decisions.filter { d -> plan.decisions.none { lineKey(it.what) == lineKey(d.what) } },
            questions = plan.questions + batch.questions.filter { q -> plan.questions.none { lineKey(it.what) == lineKey(q.what) } },
            dropped = dropped,
            unclear = plan.unclear + batch.unclear,
        )
    }

    /** Update is offered while the plan came from one batch and has at most `brain_dump_tasks_max` drafts (4.9.8). */
    fun canUpdate(batches: Int, drafts: Int): Boolean = batches == 1 && drafts <= limits.brainDumpTasksMax

    // --- Owners (4.9.9) and adding (4.9.10) ----------------------------------------------------

    /**
     * The owner's default action: none or me is mine; a member of the task's shared project
     * ([members], semantics 21.5 matching) is assigned; a Pro person ([people]) is a confirm task,
     * or their promise while "Agreed in the meeting" ([agreed]) is on; any other name stays in the title.
     */
    fun defaultOwnerAction(owner: String?, members: List<Person>, people: List<Person>, agreed: Boolean): OwnerAction {
        if (owner == null || owner == "me") return OwnerAction.Mine
        People.matchPerson(owner, members).id?.let { return OwnerAction.Assign(it) }
        val person = People.matchPerson(owner, people).id ?: return OwnerAction.Name
        return if (agreed) OwnerAction.Promise(person) else OwnerAction.Confirm(person)
    }

    fun tickedBy(filter: NotesFilter, action: OwnerAction): Boolean = when (filter) {
        NotesFilter.EVERYTHING -> true
        NotesFilter.MINE -> action == OwnerAction.Mine
        NotesFilter.MINE_AND_WAITING -> action != OwnerAction.Name
    }

    /** The title a task is added with when the name stays in it: "Lalith: send the budget", at most `title_max_chars`. */
    fun namedTitle(name: String, title: String): String = cut("$name: $title", limits.titleMaxChars)

    /** The line every added task's notes end with: "From Offsite Q4, page 3: <the line>". */
    fun sourceLine(title: String, date: String, page: Int?, from: String?): String {
        val name = title.trim().ifEmpty { "Notes $date" }
        return "From $name" + (page?.let { ", page $it" } ?: "") + (from?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
    }

    /** A draft's notes with the source line after a blank line. */
    fun withSource(notes: String, line: String): String = if (notes.isNotBlank()) "$notes\n\n$line" else line

    /** The new projects Add creates: those that hold a ticked task, with their new parents, in the plan's order. */
    fun projectsInUse(projects: List<CheckedProject>, projectRefs: List<String?>): List<CheckedProject> {
        val byRef = projects.associateBy { it.ref }
        val used = HashSet<String>()
        for (first in projectRefs) {
            var ref = first
            while (ref != null && ref !in used) {
                val project = byRef[ref] ?: break
                used += ref
                ref = project.parentRef
            }
        }
        return projects.filter { it.ref in used }
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
