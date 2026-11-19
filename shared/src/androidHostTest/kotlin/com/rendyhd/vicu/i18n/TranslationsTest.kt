package com.rendyhd.vicu.i18n

import com.ibm.icu.text.PluralRules
import com.ibm.icu.util.ULocale
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
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
 * The catalogs in `shared/src/commonMain/composeResources` (docs/translating.md): the English
 * source in `values/strings.xml` and one `values-<language>/strings.xml` per translation. A
 * translation may leave strings out (they show in English) but what it has must fit the source.
 * Every failure names the file and the string and says what to do, since a translator reads it.
 */
class TranslationsTest {

    /** A catalog: plain strings by name, and plurals by name and quantity ("one", "other"). */
    private class Catalog(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private val root: File = repoRoot()
    private val resources = File(root, "shared/src/commonMain/composeResources")

    private fun read(file: File): Catalog {
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
        return Catalog(strings, plurals)
    }

    private val source = read(File(resources, "values/strings.xml"))

    /** The translations: folder name to language tag ("values-pt-rBR" to "pt-BR"). */
    private val translations: Map<String, String> = resources.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").isFile }
        .associate { it.name to it.name.removePrefix("values-").replace("-r", "-") }
        .toSortedMap()

    /** The `%1$s`, `%2$d` of a text, sorted. `%%` is a percent sign, not an argument. */
    private fun arguments(text: String): List<String> =
        Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]""").findAll(text.replace("%%", "")).map { it.value }.sorted().toList()

    /** The plural forms a language has (CLDR, through ICU): "one" and "other" for English. */
    private fun pluralForms(tag: String): Set<String> = PluralRules.forLocale(ULocale.forLanguageTag(tag)).keywords

    @Test
    fun `the English source is well formed`() {
        val problems = mutableListOf<String>()
        val name = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
        for ((key, text) in source.strings) {
            if (!name.matches(key)) problems += "$key: names are lower_snake_case"
            if (text.isBlank()) problems += "$key: is empty"
            if (key in source.plurals) problems += "$key: is both a string and a plural"
        }
        for ((key, items) in source.plurals) {
            if (!name.matches(key)) problems += "$key: names are lower_snake_case"
            if (items.keys != setOf("one", "other")) problems += "$key: English plurals have the quantities one and other (found ${items.keys})"
            if (items.values.any { it.isBlank() }) problems += "$key: has an empty item"
        }
        if ("language_name" !in source.strings) problems += "language_name: is missing"
        assertTrue("values/strings.xml:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `every translation fits the source`() {
        val problems = mutableListOf<String>()
        for ((folder, tag) in translations) {
            val at = "$folder/strings.xml"
            if (!Regex("^values-[a-z]{2,3}(-r[A-Z]{2})?$").matches(folder)) {
                problems += "$folder: name the folder values-<language> or values-<language>-r<REGION> (values-de, values-pt-rBR)"
                continue
            }
            val catalog = read(File(resources, "$folder/strings.xml"))
            val wanted = pluralForms(tag)

            for ((key, text) in catalog.strings) {
                val english = source.strings[key]
                when {
                    english == null -> problems += "$at: $key is not a string of values/strings.xml (renamed or removed there, or misspelled here)"
                    text.isBlank() -> problems += "$at: $key is empty (leave a string out to show the English text)"
                    arguments(text) != arguments(english) ->
                        problems += "$at: $key must keep the placeholders of the English text ${arguments(english)}, it has ${arguments(text)}"
                }
            }
            for ((key, items) in catalog.plurals) {
                val english = source.plurals[key]
                if (english == null) {
                    problems += "$at: $key is not a plural of values/strings.xml"
                    continue
                }
                val allowed = english.values.flatMap(::arguments).toSet()
                for ((quantity, text) in items) {
                    if (quantity !in wanted) problems += "$at: $key has the quantity \"$quantity\", which $tag does not use (it has ${wanted.sorted()})"
                    if (text.isBlank()) problems += "$at: $key ($quantity) is empty"
                    val extra = arguments(text).filterNot { it in allowed }
                    if (extra.isNotEmpty()) problems += "$at: $key ($quantity) has placeholders the English text lacks: $extra"
                }
                val missing = wanted - items.keys
                if (missing.isNotEmpty()) problems += "$at: $key needs an item for each quantity of $tag, missing ${missing.sorted()}"
            }
            val ownName = catalog.strings["language_name"]
            if (ownName == null) {
                problems += "$at: language_name is missing (the name of this language in the language itself; Settings shows it)"
            } else if (ownName == source.strings["language_name"] && !tag.startsWith("en")) {
                problems += "$at: language_name is still \"$ownName\"; write the name of this language in the language itself"
            }
        }
        assertTrue(problems.joinToString("\n", prefix = "\n"), problems.isEmpty())
    }

    @Test
    fun `Android and iOS list the translated languages`() {
        val tags = (listOf("en") + translations.values).sorted()
        val android = Regex("""<locale android:name="([^"]+)"""")
            .findAll(File(root, "app/src/main/res/xml/locales_config.xml").readText())
            .map { it.groupValues[1] }.sorted().toList()
        val plist = File(root, "iosApp/Vicu/Info.plist").readText().replace("\r\n", "\n")
        val block = Regex("""<key>CFBundleLocalizations</key>\s*<array>(.*?)</array>""", RegexOption.DOT_MATCHES_ALL).find(plist)?.groupValues?.get(1).orEmpty()
        val ios = Regex("<string>([^<]+)</string>").findAll(block).map { it.groupValues[1] }.sorted().toList()
        val hint = "run ./gradlew syncLanguages and commit what it writes"
        assertEquals("app/src/main/res/xml/locales_config.xml: $hint", tags, android)
        assertEquals("iosApp/Vicu/Info.plist CFBundleLocalizations: $hint", tags, ios)
    }

    /**
     * Text the cross-app contract pins (docs/cross-app-semantics-v1.md) is English in the fixture
     * and in the pure code; the catalog's English must say the same, so a translation starts from it.
     */
    @Test
    fun `the completion toast is the contract's English`() {
        val toast = Json.parseToJsonElement(File(root, "test-fixtures/cross-app-semantics-v1.json").readText())
            .jsonObject.getValue("completion").jsonObject.getValue("toast").jsonObject
        val plural = source.plurals.getValue("completion_toast")
        assertEquals(toast.getValue("single").jsonPrimitive.content, plural.getValue("one"))
        assertEquals(toast.getValue("many").jsonPrimitive.content.replace("{n}", "%1\$d"), plural.getValue("other"))
        assertEquals(toast.getValue("action").jsonPrimitive.content, source.strings.getValue("completion_undo"))
        assertEquals(CompletionHold.toastText(1), plural.getValue("one"))
        assertEquals(CompletionHold.toastText(4), plural.getValue("other").replace("%1\$d", "4"))
        assertEquals(CompletionHold.TOAST_ACTION, source.strings.getValue("completion_undo"))
    }

    companion object {
        /** This repo's root: the nearest directory above the working directory that holds `test-fixtures/`. */
        fun repoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                if (File(dir, "test-fixtures").isDirectory && File(dir, "docs").isDirectory) return dir
                dir = dir.parentFile
            }
            error("the repository root was not found above ${System.getProperty("user.dir")}")
        }
    }
}
