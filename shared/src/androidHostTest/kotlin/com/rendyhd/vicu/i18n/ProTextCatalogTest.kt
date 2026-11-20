package com.rendyhd.vicu.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Text Vicu Pro writes for other people (docs/cross-app-semantics-v1.md section 21.19): the English
 * strings `pro_text_*` in `composeResources/values/strings.xml` say exactly what the contract's
 * `pro.documents.messages` say, with `{{name}}` as the positional `%n$s` (`%n$d` for `count`) that
 * [ProText] fills in, and every key a document builder can write has one.
 */
class ProTextCatalogTest {

    private val root: File = TranslationsTest.repoRoot()

    private val messages: Map<String, String> =
        Json.parseToJsonElement(File(root, "test-fixtures/cross-app-semantics-v1.json").readText()).jsonObject
            .getValue("pro").jsonObject.getValue("documents").jsonObject.getValue("messages").jsonObject
            .mapValues { it.value.jsonPrimitive.content }

    private class Catalog(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private val source: Catalog = run {
        val file = File(root, "shared/src/commonMain/composeResources/values/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Map<String, String>>()
        val nodes = document.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val name = element.getAttribute("name")
            when (element.tagName) {
                "string" -> strings[name] = element.textContent
                "plurals" -> {
                    val items = LinkedHashMap<String, String>()
                    val children = element.getElementsByTagName("item")
                    for (j in 0 until children.length) {
                        val item = children.item(j) as Element
                        items[item.getAttribute("quantity")] = item.textContent
                    }
                    plurals[name] = items
                }
            }
        }
        Catalog(strings, plurals)
    }

    /** The resource name of a key: `pro_text_`, dots as underscores, camelCase as snake_case. */
    private fun resourceName(key: String): String =
        "pro_text_" + key.replace('.', '_').replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }

    /** A catalog text with its positional arguments named again, as the contract writes it. */
    private fun named(text: String, params: List<String>): String =
        text.replace(Regex("""%(\d+)\$[sd]""")) { match -> "{{" + params[match.groupValues[1].toInt() - 1] + "}}" }
            .replace("\\'", "'").replace("\\\"", "\"")

    /** The contract's keys, a plural listed once without `_one` / `_other`. */
    private val contractKeys: Set<String> =
        messages.keys.mapTo(LinkedHashSet()) { it.replace(Regex("_(one|other)$"), "") }

    @Test
    fun `every contract key has an entry and nothing else does`() {
        assertEquals(contractKeys.sorted(), ProText.ENTRIES.keys.sorted())
    }

    @Test
    fun `the English strings are the contract's messages`() {
        val problems = mutableListOf<String>()
        for ((key, entry) in ProText.ENTRIES) {
            val name = resourceName(key)
            if (entry.name != name) problems += "$key: is ${entry.name}, should be $name"
            val plural = "${key}_one" in messages
            if (plural) {
                val items = source.plurals[name]
                if (items == null) {
                    problems += "$key: no plurals $name in strings.xml"
                    continue
                }
                if (entry.plural == null) problems += "$key: is a plural in the contract, a string in ProText"
                for (quantity in listOf("one", "other")) {
                    val expected = messages.getValue("${key}_$quantity")
                    val actual = items[quantity]?.let { named(it, entry.params) }
                    if (actual != expected) problems += "$name ($quantity): \"$actual\", the contract says \"$expected\""
                }
            } else {
                val text = source.strings[name]
                if (text == null) {
                    problems += "$key: no string $name in strings.xml"
                    continue
                }
                if (entry.string == null) problems += "$key: is a string in the contract, a plural in ProText"
                val actual = named(text, entry.params)
                if (actual != messages.getValue(key)) problems += "$name: \"$actual\", the contract says \"${messages.getValue(key)}\""
            }
            // Only a count is a number.
            val numbers = Regex("""%(\d+)\${'$'}d""").findAll((source.strings[name] ?: source.plurals[name]?.values?.joinToString(" ")).orEmpty())
                .map { entry.params[it.groupValues[1].toInt() - 1] }.toSet()
            if (numbers.any { it != "count" }) problems += "$name: only {{count}} is written as %n\$d"
        }
        assertTrue(problems.joinToString("\n", prefix = "\n"), problems.isEmpty())
    }

    @Test
    fun `every key a document builder writes has a string`() {
        val code = File(root, "shared/src/commonMain/kotlin/com/rendyhd/vicu/util/pro/Documents.kt").readText()
        val written = Regex("""line\(\s*"([A-Za-z.]+)"""").findAll(code).map { it.groupValues[1] }.toMutableSet()
        written += Regex("""block\(\s*"([A-Za-z.]+)"""").findAll(code).map { it.groupValues[1] }
        // Built from parts: the titles of a session's kind, and a slipped action of the weekly note.
        for (prefix in listOf("agenda", "recap")) for (kind in listOf("weekly", "standup")) written += "$prefix.title.$kind"
        for (key in listOf("newDate", "newDateBlocker", "notYet", "notYetBlocker")) written += "note.slipped.$key"
        written -= "literal"
        assertTrue("found too few keys: $written", written.size > 40)
        val missing = written.filter { it !in ProText.ENTRIES }
        assertTrue("keys without a string: $missing", missing.isEmpty())
    }
}
