package com.rendyhd.vicu.ai

/**
 * The key of the user's model provider, kept in the device's secret store next to the Vikunja token
 * (Android: Tink AEAD over DataStore with a Keystore master key; iOS: the Keychain). It never syncs,
 * is never logged and is left out of backups (`docs/ai-assistant-v1.md`, section 7.2).
 */
interface AiKeyStore {
    suspend fun getKey(providerId: String): String?

    /** Stores [key] for [providerId]; null or blank removes it. */
    suspend fun setKey(providerId: String, key: String?)

    /** Removes every stored provider key (wiping local data, signing out of the AI settings). */
    suspend fun clear()
}
