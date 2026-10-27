package com.rendyhd.vicu.ai.organize

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiProviders
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.ai.AiText
import com.rendyhd.vicu.ai.ask.AskBackend
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.dictation.DictationRequests
import com.rendyhd.vicu.ai.dictation.DictationService
import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.ai.provider.ChatRequest
import com.rendyhd.vicu.ai.provider.LlmException
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.voice.TaskDefaults
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The text of a request for suggestions (`docs/ai-assistant-v1.md`, section 9.1), the same as the
 * desktop's `src/shared/ai/organize.ts` sends for the same state.
 */
object OrganizeRequests {
    fun system(): String = AiContract.prompt("organize")

    /** Notes as sent: at most the limit, cut at the last space before it, trailing spaces trimmed, `…` added. */
    fun notes(plain: String): String {
        val max = AiContract.limits.organizeNotesMaxChars
        if (plain.length <= max) return plain
        return OrganizeChecks.cut(plain, max).trimEnd() + "…"
    }

    /** One task of `<tasks>`: its summary (5.2), then `created`, `changed` and `notes`. */
    fun taskEntry(summary: JsonObject, created: String?, changed: String?, notes: String): JsonObject =
        JsonObject(
            summary + mapOf(
                "created" to (created?.let { JsonPrimitive(it) } ?: JsonNull),
                "changed" to (changed?.let { JsonPrimitive(it) } ?: JsonNull),
                "notes" to JsonPrimitive(notes(notes)),
            ),
        )

    fun userMessage(context: DictationContext, session: OrganizeSession, tasks: List<JsonObject>): String = buildString {
        append("<context>\n").append(DictationRequests.contextJson(context)).append("\n</context>\n")
        append("<session>\n").append(session.id).append("\n</session>\n")
        append("<tasks>\n").append(JsonArray(tasks).toString()).append("\n</tasks>")
    }

    /** The local date of a server timestamp, `YYYY-MM-DD`, or null. */
    fun localDay(timestamp: String?, zone: TimeZone): String? =
        DateUtils.parseIsoDate(timestamp)?.let { DueDates.localDateOf(it, zone).toString() }
}

/** Where a guided session gets its suggestions (`docs/ai-assistant-v1.md`, section 9). */
interface OrganizeSuggester {
    /** Suggestions can be asked for: Ask is set up, switched on and agreed to. */
    suspend fun isReady(): Boolean

    /**
     * Suggestions for [taskIds] (one batch, in queue order) of a [session] named [list], whose new
     * tasks go to [defaultProjectId] (the Inbox when null). Throws when the request fails.
     */
    suspend fun suggest(session: OrganizeSession, taskIds: List<Long>, list: String, defaultProjectId: Long?): OrganizeResult
}

/**
 * Suggestions for the cards of a guided session (`docs/ai-assistant-v1.md`, section 9): one
 * structured request for a batch of tasks with Ask's model, checked by [OrganizeChecks].
 */
class OrganizeService(
    private val providers: AiProviders,
    private val settings: AiSettingsStore,
    private val backend: AskBackend,
    private val dictation: DictationService,
) : OrganizeSuggester {
    override suspend fun isReady(): Boolean = settings.current().askReady

    /** Throws [LlmException] when the request fails. */
    override suspend fun suggest(session: OrganizeSession, taskIds: List<Long>, list: String, defaultProjectId: Long?): OrganizeResult {
        val s = settings.current()
        val connection = providers.connection(s) ?: throw LlmException(LlmException.Kind.BAD_REQUEST, "Set up the assistant first.")
        val snapshot = backend.snapshot()
        val context = dictation.context(TaskDefaults(projectId = defaultProjectId, listName = list))
        val tasks = taskIds.mapNotNull { snapshot.byId[it] }
        val entries = tasks.map { task ->
            OrganizeRequests.taskEntry(
                summary = snapshot.summary(task),
                created = OrganizeRequests.localDay(task.created, snapshot.zone),
                changed = OrganizeRequests.localDay(task.updated, snapshot.zone),
                notes = AiText.htmlToPlain(task.description),
            )
        }
        val answer = providers.structured.request(
            connection,
            ChatRequest(
                model = s.askModel,
                system = OrganizeRequests.system(),
                messages = listOf(ChatMessage.User(OrganizeRequests.userMessage(context, session, entries))),
                schema = AiContract.organizeAnswerSchema,
                structuredMode = s.structuredMode(s.askModel),
                deterministic = true,
                idleTimeoutSeconds = AiContract.limits.dictationIdleTimeoutSeconds,
                maxTokens = AiContract.limits.organizeMaxTokens,
            ),
        )
        val checked = tasks.map { OrganizeTask(it.id, it.title, it.projectId, DateUtils.parseIsoDate(it.dueDate) != null) }
        return OrganizeChecks.check(answer, session, checked, context)
    }
}
