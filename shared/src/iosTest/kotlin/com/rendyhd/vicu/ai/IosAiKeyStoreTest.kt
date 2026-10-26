package com.rendyhd.vicu.ai

import com.rendyhd.vicu.auth.KeychainItems
import com.rendyhd.vicu.auth.KeychainValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosAiKeyStoreTest {
    private class FakeKeychain : KeychainItems {
        val items = mutableMapOf<String, String>()
        override fun read(account: String): KeychainValue = items[account]?.let { KeychainValue.Found(it) } ?: KeychainValue.Missing
        override fun write(account: String, value: String): Boolean {
            items[account] = value
            return true
        }
        override fun delete(account: String) {
            items.remove(account)
        }
    }

    @Test
    fun keys_are_kept_per_provider_and_blank_removes() = runTest {
        val keychain = FakeKeychain()
        val store = IosAiKeyStore(keychain, deleteAll = { keychain.items.clear() }, firstUseInInstall = { false })

        store.setKey("openai", "  sk-one ")
        store.setKey("anthropic", "sk-two")
        assertEquals("sk-one", store.getKey("openai"))
        assertEquals("sk-two", store.getKey("anthropic"))

        store.setKey("openai", " ")
        assertNull(store.getKey("openai"))
        store.clear()
        assertNull(store.getKey("anthropic"))
    }

    @Test
    fun the_first_use_of_an_install_deletes_what_an_earlier_install_left() = runTest {
        val keychain = FakeKeychain().apply { items["key.openai"] = "left behind" }
        var firstUse = true
        val store = IosAiKeyStore(keychain, deleteAll = { keychain.items.clear() }, firstUseInInstall = { firstUse.also { firstUse = false } })

        assertNull(store.getKey("openai"))
        store.setKey("openai", "new")
        assertEquals("new", store.getKey("openai"))
    }
}
