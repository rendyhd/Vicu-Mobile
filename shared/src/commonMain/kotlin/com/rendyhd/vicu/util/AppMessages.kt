package com.rendyhd.vicu.util

import com.rendyhd.vicu.i18n.UiText
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A message for the app-wide snackbar. When [actionLabel] and [onAction] are set the snackbar
 * shows that action (for example "Undo") and calls [onAction] if the user taps it. A message with
 * [durationMillis] stays that long (longer when an accessibility service asks for more time)
 * instead of the snackbar's long default; a newer message replaces it. With [openTaskId], for a
 * message about one new task, the snackbar also offers Open, which opens that task's editor.
 *
 * [content] and [action] are what is shown: a string of the catalog, which the snackbar reads in
 * the reader's language, or plain text ([UiText.Raw]) from a poster that is not translated yet.
 */
class AppMessage(
    val content: UiText,
    val action: UiText? = null,
    val onAction: (() -> Unit)? = null,
    val durationMillis: Long? = null,
    val openTaskId: Long? = null,
    /** With [action], the action opens this custom list. */
    val openCustomListId: String? = null,
) {
    constructor(
        text: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        durationMillis: Long? = null,
        openTaskId: Long? = null,
        openCustomListId: String? = null,
    ) : this(UiText.Raw(text), actionLabel?.let(UiText::Raw), onAction, durationMillis, openTaskId, openCustomListId)

    /** The text as it was posted, or the name of its string in the catalog ([UiText.name]). */
    val text: String get() = content.name

    /** The action's label as it was posted, or the name of its string in the catalog. */
    val actionLabel: String? get() = action?.name
}

/**
 * App-wide one-shot messages for outcomes nobody is looking at when they arrive, such as an
 * autosave that fails after the editor was closed. The app scaffold shows them in a snackbar
 * that sits above every screen, including the full-screen task editor.
 *
 * Messages posted while nothing is collecting wait in a small buffer (the oldest is dropped
 * when it is full), so a failure that happens while the app is in the background is still
 * shown when the user comes back.
 */
class AppMessages {
    private val channel = Channel<AppMessage>(capacity = BUFFER_SIZE, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Single-consumer stream: collect it from exactly one place (the app scaffold). */
    val messages: Flow<AppMessage> = channel.receiveAsFlow()

    fun post(message: String) {
        if (message.isNotBlank()) channel.trySend(AppMessage(message))
    }

    /**
     * Posts [message] with an action the user can tap (an Undo, for example), for [durationMillis]
     * when given. With [openTaskId] the snackbar also offers Open for that task.
     */
    fun post(
        message: String,
        actionLabel: String,
        durationMillis: Long? = null,
        openTaskId: Long? = null,
        onAction: () -> Unit,
    ) {
        if (message.isNotBlank()) channel.trySend(AppMessage(message, actionLabel, onAction, durationMillis, openTaskId))
    }

    /**
     * Posts a message from the catalog with an action from the catalog (the completion toast's
     * "Completed" and "Undo"), for [durationMillis] when given.
     */
    fun post(message: UiText, action: UiText, durationMillis: Long? = null, onAction: () -> Unit) {
        channel.trySend(AppMessage(message, action, onAction, durationMillis))
    }

    /** Posts a message from the catalog. */
    fun post(message: UiText) {
        channel.trySend(AppMessage(message))
    }

    /** Posts [message] with Open, which opens the custom list [listId]. */
    fun postOpeningList(message: String, listId: String) {
        if (message.isNotBlank()) channel.trySend(AppMessage(message, actionLabel = "Open", openCustomListId = listId))
    }

    private companion object {
        const val BUFFER_SIZE = 16
    }
}

/**
 * Says so in the app-wide snackbar when a write the user made was refused ("Could not schedule
 * the task: ..."); the repository has already rolled the change back. Returns whether it posted.
 * For writes started from places that have no error state of their own (a sheet, a row action).
 */
fun AppMessages.postIfRefused(result: NetworkResult<*>, doing: String): Boolean {
    if (result !is NetworkResult.Error) return false
    post("Could not $doing: ${result.message}")
    return true
}
