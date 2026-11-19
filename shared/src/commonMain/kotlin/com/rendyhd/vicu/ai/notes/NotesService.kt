package com.rendyhd.vicu.ai.notes

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiProviders
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.ai.dictation.CheckedProject
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.dictation.DictationService
import com.rendyhd.vicu.ai.dictation.KnownProject
import com.rendyhd.vicu.ai.provider.ChatImage
import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.ai.provider.ChatRequest
import com.rendyhd.vicu.ai.provider.LlmException
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.repository.ProWorldSource
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.voice.TaskDefaults
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** Who the notes are read for (section 4.9.4): the account's name and the names the person knows. */
data class NotesPeople(val me: String?, val people: List<String>)

/**
 * The requests of notes (`docs/ai-assistant-v1.md`, section 4.9): one page picture read as text on
 * the reading model (4.9.2), and one batch organized on the dictation model (4.9.4) or an Update of
 * a one-batch plan (4.9.8), checked by [NotesRules].
 */
class NotesService(
    private val providers: AiProviders,
    private val settings: AiSettingsStore,
    private val dictation: DictationService,
    private val collaboration: CollaborationRepository,
    private val behaviorPrefs: BehaviorPrefsStore,
    private val proWorld: ProWorldSource?,
) {
    /**
     * The context of the notes' [date] (`now` at noon local), for the list [defaults] describes.
     */
    suspend fun context(defaults: TaskDefaults, date: LocalDate): DictationContext =
        dictation.context(defaults, reference = LocalDateTime(date, LocalTime(12, 0)))

    /**
     * `me` (the account's name as the member lists know it, or null) and `people`: the members of
     * projects someone else can see, and the Pro people while Pro and its assistant are on.
     */
    suspend fun people(): NotesPeople {
        val collab = collaboration.state.first()
        val me = collab.meAsUser()?.let { it.name.ifBlank { it.username } }
        val names = mutableListOf<String>()
        for (access in collab.projects.values) {
            if (!access.shared) continue
            for (member in access.members) if (member.id != collab.me) names += member.name.ifBlank { member.username }
        }
        val behavior = behaviorPrefs.getPrefs().first()
        if (behavior.isProEnabled() && behavior.proAssistant) {
            proWorld?.world?.value?.takeIf { it.promisesOn }?.people?.forEach { names += it.title }
        }
        return NotesPeople(me, names)
    }

    /** One page picture as text (4.9.2). Throws [LlmException]. */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun readPage(model: String, jpeg: ByteArray, page: Int, pages: Int, title: String): String {
        val s = settings.current()
        val connection = providers.connection(s) ?: throw LlmException(LlmException.Kind.BAD_REQUEST, "Set up the assistant first.")
        val answer = providers.llm.complete(
            connection,
            ChatRequest(
                model = model,
                system = AiContract.prompt("notesRead"),
                messages = listOf(ChatMessage.User(NotesRules.readPageUserText(page, pages, title), listOf(ChatImage("image/jpeg", Base64.encode(jpeg))))),
                deterministic = true,
                idleTimeoutSeconds = AiContract.limits.askIdleTimeoutSeconds,
                maxTokens = AiContract.limits.notesReadMaxTokens,
            ),
        )
        return NotesRules.readPageAnswer(answer.message.text)
    }

    /**
     * One batch (4.9.4) checked by 4.9.6; with [plan] (its JSON) and [words] an Update (4.9.8).
     * Throws [LlmException].
     */
    suspend fun organize(
        context: DictationContext,
        people: NotesPeople,
        title: String,
        pages: List<NotesPage>,
        known: List<KnownProject>,
        firstProjectNumber: Int,
        firstDraftNumber: Int,
        plan: String? = null,
        words: String? = null,
    ): NotesPlan {
        val s = settings.current()
        val connection = providers.connection(s) ?: throw LlmException(LlmException.Kind.BAD_REQUEST, "Set up the assistant first.")
        val contextJson = NotesRules.contextJson(context, people.me, people.people, title)
        val answer = providers.structured.request(
            connection,
            ChatRequest(
                model = s.dictationModel,
                system = NotesRules.system(editing = plan != null),
                messages = listOf(ChatMessage.User(NotesRules.userMessage(contextJson, pages, known, plan, words))),
                schema = AiContract.notesAnswerSchema,
                structuredMode = s.structuredMode(s.dictationModel),
                deterministic = true,
                idleTimeoutSeconds = AiContract.limits.dictationIdleTimeoutSeconds,
                maxTokens = AiContract.limits.brainDumpMaxTokens,
            ),
        )
        return NotesRules.check(answer, context, pages, people.me, known, firstProjectNumber, firstDraftNumber)
    }

    /** The paths of the plan's new projects, for the next batch's `<projects_so_far>`. */
    fun known(projects: List<CheckedProject>, context: DictationContext): List<KnownProject> =
        NotesRules.knownProjects(projects) { id -> context.projects.firstOrNull { it.id == id }?.path.orEmpty() }
}
