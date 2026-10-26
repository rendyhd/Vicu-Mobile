package com.rendyhd.vicu.ai

import com.rendyhd.vicu.auth.KeychainItems
import com.rendyhd.vicu.auth.KeychainValue
import com.rendyhd.vicu.auth.SystemKeychainItems
import com.rendyhd.vicu.util.IosPaths
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock

/**
 * Provider keys in the Keychain, as generic passwords of their own service next to the Vikunja
 * token's: readable after the first unlock, never moved to another device (not even in a backup).
 *
 * Keychain items outlive an uninstall, and section 7.2 says uninstalling removes the key: the first
 * use in an install (no marker in Application Support, which an uninstall deletes) deletes what an
 * earlier install left. Nothing is touched until then (CLAUDE.md, gotcha 19).
 */
class IosAiKeyStore internal constructor(
    private val keychain: KeychainItems,
    private val deleteAll: () -> Unit,
    /** True the first time it is asked in this install; marks the install as it answers. */
    private val firstUseInInstall: () -> Boolean,
) : AiKeyStore {

    constructor() : this(
        keychain = SystemKeychainItems(SERVICE_NAME),
        deleteAll = { SystemKeychainItems(SERVICE_NAME).deleteAll() },
        firstUseInInstall = ::markInstall,
    )

    private val lock = NSLock()
    private var checked = false

    override suspend fun getKey(providerId: String): String? = locked {
        when (val value = keychain.read(account(providerId))) {
            is KeychainValue.Found -> value.value
            else -> null
        }
    }

    override suspend fun setKey(providerId: String, key: String?) {
        locked {
            val trimmed = key?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmed == null) keychain.delete(account(providerId)) else keychain.write(account(providerId), trimmed)
        }
    }

    override suspend fun clear() {
        locked { deleteAll() }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            if (!checked) {
                checked = true
                if (firstUseInInstall()) deleteAll()
            }
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun account(providerId: String) = "key.$providerId"

    private companion object {
        const val SERVICE_NAME = "com.rendyhd.vicu.ai"

        fun markInstall(): Boolean {
            val marker = IosPaths.excludedSubdirectory(IosPaths.applicationSupport, "ai") + "/keys-installed"
            val files = NSFileManager.defaultManager
            if (files.fileExistsAtPath(marker)) return false
            files.createFileAtPath(marker, contents = null, attributes = null)
            return true
        }
    }
}
