package com.rendyhd.vicu.ai.talk

import com.rendyhd.vicu.ai.AiContract

/**
 * Spoken yes or no to an open proposal card (`docs/ai-assistant-v1.md`, section 6.1): the
 * transcript, normalised, must be exactly one of the fixture's phrases for the language.
 * Vectors: `talk.confirmVectors`.
 */
object SpokenConfirmation {
    enum class Answer { YES, NO, OTHER }

    private val PUNCTUATION = Regex("[.,!?;:]")
    private val SPACES = Regex("\\s+")

    fun normalise(text: String): String =
        text.lowercase().replace('’', '\'').replace('‘', '\'')
            .replace(PUNCTUATION, " ")
            .replace(SPACES, " ")
            .trim()

    /** [language] is an ISO 639-1 code; a language without phrases confirms by tap only. */
    fun answer(text: String, language: String): Answer {
        val (yes, no) = AiContract.confirmPhrases[language.lowercase().substringBefore('-')] ?: return Answer.OTHER
        val n = normalise(text)
        return when (n) {
            in yes -> Answer.YES
            in no -> Answer.NO
            else -> Answer.OTHER
        }
    }
}
