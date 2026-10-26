package com.rendyhd.vicu.speech

import com.rendyhd.vicu.ai.AiContract
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A project as the hint sees it. */
data class HintProject(
    val title: String,
    val inbox: Boolean = false,
    val writable: Boolean = true,
    val archived: Boolean = false,
)

/**
 * Whisper's initial prompt: the names the user is likely to say (`docs/ai-assistant-v1.md`,
 * section 3.4), built the same way on every device. Vectors: `speech.hintVectors`.
 */
object VocabularyHint {
    fun build(projects: List<HintProject>, labels: List<String>, places: List<String>): String? {
        val hint = AiContract.speech.getValue("hint").jsonObject
        val maxChars = hint.getValue("max_chars").jsonPrimitive.int
        val maxName = hint.getValue("max_name_chars").jsonPrimitive.int
        val separator = hint.getValue("separator").jsonPrimitive.content
        val end = hint.getValue("end").jsonPrimitive.content

        val order = compareBy<String>({ it.lowercase() }, { it })
        val names = projects.filter { it.inbox }.map { it.title } +
            projects.filter { !it.inbox && it.writable && !it.archived }.map { it.title }.sortedWith(order) +
            labels.sortedWith(order) +
            places.sortedWith(order)

        val taken = mutableListOf<String>()
        val seen = HashSet<String>()
        var length = 0
        for (raw in names) {
            val name = raw.trim()
            if (name.isEmpty() || name.none { it.isLetter() } || name.length > maxName) continue
            if (name.lowercase() in seen) continue
            val next = length + (if (taken.isEmpty()) 0 else separator.length) + name.length
            if (next + end.length > maxChars) break
            taken += name
            seen += name.lowercase()
            length = next
        }
        return if (taken.isEmpty()) null else taken.joinToString(separator) + end
    }
}
