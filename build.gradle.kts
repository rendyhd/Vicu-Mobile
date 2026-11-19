// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
}

// The languages the app is translated into are the values-<language> folders of
// shared/src/commonMain/composeResources (docs/translating.md). Android and iOS each want them
// listed, so their settings can offer a language for Vicu: res/xml/locales_config.xml and
// CFBundleLocalizations in the iOS Info.plist. This task writes both lists from the folders;
// TranslationsTest fails while they are out of step.
tasks.register("syncLanguages") {
    group = "vicu"
    description = "Lists the translated languages for Android (locales_config.xml) and iOS (Info.plist)."
    val resources = layout.projectDirectory.dir("shared/src/commonMain/composeResources").asFile
    val localesConfig = layout.projectDirectory.file("app/src/main/res/xml/locales_config.xml").asFile
    val infoPlist = layout.projectDirectory.file("iosApp/Vicu/Info.plist").asFile
    doLast {
        // values-nl is "nl", values-pt-rBR is "pt-BR"; English is the unqualified values folder.
        val translated = resources.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && it.resolve("strings.xml").isFile }
            .map { it.name.removePrefix("values-").replace("-r", "-") }
            .sorted()
        val tags = listOf("en") + translated

        localesConfig.writeText(
            listOf(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
                "<!-- The languages Android offers for Vicu (Settings, Apps, Vicu, Language). Written by ./gradlew syncLanguages. -->",
                "<locale-config xmlns:android=\"http://schemas.android.com/apk/res/android\">",
                *tags.map { "    <locale android:name=\"$it\" />" }.toTypedArray(),
                "</locale-config>",
                "",
            ).joinToString("\n"),
        )

        val tab = "\t"
        val entry = listOf(
            "$tab<key>CFBundleLocalizations</key>",
            "$tab<array>",
            *tags.map { "$tab$tab<string>$it</string>" }.toTypedArray(),
            "$tab</array>",
            "",
        ).joinToString("\n")
        val plist = infoPlist.readText().replace("\r\n", "\n")
        val present = Regex("""\t<key>CFBundleLocalizations</key>\n\t<array>\n(?:\t\t<string>[^<]*</string>\n)*\t</array>\n""")
        val next = "$tab<key>CFBundleName</key>\n"
        val updated = when {
            present.containsMatchIn(plist) -> present.replace(plist) { entry }
            next in plist -> plist.replace(next, entry + next)
            else -> error("iosApp/Vicu/Info.plist: no CFBundleLocalizations and no CFBundleName to put it before")
        }
        infoPlist.writeText(updated)
        println("Languages: ${tags.joinToString(", ")}")
    }
}
