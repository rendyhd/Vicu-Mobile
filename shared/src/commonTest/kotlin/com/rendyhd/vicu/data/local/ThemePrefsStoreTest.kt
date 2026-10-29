package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemePrefsStoreTest {

    @Test
    fun `device colours are off until the switch is turned on`() = runTest {
        val store = ThemePrefsStore(InMemoryPreferencesDataStore())
        assertFalse(store.useDeviceColors.first())
        store.setUseDeviceColors(true)
        assertTrue(store.useDeviceColors.first())
        store.setUseDeviceColors(false)
        assertFalse(store.useDeviceColors.first())
    }

    @Test
    fun `pure black is off until the switch is turned on`() = runTest {
        val store = ThemePrefsStore(InMemoryPreferencesDataStore())
        assertFalse(store.pureBlack.first())
        store.setPureBlack(true)
        assertTrue(store.pureBlack.first())
        assertFalse(store.useDeviceColors.first())
        store.setPureBlack(false)
        assertFalse(store.pureBlack.first())
    }
}
