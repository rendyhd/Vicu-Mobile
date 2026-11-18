package com.rendyhd.vicu.ui.screens.taskentry

import com.rendyhd.vicu.domain.model.TaskReminder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What quick add holds that the user wrote or picked, small enough for the instance state that
 * survives process death. The system may end the app while the camera is open (Take photo), and
 * the sheet then comes back with this draft instead of an empty one. Location choices are left
 * out: they depend on saved places that are read again anyway, and the title still names them.
 */
@Serializable
internal data class TaskEntrySavedDraft(
    val title: String,
    val description: String,
    val pendingImages: Map<String, String> = emptyMap(),
    val pendingAttachmentUris: List<String> = emptyList(),
    val pendingAttachmentMimeType: String? = null,
    val dueDate: String = "",
    val dueDateIsManual: Boolean = false,
    val priority: Int = 0,
    val priorityIsManual: Boolean = false,
    val projectId: Long = 0,
    val projectIsManual: Boolean = false,
    val labelIds: List<Long> = emptyList(),
    val reminders: List<TaskReminder> = emptyList(),
    val repeatAfter: Long? = null,
    val repeatMode: Int? = null,
    val subtasks: List<String> = emptyList(),
    val promiseWords: String = "",
    val promiseDoneWhen: String = "",
    /** Token types by name: the dismissed chips, the pinned ones and the text each dismissal hid. */
    val suppressedTypes: List<String> = emptyList(),
    val pinnedTypes: List<String> = emptyList(),
    val suppressedRawTexts: Map<String, List<String>> = emptyMap(),
    val entryProjectId: Long? = null,
    val seedDueDate: String? = null,
)

internal object TaskEntrySavedDraftCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Instance state is capped near 1 MB for everything; a draft beyond this is not kept. */
    const val MAX_ENCODED_CHARS = 150_000

    fun encode(draft: TaskEntrySavedDraft): String? =
        json.encodeToString(TaskEntrySavedDraft.serializer(), draft).takeIf { it.length <= MAX_ENCODED_CHARS }

    fun decode(encoded: String?): TaskEntrySavedDraft? {
        if (encoded.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(TaskEntrySavedDraft.serializer(), encoded) }.getOrNull()
    }
}
