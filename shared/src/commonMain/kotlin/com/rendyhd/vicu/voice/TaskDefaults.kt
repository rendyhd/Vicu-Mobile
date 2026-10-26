package com.rendyhd.vicu.voice

import androidx.compose.runtime.Immutable

/**
 * What a new task gets from the list it is added in, the same as that list's quick add: the
 * project of a project screen or the Inbox, today's date on Today. Words that name a project or a
 * date win over these. Null fields mean none: the Inbox, no date.
 */
@Immutable
data class TaskDefaults(
    val projectId: Long? = null,
    val dueDate: String? = null,
    /** The list's name as the assistant's dictation context gives it ("Today", a project's title). */
    val listName: String? = null,
)
