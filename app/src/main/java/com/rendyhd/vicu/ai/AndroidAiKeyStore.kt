package com.rendyhd.vicu.ai

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64

private val Context.aiKeyDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_keys")

/**
 * Provider keys, encrypted with Tink AEAD (AES-256-GCM) under a keyset wrapped by an Android
 * Keystore master key, the same scheme as the Vikunja token (`AndroidSecureTokenStorage`) but with
 * its own master key, keyset and DataStore file (`datastore/ai_keys.preferences_pb`), so neither
 * store's reset touches the other. The provider id is the associated data, so a value cannot be
 * moved to another provider. The DataStore file and the keyset are left out of backups and device
 * transfer (`backup_rules.xml`, `data_extraction_rules.xml`); a key that can no longer be
 * decrypted (the Keystore was reset) reads as none.
 */
class AndroidAiKeyStore(private val context: Context) : AiKeyStore {

    private val aead: Aead by lazy {
        AeadConfig.register()
        try {
            buildAead()
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "AI keyset unusable, starting a new one", e)
            resetKeystore()
            buildAead()
        }
    }

    override suspend fun getKey(providerId: String): String? = withContext(Dispatchers.IO) {
        val stored = context.aiKeyDataStore.data.first()[keyFor(providerId)] ?: return@withContext null
        try {
            String(aead.decrypt(Base64.getDecoder().decode(stored), providerId.toByteArray()), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Stored AI key unreadable")
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override suspend fun setKey(providerId: String, key: String?) {
        withContext(Dispatchers.IO) {
            val value = key?.trim()?.takeIf { it.isNotEmpty() }
            // Encrypt outside edit {}: a keyset reset must not run inside the DataStore transaction.
            val encrypted = value?.let {
                Base64.getEncoder().encodeToString(aead.encrypt(it.toByteArray(Charsets.UTF_8), providerId.toByteArray()))
            }
            context.aiKeyDataStore.edit { prefs ->
                if (encrypted == null) prefs.remove(keyFor(providerId)) else prefs[keyFor(providerId)] = encrypted
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            context.aiKeyDataStore.edit { it.clear() }
        }
    }

    private fun keyFor(providerId: String) = stringPreferencesKey("key.$providerId")

    private fun buildAead(): Aead =
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://$MASTER_KEY_ALIAS")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)

    private fun resetKeystore() {
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete the AI master key", e)
        }
        context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private companion object {
        const val TAG = "AiKeyStore"
        const val MASTER_KEY_ALIAS = "vicu_ai_master_key"
        const val KEYSET_NAME = "vicu_ai_keyset"
        const val KEYSET_PREFS = "vicu_ai_keyset_prefs"
    }
}
