package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.util.ProjectNotifyMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectNotifyPrefsStoreTest {

    private val store = ProjectNotifyPrefsStore(InMemoryPreferencesDataStore())

    @Test
    fun `a chosen mode is kept per project and replaced by the next choice`() = runTest {
        store.setMode(1, ProjectNotifyMode.MINE)
        store.setMode(2, ProjectNotifyMode.OFF)
        store.setMode(1, ProjectNotifyMode.ALL)

        assertEquals(mapOf(1L to ProjectNotifyMode.ALL, 2L to ProjectNotifyMode.OFF), store.chosenNow())
    }

    @Test
    fun `clearing forgets every choice`() = runTest {
        store.setMode(1, ProjectNotifyMode.OFF)

        store.clear()

        assertTrue(store.chosenNow().isEmpty())
    }
}
