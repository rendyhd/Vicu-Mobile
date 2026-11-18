package com.rendyhd.vicu.ui.components.task

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.util.parser.SyntaxMode
import com.rendyhd.vicu.util.parser.getPrefixes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `$` in quick add suggests Pro's people (contract 5.5), in both syntax modes. */
class PersonSuggestionsTest {

    private val people = listOf("Jan de Vries", "Priya", "Ana Silva")
    private val labels = listOf(Label(1, "Priority"))

    private fun suggest(text: String, mode: SyntaxMode = SyntaxMode.TODOIST, people: List<String> = this.people) =
        computeSuggestions(text, text.length, getPrefixes(mode), emptyList(), labels, people)

    @Test
    fun `a dollar offers the people whose name holds what is typed`() {
        assertEquals(listOf("Priya"), suggest("review the deck for \$pri").map { it.display })
        assertEquals(listOf("Priya"), suggest("review \$pri", SyntaxMode.VIKUNJA).map { it.display })
        assertEquals(people, suggest("call \$").map { it.display })
    }

    @Test
    fun `a name with a space is inserted quoted and a quoted name may hold spaces`() {
        val text = "check numbers \$\"Jan de"
        val item = suggest(text).single()
        assertEquals("\$\"Jan de Vries\" ", item.replacement)
        assertEquals("check numbers \$\"Jan de Vries\" " to 30, buildReplacement(text, text.length, item))
        assertEquals("\$Priya ", suggest("\$Pr").single().replacement)
    }

    @Test
    fun `no people, money or a closed name offers nothing`() {
        assertTrue(suggest("pay \$5").isEmpty())
        assertTrue(suggest("price in US\$").isEmpty())
        assertTrue(suggest("\$\"Jan de Vries\" x").isEmpty())
        assertTrue(suggest("call \$pri", people = emptyList()).isEmpty())
        // A label prefix nearer the cursor still wins.
        assertEquals(listOf("Priority"), suggest("\$Jan @pri").map { it.display })
    }
}
