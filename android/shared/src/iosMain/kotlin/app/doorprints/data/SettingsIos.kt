package app.doorprints.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import okio.Path.Companion.toPath
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
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

/**
 * The iOS settings (CMP-4 P4b): the same [SettingsStore] on `settings.preferences_pb` in the app's Application Support
 * folder, with the API key in the Keychain ([KeychainSecretStore]). Compile-only until the iOS shell (CMP-8); nothing
 * calls it yet, and no test runs it on a simulator (docs/10 S4b-BL-26). Call it once per process.
 */
@OptIn(ExperimentalForeignApi::class)
fun iosSettingsStore(): SettingsStore {
    val support = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    val path = requireNotNull(support?.path) { "No Application Support folder" } +
        "/" + SettingsStore.FILE_NAME + ".preferences_pb"
    val dataStore = PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })
    return SettingsStore(dataStore, KeychainSecretStore())
}

/**
 * The iOS [SecretStore]: the API key as a generic-password Keychain item (service `app.doorprints`, account
 * `api_key`), readable after the first unlock and on this device only, so it is left out of backups as Android's
 * Keystore key is. The settings keep only a counter (`apiKeyKeychain`) that each [put] moves on, so a saved key makes
 * the settings emit, and so a Keychain item left over from an earlier install (iOS keeps them across uninstalls) is
 * not read into fresh settings. Errors name the Keychain status only, never the key.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainSecretStore(
    private val service: String = "app.doorprints",
    private val account: String = "api_key",
) : SecretStore {

    private val marker = longPreferencesKey("apiKeyKeychain")

    override fun get(settings: Preferences): String? {
        if (settings[marker] == null) return null
        return withQuery(
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        ) { query ->
            memScoped {
                val result = alloc<CFTypeRefVar>()
                if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped null
                val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
                val bytes = data.bytes ?: return@memScoped ""
                bytes.reinterpret<kotlinx.cinterop.ByteVar>().readBytes(data.length.toInt()).decodeToString()
            }
        }
    }

    override fun put(settings: MutablePreferences, apiKey: String) {
        delete()
        val bytes = apiKey.encodeToByteArray()
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        val status = withQuery(
            kSecValueData to CFBridgingRetain(data),
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            release = 1,
        ) { query -> SecItemAdd(query, null) }
        check(status == errSecSuccess) { "Keychain: the API key was not saved (status $status)" }
        settings[marker] = (settings[marker] ?: 0L) + 1
    }

    override fun clear(settings: MutablePreferences) {
        delete()
        settings.remove(marker)
    }

    private fun delete() {
        val status = withQuery { query -> SecItemDelete(query) }
        check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain: delete failed (status $status)" }
    }

    /**
     * Runs [block] with a query for this item plus [extra]. The first [release] values of [extra] were retained by the
     * caller (`CFBridgingRetain`) and are released here, after the dictionary, which retains what it holds.
     */
    private fun <T> withQuery(
        vararg extra: Pair<CFTypeRef?, CFTypeRef?>,
        release: Int = 0,
        block: (CFMutableDictionaryRef?) -> T,
    ): T {
        val serviceRef = CFBridgingRetain(service)
        val accountRef = CFBridgingRetain(account)
        val query =
            CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, serviceRef)
            CFDictionaryAddValue(query, kSecAttrAccount, accountRef)
            extra.forEach { (key, value) -> CFDictionaryAddValue(query, key, value) }
            return block(query)
        } finally {
            CFRelease(query)
            CFRelease(serviceRef)
            CFRelease(accountRef)
            extra.take(release).forEach { CFRelease(it.second) }
        }
    }
}
