package com.rendyhd.vicu.ui.screens.shared

import com.rendyhd.vicu.i18n.UiText
import com.rendyhd.vicu.resources.Res
import com.rendyhd.vicu.resources.completion_toast
import com.rendyhd.vicu.resources.completion_undo
import com.rendyhd.vicu.util.AppMessage

/** The completion toast's action as it is posted: the catalog's "Undo". */
internal val COMPLETION_UNDO: UiText = UiText.Res(Res.string.completion_undo)

/**
 * A message as the tests read it. The completion toast is posted as the catalog's plural and a
 * count, and no resource can be read in a unit test, so it is spelled here by the contract's
 * English ([CompletionHold.toastText], which the fixture pins and `TranslationsTest` compares with
 * the catalog). Any other message reads as it was posted.
 */
internal fun AppMessage.completionText(): String {
    val content = content
    return if (content is UiText.Plural && content.id == Res.plurals.completion_toast) {
        CompletionHold.toastText(content.quantity)
    } else {
        text
    }
}
