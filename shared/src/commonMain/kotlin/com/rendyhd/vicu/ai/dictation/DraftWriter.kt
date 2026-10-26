package com.rendyhd.vicu.ai.dictation

import com.rendyhd.vicu.ai.AiText
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.location.LocationRepository
import com.rendyhd.vicu.location.LocationTrigger
import com.rendyhd.vicu.util.DefaultReminder
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.parser.ParsedRecurrence
import com.rendyhd.vicu.util.parser.RecurrenceUnit
import com.rendyhd.vicu.util.parser.recurrenceToVikunja
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** What Add created: the new tasks' ids (temp ids while offline) and how many failed. */
data class AddedDrafts(val taskIds: List<Long>, val failed: Int, val firstError: String?)

/**
 * Creates checked drafts through the path quick add uses (`docs/ai-assistant-v1.md`, section 4.5):
 * stored first and queued, the default reminder when the draft has none of its own, new labels
 * created first, subtasks as task relations, a place as a location reminder.
 */
class DraftWriter(
    private val tasks: TaskRepository,
    private val labels: LabelRepository,
    private val notificationPrefs: NotificationPrefsStore,
    private val locations: LocationRepository?,
) {
    /** Creates [drafts] in their order; [parentTaskId] makes them subtasks of that task (Break into steps). */
    suspend fun add(drafts: List<CheckedDraft>, zone: TimeZone, parentTaskId: Long? = null): AddedDrafts {
        val prefs = notificationPrefs.getPrefs().first()
        val known = labels.getAll().first().toMutableList()
        val created = mutableListOf<Long>()
        var failed = 0
        var firstError: String? = null
        val places = locations?.allPlaces().orEmpty()

        for (draft in drafts) {
            // New labels first, so the task can carry them.
            val labelIds = mutableListOf<Long>()
            for (label in draft.labels) {
                val id = label.id ?: known.firstOrNull { it.title.equals(label.title, ignoreCase = true) }?.id
                    ?: when (val result = labels.create(Label(id = 0L, title = label.title))) {
                        is NetworkResult.Success -> result.data.also { known += it }.id
                        else -> null
                    }
                if (id != null) labelIds += id
            }
            val task = taskOf(draft, zone, prefs.defaultReminderOffset, prefs.defaultReminderRelativeTo)
            val result = if (parentTaskId != null) tasks.createSubtask(parentTaskId, task) else tasks.createQueued(task)
            when (result) {
                is NetworkResult.Success -> {
                    val id = result.data.id
                    created += id
                    for (labelId in labelIds) labels.addToTask(id, labelId)
                    for (title in draft.subtasks) {
                        tasks.createSubtask(id, Task(id = 0, title = title, projectId = draft.projectId))
                    }
                    val place = draft.place?.let { p -> places.firstOrNull { it.id == p.placeId }?.let { it to p.trigger } }
                    if (place != null && locations != null) {
                        val trigger = if (place.second == "leave") LocationTrigger.LEAVE else LocationTrigger.ARRIVE
                        locations.setReminder(locations.reminderAt(id, place.first, trigger))
                    }
                }
                is NetworkResult.Error -> {
                    failed++
                    if (firstError == null) firstError = result.message
                }
                NetworkResult.Loading -> failed++
            }
        }
        return AddedDrafts(created, failed, firstError)
    }

    /** Undo of Add: deletes what was created (with its subtasks). */
    suspend fun undo(added: AddedDrafts) {
        for (id in added.taskIds.asReversed()) tasks.delete(id, deleteSubtasks = true)
        locations?.removeReminders(added.taskIds.toSet())
    }

    companion object {
        fun taskOf(draft: CheckedDraft, zone: TimeZone, defaultOffset: Int, defaultRelativeTo: String): Task {
            val due = draft.due?.let { DueDates.fromParsed(it, draft.dueHasTime, zone).toString() }.orEmpty()
            val recurrence = draft.repeat?.let { r ->
                recurrenceToVikunja(ParsedRecurrence(r.every, RecurrenceUnit.valueOf(r.unit.uppercase())))
            }
            val reminder = when {
                draft.reminder?.beforeDueMinutes != null && due.isNotEmpty() -> {
                    val seconds = draft.reminder.beforeDueMinutes * 60
                    DefaultReminder.build(due, if (seconds == 0) -1 else seconds, "due_date")
                }
                draft.reminder?.at != null -> TaskReminder(reminder = draft.reminder.at.toInstant(zone).toString())
                else -> DefaultReminder.build(due, defaultOffset, defaultRelativeTo)
            }
            return Task(
                id = 0,
                title = draft.title,
                description = AiText.notesToHtml(draft.notes),
                dueDate = due,
                priority = draft.priority ?: 0,
                projectId = draft.projectId,
                repeatAfter = recurrence?.repeatAfter ?: 0,
                repeatMode = recurrence?.repeatMode ?: 0,
                reminders = listOfNotNull(reminder),
            )
        }
    }
}
