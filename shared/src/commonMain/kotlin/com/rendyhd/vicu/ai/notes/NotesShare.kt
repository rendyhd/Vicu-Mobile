package com.rendyhd.vicu.ai.notes

import com.rendyhd.vicu.domain.model.SharedContent

/** What was shared to Vicu, as notes (`docs/ai-assistant-v1.md`, section 4.9). */
object NotesShare {
    /** Shared pictures, PDFs or text files can become pages of notes; several photos become pages in order. */
    fun offers(shared: SharedContent): Boolean {
        if (!shared.hasFiles) return false
        val type = shared.mimeType.orEmpty().lowercase()
        // A share of several kinds says `*/*`: the notes read what they can and say what they cannot.
        return type.isEmpty() || type.startsWith("image/") || type.startsWith("text/") || type == "application/pdf" || type == "*/*"
    }

    /** The text that becomes one page when nothing but text was shared. */
    fun text(shared: SharedContent): String? =
        if (shared.hasFiles) null else listOfNotNull(shared.subject, shared.text).joinToString("\n\n").takeIf { it.isNotBlank() }
}
