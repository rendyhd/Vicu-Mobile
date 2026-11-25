package com.rendyhd.vicu.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.util.EntryChip
import com.rendyhd.vicu.util.EntryChipLayout
import com.rendyhd.vicu.util.EntryChipPreset
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

    @Test
    fun `a view of the bottom bar is listed in the drawer only once it is turned on`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val store = BehaviorPrefsStore(data)
        assertTrue(store.getPrefs().first().drawerBarViews.isEmpty())

        store.setDrawerBarViewShown(DrawerView.TODAY, shown = true)
        store.setDrawerBarViewShown(DrawerView.INBOX, shown = true)
        assertEquals(setOf(DrawerView.TODAY, DrawerView.INBOX), store.getPrefs().first().drawerBarViews)
        assertTrue(store.getPrefs().first().hiddenDrawerViews.isEmpty(), "hiding is kept apart")

        store.setDrawerBarViewShown(DrawerView.TODAY, shown = false)
        store.setDrawerBarViewShown(DrawerView.INBOX, shown = false)
        assertNull(data.data.first()[stringSetPreferencesKey("drawer_bar_views")])
    }

    @Test
    fun `the drawer's order is stored - keeps a newer version's key - and resets`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val orderKey = stringPreferencesKey("drawer_order")
        val store = BehaviorPrefsStore(data)
        assertTrue(store.getPrefs().first().drawerOrder.isEmpty())

        data.edit { it[orderKey] = "LOGBOOK,CALENDAR,PROJECTS" }
        assertEquals(listOf("LOGBOOK", "CALENDAR", "PROJECTS"), store.getPrefs().first().drawerOrder)

        store.setDrawerOrder(listOf("PROJECTS", "LOGBOOK"))
        assertEquals("PROJECTS,LOGBOOK,CALENDAR", data.data.first()[orderKey])

        store.setDrawerOrder(emptyList())
        assertNull(data.data.first()[orderKey])
    }

    private val chipOrderKey = stringPreferencesKey("entry_chip_order")
    private val chipsPinnedKey = stringPreferencesKey("entry_chips_pinned")

    @Test
    fun `quick add starts with the Standard chips, compact`() = runTest {
        val chips = BehaviorPrefsStore(InMemoryPreferencesDataStore()).getPrefs().first().entryChips
        assertEquals(EntryChipLayout(), chips)
        assertEquals(EntryChipPreset.STANDARD, chips.preset)
    }

    @Test
    fun `the quick-add chips, their order and full height are kept`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val store = BehaviorPrefsStore(data)

        store.setEntryChipsPinned(emptySet())
        assertEquals(EntryChipPreset.MINIMAL, store.getPrefs().first().entryChips.preset)
        assertEquals("", data.data.first()[chipsPinnedKey])

        store.setEntryChipsPinned(setOf(EntryChip.REPEAT, EntryChip.WHEN))
        assertEquals(setOf(EntryChip.WHEN, EntryChip.REPEAT), store.getPrefs().first().entryChips.pinned)
        // Stored in the default order, whatever order they were picked in.
        assertEquals("WHEN,REPEAT", data.data.first()[chipsPinnedKey])

        val order = listOf(EntryChip.PROJECT) + (EntryChip.entries - EntryChip.PROJECT)
        store.setEntryChipOrder(order)
        store.setEntryOpenFull(true)
        val chips = store.getPrefs().first().entryChips
        assertEquals(order, chips.order)
        assertTrue(chips.openFull)
    }

    @Test
    fun `chips a newer version wrote are kept when this one writes`() = runTest {
        val data = InMemoryPreferencesDataStore()
        data.edit {
            it[chipOrderKey] = "ESTIMATE,WHEN"
            it[chipsPinnedKey] = "ESTIMATE,WHEN"
        }
        val store = BehaviorPrefsStore(data)
        assertEquals(setOf(EntryChip.WHEN), store.getPrefs().first().entryChips.pinned)
        assertEquals(EntryChip.WHEN, store.getPrefs().first().entryChips.order.first())

        store.setEntryChipsPinned(setOf(EntryChip.LABELS))
        assertEquals("LABELS,ESTIMATE", data.data.first()[chipsPinnedKey])
        store.setEntryChipOrder(EntryChip.entries)
        assertTrue(data.data.first()[chipOrderKey]!!.endsWith(",ESTIMATE"))
    }
}
