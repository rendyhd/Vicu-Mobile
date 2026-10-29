package com.rendyhd.vicu.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

    private val legacyScheduleKey = stringPreferencesKey("schedule_action")
    private val swipeLeftKey = stringPreferencesKey("swipe_left")

    @Test
    fun `out of the box a swipe right completes and a swipe left chooses when`() = runTest {
        val prefs = BehaviorPrefsStore(InMemoryPreferencesDataStore()).getPrefs().first()
        assertEquals(SwipeAction.COMPLETE, prefs.swipeRight)
        assertEquals(SwipeAction.CHOOSE_WHEN, prefs.swipeLeft)
    }

    @Test
    fun `the old swipe to schedule setting carries over to swipe left`() = runTest {
        val urgent = InMemoryPreferencesDataStore()
        urgent.edit { it[legacyScheduleKey] = "PRIORITY_URGENT" }
        assertEquals(SwipeAction.URGENT, BehaviorPrefsStore(urgent).getPrefs().first().swipeLeft)

        val chooseWhen = InMemoryPreferencesDataStore()
        chooseWhen.edit { it[legacyScheduleKey] = "DUE_TODAY" }
        assertEquals(SwipeAction.CHOOSE_WHEN, BehaviorPrefsStore(chooseWhen).getPrefs().first().swipeLeft)
    }

    @Test
    fun `choosing swipe left replaces the old setting`() = runTest {
        val data = InMemoryPreferencesDataStore()
        data.edit { it[legacyScheduleKey] = "PRIORITY_URGENT" }
        val store = BehaviorPrefsStore(data)

        store.setSwipeLeft(SwipeAction.DELETE)
        store.setSwipeRight(SwipeAction.DUE_TOMORROW)

        val prefs = store.getPrefs().first()
        assertEquals(SwipeAction.DELETE, prefs.swipeLeft)
        assertEquals(SwipeAction.DUE_TOMORROW, prefs.swipeRight)
        assertNull(data.data.first()[legacyScheduleKey])
    }

    @Test
    fun `a swipe action a newer version wrote reads as the default`() = runTest {
        val data = InMemoryPreferencesDataStore()
        data.edit { it[swipeLeftKey] = "SNOOZE" }
        assertEquals(SwipeAction.CHOOSE_WHEN, BehaviorPrefsStore(data).getPrefs().first().swipeLeft)
    }
}
