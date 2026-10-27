package com.rendyhd.vicu.ai.organize

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.dictation.DictationChecks
import com.rendyhd.vicu.ai.dictation.DictationContext
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** What a guided session asks suggestions for (`docs/ai-assistant-v1.md`, section 9.1). */
enum class OrganizeSession(val id: String) { INBOX("inbox"), OVERDUE("overdue"), WAITING("waiting"), REVIEW("review") }

/** A task of the `<tasks>` block, as far as the checks need it. */
data class OrganizeTask(val id: Long, val title: String, val projectId: Long, val hasDue: Boolean)

/** A suggestion after the checks of section 9.3. */
data class Suggestion(
    val taskId: Long,
    val title: String?,
    val projectId: Long?,
    /** Local wall-clock due date; a date-only one is 23:59:59 with [dueHasTime] false. */
    val due: LocalDateTime?,
    val dueHasTime: Boolean,
    val clearDue: Boolean,
    /** `"done"` or `"delete"`, or null. */
    val close: String?,
    val reason: String,
) {
    /** It changes nothing: the model says to leave the task as it is. */
    val leavesIt: Boolean get() = title == null && projectId == null && due == null && !clearDue && close == null
}

/** A review's next step: an open task ([taskId]) or a new task's [title]. */
data class NextStep(val taskId: Long?, val title: String?, val due: LocalDateTime?, val dueHasTime: Boolean)

/** Something the checks left out; [taskId] is null for the next step. */
data class OrganizeDropped(val taskId: Long?, val field: String, val value: JsonElement)

data class OrganizeResult(
    val suggestions: List<Suggestion>,
    val nextStep: NextStep?,
    val dropped: List<OrganizeDropped>,
)

/**
 * The checks of `docs/ai-assistant-v1.md`, section 9.3: a suggestion is about a task of the batch,
 * names a project the person can use, and reads its date through the quick-add parser. Vectors:
 * `organize.checks` of `ai-tools-v1.json`.
 */
object OrganizeChecks {
    private val limits get() = AiContract.limits

    /** [answer] matches `organize.answerSchema` (the caller checked it). */
    fun check(answer: JsonObject, session: OrganizeSession, tasks: List<OrganizeTask>, context: DictationContext): OrganizeResult {
        val byId = tasks.associateBy { it.id }
        val projectIds = context.projects.mapTo(HashSet()) { it.id }
        val dropped = mutableListOf<OrganizeDropped>()
        val seen = HashSet<Long>()
        val suggestions = mutableListOf<Suggestion>()
        for (element in (answer["suggestions"] as? JsonArray).orEmpty()) {
            val s = element as? JsonObject ?: continue
            val idElement = s["task_id"] ?: JsonNull
            val id = (idElement as? JsonPrimitive)?.longOrNull
            // 1. Task.
            val task = id?.let { byId[it] }
            if (task == null || !seen.add(task.id)) {
                dropped += OrganizeDropped(id, "task_id", idElement)
                continue
            }
            // 2. Title.
            val title = title(s.str("title"))?.takeIf { it != task.title }
            // 3. Project.
            var projectId = (s["project_id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
            if (projectId != null && projectId !in projectIds) {
                dropped += OrganizeDropped(task.id, "project", s["project_id"] ?: JsonNull)
                projectId = null
            }
            if (projectId == task.projectId) projectId = null
            // 4. When.
            val reading = DictationChecks.readWhen(s["when"] as? JsonObject, context)
            reading.droppedValue?.let { dropped += OrganizeDropped(task.id, "when", JsonPrimitive(it)) }
            val clearDue = reading.due == null && task.hasDue && (s["clear_due"] as? JsonPrimitive)?.booleanOrNull == true
            // 5. Close.
            val close = s.str("close")?.takeIf { it == "done" || it == "delete" }
            // 6. Reason.
            val reason = cut(DictationChecks.oneLine(s.str("reason").orEmpty()).trim(), limits.organizeReasonMaxChars)
            val suggestion = Suggestion(task.id, title, projectId, reading.due, reading.hasTime, clearDue, close, reason)
            // 7. Nothing.
            if (suggestion.leavesIt && reason.isEmpty()) {
                dropped += OrganizeDropped(task.id, "suggestion", JsonPrimitive(task.id))
                continue
            }
            suggestions += suggestion
        }
        return OrganizeResult(suggestions, nextStep(answer["next_step"] as? JsonObject, session, byId, context, dropped), dropped)
    }

    /** 8. Next step: only in a review; a task of the batch, else a new task's title. */
    private fun nextStep(
        o: JsonObject?,
        session: OrganizeSession,
        byId: Map<Long, OrganizeTask>,
        context: DictationContext,
        dropped: MutableList<OrganizeDropped>,
    ): NextStep? {
        if (o == null || session != OrganizeSession.REVIEW) return null
        val taskId = (o["task_id"] as? JsonPrimitive)?.longOrNull?.takeIf { it in byId }
        val title = if (taskId == null) title(o.str("title")) else null
        if (taskId == null && title == null) return null
        val reading = DictationChecks.readWhen(o["when"] as? JsonObject, context)
        reading.droppedValue?.let { dropped += OrganizeDropped(null, "when", JsonPrimitive(it)) }
        return NextStep(taskId, title, reading.due, reading.hasTime)
    }

    /** One line, trimmed, at most the title limit (cut at the last space before it); null when empty. */
    private fun title(raw: String?): String? =
        cut(DictationChecks.oneLine(raw.orEmpty()).trim(), limits.titleMaxChars).takeIf { it.isNotEmpty() }

    /** At most [max] characters, cut at the last space at or before [max] (without one, at [max]). */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val at = text.lastIndexOf(' ', max)
        return if (at < 0) text.substring(0, max) else text.substring(0, at)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
