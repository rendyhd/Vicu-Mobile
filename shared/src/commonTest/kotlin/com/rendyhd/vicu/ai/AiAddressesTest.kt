package com.rendyhd.vicu.ai

import com.rendyhd.vicu.ai.provider.LlmClient
import com.rendyhd.vicu.ai.provider.LlmException
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.util.CrossAppFixture
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Typed addresses, what each preset remembers, saved addresses and keys per address (`docs/ai-assistant-v1.md` 2.4, 7.2). */
class AiAddressesTest {
    private class FakeKeys : AiKeyStore {
        val keys = mutableMapOf<String, String>()
        override suspend fun getKey(providerId: String) = keys[providerId]
        override suspend fun setKey(providerId: String, key: String?) {
            if (key.isNullOrBlank()) keys.remove(providerId) else keys[providerId] = key
        }
        override suspend fun clear() = keys.clear()
    }

    private val other = AiContract.provider("other")!!
    private val ollama = AiContract.provider("ollama")!!
    private val openai = AiContract.provider("openai")!!

    @Test
    fun addressVersionCasesOfTheFixture() {
        val fixture = Json.parseToJsonElement(CrossAppFixture.readTestFixture("ai-tools-v1.json")).jsonObject
        val cases = fixture.getValue("addressVersion").jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size >= 10)
        for (case in cases) {
            val address = case.jsonObject.getValue("address").jsonPrimitive.content
            assertEquals(case.jsonObject.getValue("hint").jsonPrimitive.boolean, AiSettings.mayLackVersionPath(address), address)
        }
    }

    @Test
    fun switchingPresetsBringsBackWhatEachWasSetTo() {
        val start = AiSettings().choosingProvider(other).copy(address = "http://box:1234/v1", dictationModel = "llama", askModel = "qwen", askOn = true)
        val away = start.choosingProvider(openai)
        assertEquals("https://api.openai.com/v1", away.address)
        assertFalse(away.askOn)

        val back = away.copy(dictationModel = "gpt-x").choosingProvider(other)
        assertEquals("http://box:1234/v1", back.address)
        assertEquals("llama", back.dictationModel)
        assertEquals("qwen", back.askModel)
        assertFalse(back.askOn)
        assertEquals("gpt-x", back.remembered.getValue("openai").dictationModel)
        assertNull(back.remembered["other"])
    }

    @Test
    fun aFixedPresetAlwaysUsesTheFixtureAddress() {
        val s = AiSettings(remembered = mapOf("openai" to ProviderSetup("https://old.example/v1", "m", "m"))).choosingProvider(openai)
        assertEquals("https://api.openai.com/v1", s.address)
        assertEquals("m", s.dictationModel)
    }

    @Test
    fun aTypedPresetWithNothingRememberedStartsAtItsDefault() {
        assertEquals("http://localhost:11434/v1", AiSettings().choosingProvider(ollama).address)
        assertEquals("", AiSettings().choosingProvider(other).address)
    }

    @Test
    fun savedAddressesAreLatestFirstWithoutDuplicatesAndCapped() {
        var s = AiSettings().choosingProvider(other)
        for (n in 1..7) s = s.copy(address = "http://box$n/v1").savingAddress()
        s = s.copy(address = "http://box5/v1/").savingAddress()
        val addresses = s.saved().map { it.address }
        assertEquals(AiContract.limits.savedAddressesMax, addresses.size)
        assertEquals("http://box5/v1/", addresses.first())
        assertEquals(1, addresses.count { AiSettings.sameAddress(it, "http://box5/v1") })
        assertFalse("http://box1/v1" in addresses)
    }

    @Test
    fun theSavedEntryFollowsModelChanges() {
        val s = AiSettings().choosingProvider(other).copy(address = "http://box/v1", dictationModel = "a", askModel = "a").savingAddress()
        val changed = s.copy(dictationModel = "b").withSavedModels()
        assertEquals("b", changed.saved().single().dictationModel)
        // An address that is not saved changes nothing in the list.
        val elsewhere = changed.copy(address = "http://other/v1", dictationModel = "c").withSavedModels()
        assertEquals("b", elsewhere.saved().single().dictationModel)
    }

    @Test
    fun keysOfTypedPresetsArePerAddress() {
        assertEquals("openai", AiSettings.keyIdFor("openai", "https://api.openai.com/v1"))
        assertEquals("other|http://box/v1", AiSettings.keyIdFor("other", "http://box/v1/"))
    }

    @Test
    fun aKeyStoredPerPresetMovesToTheAddressOnce() = runTest {
        val keys = FakeKeys().apply {
            keys["other"] = "sk-other"
            keys["ollama"] = "sk-ollama"
            keys["openai"] = "sk-openai"
        }
        val store = AiSettingsStore(InMemoryPreferencesDataStore())
        store.update { it.choosingProvider(other).copy(address = "http://box/v1") }
        val providers = AiProviders(LlmClient(MockEngine { respond("{}") }), keys, store)

        assertEquals("sk-other", providers.key(store.current()))
        assertEquals("sk-other", keys.keys["other|http://box/v1"])
        assertEquals("sk-ollama", keys.keys["ollama|http://localhost:11434/v1"])
        assertNull(keys.keys["other"])
        assertEquals("sk-openai", keys.keys["openai"])
        assertTrue(store.current().keysPerAddress)

        // Another address of the same preset does not get that key.
        assertNull(providers.key(store.current().copy(address = "http://elsewhere/v1")))
    }

    @Test
    fun aNotFoundFromATypedAddressWithoutVersionPathSaysSo() = runTest {
        val engine = MockEngine { respond("""{"error":"not found"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json")) }
        val store = AiSettingsStore(InMemoryPreferencesDataStore())
        val providers = AiProviders(LlmClient(engine), FakeKeys(), store)
        val hint = AiContract.message("addressVersionHint")

        val bare = AiSettings().choosingProvider(other).copy(address = "http://box:1234")
        val e = assertFailsWith<LlmException> { providers.listModels(bare) }
        assertTrue(e.message!!.endsWith(hint), e.message)
        assertEquals(hint, providers.test(bare, "m").problems.last())
        assertEquals(1, providers.test(bare, "m").problems.count { it == hint })

        val versioned = bare.copy(address = "http://box:1234/v1")
        val e2 = assertFailsWith<LlmException> { providers.listModels(versioned) }
        assertFalse(e2.message!!.contains(hint))
    }
}
