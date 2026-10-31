package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.ui.components.shared.IconRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A project or list without an icon (docs/cross-app-semantics-v1.md sections 3 and 11.3). */
class ProjectIconPreferencesTest {

    @Test
    fun noneHidesTheFolderAndAnythingElseIsTheDefault() = runTest {
        val synced = InMemorySyncedEntries(
            mapOf(
                "project_icon.5" to JsonPrimitive("none"),
                "project_icon.6" to JsonPrimitive("rocket"),
                "project_icon.x" to JsonPrimitive("none"),
                "task_order" to JsonPrimitive("none"),
            ),
        )
        assertEquals(setOf(5L), ProjectIconPreferences(synced).withoutIcon.first())
    }

    @Test
    fun choosingTheFolderAgainWritesTheDefault() = runTest {
        val synced = InMemorySyncedEntries()
        val icons = ProjectIconPreferences(synced)
        icons.setShowIcon(7, show = false)
        assertEquals(JsonPrimitive("none"), synced.entries.first()["project_icon.7"])
        icons.setShowIcon(7, show = true)
        assertNull(synced.entries.first()["project_icon.7"])
        assertTrue(icons.withoutIcon.first().isEmpty())
    }

    @Test
    fun aListIconOfNoneIsDrawnWithoutAnIcon() {
        assertNull(IconRegistry.customListIcon("none"))
        assertNotNull(IconRegistry.customListIcon(""))
        assertNotNull(IconRegistry.customListIcon("from-a-newer-version"))
        assertNotNull(IconRegistry.customListIcon("star"))
    }
}
