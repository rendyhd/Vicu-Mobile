package com.rendyhd.vicu.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BehaviorPrefsStoreTest {

    private val hiddenKey = stringSetPreferencesKey("hidden_drawer_views")

    @Test
    fun `out of the box the drawer hides no view`() = runTest {
        val store = BehaviorPrefsStore(InMemoryPreferencesDataStore())
        assertTrue(store.getPrefs().first().hiddenDrawerViews.isEmpty())
    }

    @Test
    fun `views can be hidden and shown again`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val store = BehaviorPrefsStore(data)

        store.setDrawerViewHidden(DrawerView.LOGBOOK, hidden = true)
        store.setDrawerViewHidden(DrawerView.ANYTIME, hidden = true)
        assertEquals(setOf(DrawerView.LOGBOOK, DrawerView.ANYTIME), store.getPrefs().first().hiddenDrawerViews)

        store.setDrawerViewHidden(DrawerView.LOGBOOK, hidden = false)
        assertEquals(setOf(DrawerView.ANYTIME), store.getPrefs().first().hiddenDrawerViews)

        // With nothing hidden the key is gone: the default again.
        store.setDrawerViewHidden(DrawerView.ANYTIME, hidden = false)
        assertNull(data.data.first()[hiddenKey])
    }

    @Test
    fun `a view a newer version wrote is skipped when read and kept when another changes`() = runTest {
        val data = InMemoryPreferencesDataStore()
        data.edit { it[hiddenKey] = setOf("CALENDAR", "REVIEW") }
        val store = BehaviorPrefsStore(data)

        assertEquals(setOf(DrawerView.REVIEW), store.getPrefs().first().hiddenDrawerViews)

        store.setDrawerViewHidden(DrawerView.REVIEW, hidden = false)
        assertEquals(setOf("CALENDAR"), data.data.first()[hiddenKey])
    }
}
