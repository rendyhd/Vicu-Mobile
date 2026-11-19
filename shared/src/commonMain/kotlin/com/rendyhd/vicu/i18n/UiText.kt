package com.rendyhd.vicu.i18n

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Text for a person, named instead of written out, so that code without a screen (a view model,
 * the sync engine, a message for the snackbar) can say what to show and the screen shows it in the
 * reader's language (docs/translating.md; the strings are in `composeResources/values/strings.xml`).
 *
 * A composable that has the string at hand calls `stringResource(Res.string.x)` itself; this type
 * is for text that travels first. Tests compare it as a value: no resource is read until [text] or
 * [load] runs, and those need a device (the JVM unit tests have no assets).
 */
sealed interface UiText {
    /**
     * Text that is shown as it is: not ours to translate (a task's title, what a server answered),
     * or not in the catalog yet.
     */
    data class Raw(val value: String) : UiText

    /** A string of the catalog; [args] fill its `%1$s`, `%2$d` in order. */
    data class Res(val id: StringResource, val args: List<Any>) : UiText {
        constructor(id: StringResource, vararg args: Any) : this(id, args.toList())
    }

    /** A plural of the catalog in the form [quantity] takes; [args] fill it, by default with the quantity. */
    data class Plural(val id: PluralStringResource, val quantity: Int, val args: List<Any> = listOf(quantity)) : UiText

    /**
     * What a log or a test reads without loading anything: the text itself, or the string's name
     * (`completion_toast`). Never shown to a person.
     */
    val name: String
        get() = when (this) {
            is Raw -> value
            is Res -> id.key
            is Plural -> id.key
        }
}

/** The text in the language in use; draws again when the language changes. */
@Composable
fun UiText.text(): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> stringResource(id, *args.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, quantity, *args.toTypedArray())
}

/** The text in the language in use, for code outside a composable (a snackbar, a notification). */
suspend fun UiText.load(): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> getString(id, *args.toTypedArray())
    is UiText.Plural -> getPluralString(id, quantity, *args.toTypedArray())
}
