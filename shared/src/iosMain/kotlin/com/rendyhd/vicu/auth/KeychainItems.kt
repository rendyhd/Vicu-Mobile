package com.rendyhd.vicu.auth

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** What the Keychain answered for one item. */
internal sealed interface KeychainValue {
    data class Found(val value: String) : KeychainValue

    data object Missing : KeychainValue

    /**
     * No answer: the device has not been unlocked since it started, or the app has no Keychain
     * access. Nothing can be concluded from it, so nothing is deleted or written over because of it.
     */
    data object Unavailable : KeychainValue
}

/** The app's own string items in the Keychain, one per account name; replaced in tests. */
internal interface KeychainItems {
    fun read(account: String): KeychainValue

    /** Replaces the item; false when the Keychain refused it. */
    fun write(account: String, value: String): Boolean

    fun delete(account: String)
}

/**
 * Generic passwords of [service], readable after the first unlock (so background refresh can use
 * them) and never moved to another device, not even through a backup.
 */
internal class SystemKeychainItems(private val service: String) : KeychainItems {

    /**
     * Runs [block] with a Keychain query for [account] plus [extra]. Values are CF objects
     * (constants such as kSecClassGenericPassword) or Objective-C objects (a String, NSData),
     * which are bridged for the call and released after it.
     */
    private inline fun <T> withQuery(
        account: String,
        vararg extra: Pair<CFStringRef?, Any?>,
        block: (CFDictionaryRef?) -> T,
    ): T {
        val pairs = listOf<Pair<CFStringRef?, Any?>>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service,
            kSecAttrAccount to account,
        ) + extra
        val dict = CFDictionaryCreateMutable(
            null, pairs.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        val bridged = mutableListOf<CFTypeRef?>()
        for ((key, value) in pairs) {
            val cfValue: CFTypeRef? = when (value) {
                null -> null
                is CPointer<*> -> value.reinterpret()
                else -> CFBridgingRetain(value).also { bridged += it }
            }
            CFDictionaryAddValue(dict, key, cfValue)
        }
        try {
            return block(dict)
        } finally {
            bridged.forEach { CFRelease(it) }
            CFRelease(dict)
        }
    }

    override fun read(account: String): KeychainValue =
        withQuery(account, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) { query ->
            memScoped {
                val result = alloc<CFTypeRefVar>()
                when (SecItemCopyMatching(query, result.ptr)) {
                    errSecSuccess -> {
                        val data = CFBridgingRelease(result.value) as? NSData
                        data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }
                            ?.let { KeychainValue.Found(it) }
                            ?: KeychainValue.Missing
                    }
                    errSecItemNotFound -> KeychainValue.Missing
                    else -> KeychainValue.Unavailable
                }
            }
        }

    override fun write(account: String, value: String): Boolean {
        delete(account)
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
        return withQuery(
            account,
            kSecValueData to data,
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ) { query -> SecItemAdd(query, null) == errSecSuccess }
    }

    override fun delete(account: String) {
        withQuery(account) { query -> SecItemDelete(query) }
    }

    /** Deletes every item of [service]. */
    fun deleteAll() {
        val dict = CFDictionaryCreateMutable(null, 2, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val serviceName = CFBridgingRetain(service)
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, serviceName)
        try {
            SecItemDelete(dict)
        } finally {
            CFRelease(serviceName)
            CFRelease(dict)
        }
    }
}
