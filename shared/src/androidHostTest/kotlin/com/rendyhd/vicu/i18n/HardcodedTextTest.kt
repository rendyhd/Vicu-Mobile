package com.rendyhd.vicu.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Text a person reads belongs in `composeResources/values/strings.xml` and reaches the screen
 * through `stringResource` or [UiText] (CLAUDE.md "Translations"). Most of the app was written
 * before that, so a baseline records how many hardcoded texts each file still has; a file may only
 * go down.
 *
 * The scan is a set of patterns, not a parser: text handed to `Text(...)`, to the named arguments
 * that hold text (`label = "..."`, `contentDescription = "..."`) and to `post("...")`. A line with
 * `i18n-ignore` in a comment is skipped (a brand, a format example).
 *
 * After moving text into the catalog, write the baseline again:
 * `./gradlew :shared:testAndroidHostTest --tests "*HardcodedTextTest" -Pi18nBaseline`
 */
class HardcodedTextTest {

    private val root: File = TranslationsTest.repoRoot()
    private val baselineFile = File(root, "shared/src/androidHostTest/resources/i18n/hardcoded-text-baseline.txt")

    /** Where text for a person is written: the shared code and each platform's own. */
    private val scanned = listOf(
        "shared/src/commonMain/kotlin",
        "shared/src/androidMain/kotlin",
        "shared/src/iosMain/kotlin",
        "app/src/main/java",
    )

    private fun scan(): Map<String, Int> {
        val counts = sortedMapOf<String, Int>()
        for (dir in scanned) {
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val count = hardcodedIn(file.readText()).size
                if (count > 0) counts[file.relativeTo(root).invariantSeparatorsPath] = count
            }
        }
        return counts
    }

    private fun readBaseline(): Map<String, Int> =
        if (!baselineFile.isFile) {
            emptyMap()
        } else {
            baselineFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
                val (count, path) = line.split('\t', limit = 2)
                path to count.toInt()
            }
        }

    @Test
    fun `hardcoded text does not grow and the baseline is current`() {
        val now = scan()
        if (System.getProperty("vicu.i18n.updateBaseline") == "true") {
            baselineFile.parentFile.mkdirs()
            baselineFile.writeText(
                "# Hardcoded texts per file (HardcodedTextTest). Written with -Pi18nBaseline; a file may only go down.\n" +
                    now.entries.joinToString("") { "${it.value}\t${it.key}\n" },
            )
            return
        }
        val before = readBaseline()
        val grown = now.filter { (file, count) -> count > (before[file] ?: 0) }
            .map { (file, count) -> "$file: $count hardcoded texts, the baseline allows ${before[file] ?: 0}" }
        assertTrue(
            "New text goes into composeResources/values/strings.xml and is shown with stringResource or UiText " +
                "(CLAUDE.md \"Translations\"):\n" + grown.joinToString("\n"),
            grown.isEmpty(),
        )
        val shrunk = before.filter { (file, count) -> (now[file] ?: 0) < count }
            .map { (file, count) -> "$file: ${now[file] ?: 0} hardcoded texts, the baseline still says $count" }
        assertTrue(
            "Text moved into the catalog; keep the gain by writing the baseline again " +
                "(./gradlew :shared:testAndroidHostTest --tests \"*HardcodedTextTest\" -Pi18nBaseline):\n" + shrunk.joinToString("\n"),
            shrunk.isEmpty(),
        )
    }

    @Test
    fun `the scan finds text and leaves the rest alone`() {
        fun find(code: String) = hardcodedIn(code)
        assertEquals(listOf("Week starts on"), find("""Text(text = "Week starts on", style = x)"""))
        assertEquals(listOf("Inbox"), find("""SmartListItem(label = "Inbox", icon = i)"""))
        assertEquals(listOf("Review"), find("""label = { Text("Review") },"""))
        assertEquals(listOf("Close"), find("""Icon(Icons.Default.Close, contentDescription = "Close")"""))
        assertEquals(listOf("Could not reopen \$failed of \${ids.size}"), find("""messages.post("Could not reopen ${'$'}failed of ${'$'}{ids.size}")"""))
        // From the catalog, an id, a symbol, a route: not hardcoded text.
        assertEquals(emptyList<String>(), find("""Text(stringResource(Res.string.nav_inbox))"""))
        assertEquals(emptyList<String>(), find("""item(key = "week_start") { Text("·") }"""))
        assertEquals(emptyList<String>(), find("""selected = currentRoute == "InboxRoute","""))
        assertEquals(emptyList<String>(), find("""Text("Vicu") // i18n-ignore: the name"""))
    }

    private companion object {
        /** Named arguments that hold text for a person in this code base. */
        const val TEXT_ARGUMENTS =
            "text|label|title|subtitle|description|contentDescription|stateDescription|onClickLabel|placeholder|" +
                "supportingText|message|hint|note|actionLabel|confirmLabel|confirmText|dismissLabel|dismissText|emptyText"

        val PATTERNS = listOf(
            Regex("""\bText\(\s*(?:text\s*=\s*)?"((?:[^"\\]|\\.)*)""""),
            Regex("""\b(?:$TEXT_ARGUMENTS)\s*=\s*"((?:[^"\\]|\\.)*)""""),
            Regex("""\bpost(?:IfRefused|OpeningList)?\(\s*"((?:[^"\\]|\\.)*)""""),
        )

        /** Two letters in a row: text, not a symbol or a number. */
        val HAS_WORD = Regex("""\p{L}{2,}""")

        /** The hardcoded texts of one file's code. A text matched by two patterns counts once. */
        fun hardcodedIn(code: String): List<String> {
            val found = mutableListOf<String>()
            for (line in code.lineSequence()) {
                if ("i18n-ignore" in line) continue
                val inLine = LinkedHashMap<Int, String>()
                for (pattern in PATTERNS) {
                    for (match in pattern.findAll(line)) {
                        val group = match.groups[1] ?: continue
                        if (HAS_WORD.containsMatchIn(group.value)) inLine[group.range.first] = group.value
                    }
                }
                found += inLine.values
            }
            return found
        }
    }
}
