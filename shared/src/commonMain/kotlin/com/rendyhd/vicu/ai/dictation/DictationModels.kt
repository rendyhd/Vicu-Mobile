package com.rendyhd.vicu.ai.dictation

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A project the model may put a task in: one the user can write to, not archived. */
@Serializable
data class ContextProject(val id: Long, val title: String, val path: String)

@Serializable
data class ContextLabel(val id: Long, val title: String)

@Serializable
data class ContextPlace(val id: String, val name: String)

/**
 * What a dictation knows about the user's lists (`docs/ai-assistant-v1.md`, section 4.1): sent to
 * the model as the `<context>` block and used by the checks of section 4.3.
 */
data class DictationContext(
    /** The reference time: when the recording ended. */
    val now: LocalDateTime,
    val zone: TimeZone,
    /** The name of the list the user is in. */
    val list: String,
    /** The project the list gives a new task, else the Inbox. */
    val defaultProjectId: Long,
    /** The list's default due date (Today gives today), or null. */
    val defaultDue: LocalDate?,
    val projects: List<ContextProject>,
    val labels: List<ContextLabel>,
    val places: List<ContextPlace>,
    /** The BCP 47 tag slash dates are read with; null is the device locale. */
    val locale: String? = null,
)

@Serializable
data class DraftLabel(
    /** Null for a label that does not exist yet (created on Add). */
    val id: Long?,
    val title: String,
)

@Serializable
data class DraftRepeat(val every: Int, val unit: String)

/** Exactly one of the two is set. */
@Serializable
data class DraftReminder(val beforeDueMinutes: Int? = null, val at: LocalDateTime? = null)

@Serializable
data class DraftPlace(val placeId: String, val trigger: String)

/** A draft after the checks of section 4.3: what a card shows and Add creates. */
@Serializable
data class CheckedDraft(
    val ref: String,
    val title: String,
    val notes: String,
    /** Local wall-clock due date; a date-only one is 23:59:59 with [dueHasTime] false. */
    val due: LocalDateTime?,
    val dueHasTime: Boolean,
    val projectId: Long,
    val labels: List<DraftLabel>,
    val priority: Int?,
    val repeat: DraftRepeat?,
    val reminder: DraftReminder?,
    val place: DraftPlace?,
    val subtasks: List<String>,
    /** Field names (in `dictation.fields` order) Vicu filled in itself or the model was unsure of. */
    val guessed: List<String>,
)

/** A field the checks rejected, for the card to explain. */
@Serializable
data class DroppedField(val ref: String?, val field: String, val value: JsonElement)

@Serializable
data class CheckResult(
    val drafts: List<CheckedDraft>,
    val dropped: List<DroppedField>,
    val unclear: List<String>,
)
