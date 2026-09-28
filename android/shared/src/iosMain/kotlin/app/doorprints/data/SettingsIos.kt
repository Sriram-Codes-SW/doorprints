package app.doorprints.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecInteractionNotAllowed
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
import platform.darwin.OSStatus

/**
 * The iOS settings (CMP-4 P4b): the same [SettingsStore] on `settings.preferences_pb` in the app's data folder,
 * `Application Support/Doorprints`, which is excluded from backup ([iosDataDirectory]; threat model F-03, SEC-011),
 * with the API key in the Keychain ([KeychainSecretStore]). Nothing calls it until the iOS shell (CMP-8); the simulator
 * tests (iosTest, S4b-BL-26) run the same store through [iosSettingsStore] with a path. Call it once per process.
 */
fun iosSettingsStore(): SettingsStore =
    iosSettingsStore(iosDataDirectory() + "/" + SettingsStore.FILE_NAME + ".preferences_pb", KeychainSecretStore())

/**
 * The iOS settings on the file at [path] with the key in [secrets] (for the simulator tests, S4b-BL-26). [path] must
 * end in `.preferences_pb` and have no other DataStore open on it in the process.
 */
internal fun iosSettingsStore(path: String, secrets: SecretStore): SettingsStore =
    SettingsStore(PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() }), secrets)

/**
 * The iOS [SecretStore]: the API key as a generic-password Keychain item (service `app.doorprints`, account
 * `api_key`), readable after the first unlock and on this device only, so it is left out of backups as Android's
 * Keystore key is. The settings keep only a counter (`apiKeyKeychain`) that each [put] moves on, so a saved key makes
 * the settings emit, and so a Keychain item left over from an earlier install (iOS keeps them across uninstalls) is
 * not read into fresh settings. Errors name the Keychain status only, never the key.
 *
 * S4b-BL-30: [put] updates the item in place and adds it only when it is missing, so a failed write never leaves no
 * key; [get] throws [SecretUnavailableException] while the device is locked (or on any other Keychain error) instead
 * of reading as "no key", and [put] and [clear] throw it too while the device is locked; and [editing] puts the item
 * back as it was when the settings edit around [put] or [clear] fails, so the Keychain and the settings stay in step.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainSecretStore internal constructor(
    private val service: String,
    private val account: String,
    private val keychain: Keychain,
) : SecretStore {

    constructor(service: String = "app.doorprints", account: String = "api_key") :
        this(service, account, SecurityKeychain)

    private val marker = longPreferencesKey("apiKeyKeychain")

    /** One [editing] at a time, so one edit's undo cannot overwrite another edit's key. */
    private val edits = Mutex()

    /** Inside [editing]: the item as it was before this edit's first [put] or [clear]. */
    private var before: Before? = null
    private var inEdit = false

    /**
     * The key the settings' marker points at. [get] does not take the [editing] lock, so while an edit is running a
     * reader may briefly see the new key before that edit fails and puts the old one back.
     */
    override fun get(settings: Preferences): String? {
        if (settings[marker] == null) return null
        val read = keychain.copy(service, account)
        return when (read.status) {
            errSecSuccess -> read.data?.decodeToString() ?: ""
            errSecItemNotFound -> null
            errSecInteractionNotAllowed ->
                throw SecretUnavailableException("Keychain: locked; the key can be read once the device is unlocked")
            else -> throw SecretUnavailableException("Keychain: the API key could not be read (status ${read.status})")
        }
    }

    override fun put(settings: MutablePreferences, apiKey: String) {
        rememberBefore()
        write(apiKey.encodeToByteArray())
        settings[marker] = (settings[marker] ?: 0L) + 1
    }

    override fun clear(settings: MutablePreferences) {
        rememberBefore()
        delete()
        settings.remove(marker)
    }

    /**
     * Runs [block] and, when it throws, puts the Keychain item back as it was before the edit's first [put] or [clear].
     * The block runs under [NonCancellable], so cancelling the caller cannot stop the edit half way between the
     * Keychain and the settings. A [CancellationException] that still comes out of [block] (DataStore can throw one
     * after it has committed the write) does not undo the Keychain change: the settings may already hold the new
     * marker, and undoing would leave them pointing at the old key. A cancelled caller sees the cancellation once the
     * edit has finished.
     */
    override suspend fun <T> editing(block: suspend () -> T): T = edits.withLock {
        withContext(NonCancellable) { editUnderLock(block) }
    }

    private suspend fun <T> editUnderLock(block: suspend () -> T): T {
        inEdit = true
        before = null
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            before?.let { old ->
                try {
                    if (old.data == null) delete() else write(old.data)
                } catch (undo: Throwable) {
                    e.addSuppressed(undo)
                }
            }
            throw e
        } finally {
            inEdit = false
            before = null
        }
    }

    /** Records the item as it is now, once per [editing], before the first change. */
    private fun rememberBefore() {
        if (!inEdit || before != null) return
        val read = keychain.copy(service, account)
        if (read.status == errSecInteractionNotAllowed) {
            throw SecretUnavailableException("Keychain: locked; the key can be changed once the device is unlocked")
        }
        check(read.status == errSecSuccess || read.status == errSecItemNotFound) {
            "Keychain: the API key could not be read before the change (status ${read.status})"
        }
        before = Before(if (read.status == errSecSuccess) read.data ?: ByteArray(0) else null)
    }

    /** Updates the item, or adds it when there is none (never delete-then-add, which could leave no key). */
    private fun write(data: ByteArray) {
        var status = keychain.update(service, account, data)
        if (status == errSecItemNotFound) status = keychain.add(service, account, data)
        if (status == errSecInteractionNotAllowed) {
            throw SecretUnavailableException("Keychain: locked; the key can be saved once the device is unlocked")
        }
        check(status == errSecSuccess) { "Keychain: the API key was not saved (status $status)" }
    }

    /** The item's [data] before a change; null when there was no item. */
    private class Before(val data: ByteArray?)

    private fun delete() {
        val status = keychain.delete(service, account)
        check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain: delete failed (status $status)" }
    }
}

/** What [Keychain.copy] found: the Security framework's [status] and, on `errSecSuccess`, the item's [data]. */
internal class KeychainRead(val status: OSStatus, val data: ByteArray?)

/**
 * The four Keychain calls [KeychainSecretStore] makes, on one generic-password item named by service and account
 * (S4b-BL-30). [SecurityKeychain] is the real one; the iosTests put a fake in its place. Each returns the Security
 * framework's status unchanged.
 */
internal interface Keychain {
    /** `SecItemCopyMatching`: the item's data. */
    fun copy(service: String, account: String): KeychainRead

    /** `SecItemAdd`: a new item, readable after the first unlock, this device only. */
    fun add(service: String, account: String, data: ByteArray): OSStatus

    /** `SecItemUpdate`: new data for an existing item (`errSecItemNotFound` when there is none). */
    fun update(service: String, account: String, data: ByteArray): OSStatus

    /** `SecItemDelete`. */
    fun delete(service: String, account: String): OSStatus
}

/** [Keychain] on the Security framework. */
@OptIn(ExperimentalForeignApi::class)
internal object SecurityKeychain : Keychain {

    override fun copy(service: String, account: String): KeychainRead = withQuery(
        service, account,
        kSecReturnData to kCFBooleanTrue,
        kSecMatchLimit to kSecMatchLimitOne,
    ) { query ->
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status != errSecSuccess) return@memScoped KeychainRead(status, null)
            val data = CFBridgingRelease(result.value) as? NSData
            val bytes = data?.bytes
            KeychainRead(
                status,
                if (data == null || bytes == null) ByteArray(0)
                else bytes.reinterpret<ByteVar>().readBytes(data.length.toInt()),
            )
        }
    }

    override fun add(service: String, account: String, data: ByteArray): OSStatus = withQuery(
        service, account,
        kSecValueData to CFBridgingRetain(data.toNSData()),
        kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        release = 1,
    ) { query -> SecItemAdd(query, null) }

    override fun update(service: String, account: String, data: ByteArray): OSStatus =
        withQuery(service, account) { query ->
            withDictionary(
                kSecValueData to CFBridgingRetain(data.toNSData()),
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                release = 1,
            ) { changes ->
                SecItemUpdate(query, changes)
            }
        }

    override fun delete(service: String, account: String): OSStatus =
        withQuery(service, account) { query -> SecItemDelete(query) }

    private fun ByteArray.toNSData(): NSData =
        usePinned { NSData.dataWithBytes(if (isEmpty()) null else it.addressOf(0), size.toULong()) }

    /** Runs [block] with a query for the item plus [extra]; see [withDictionary] for [release]. */
    private fun <T> withQuery(
        service: String,
        account: String,
        vararg extra: Pair<CFTypeRef?, CFTypeRef?>,
        release: Int = 0,
        block: (CFMutableDictionaryRef?) -> T,
    ): T {
        val serviceRef = CFBridgingRetain(service)
        val accountRef = CFBridgingRetain(account)
        try {
            return withDictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to serviceRef,
                kSecAttrAccount to accountRef,
                *extra,
                release = 0,
                block = block,
            )
        } finally {
            CFRelease(serviceRef)
            CFRelease(accountRef)
            extra.take(release).forEach { CFRelease(it.second) }
        }
    }

    /**
     * Runs [block] with a dictionary of [entries]. The first [release] values of [entries] were retained by the
     * caller (`CFBridgingRetain`) and are released here, after the dictionary, which retains what it holds.
     */
    private fun <T> withDictionary(
        vararg entries: Pair<CFTypeRef?, CFTypeRef?>,
        release: Int,
        block: (CFMutableDictionaryRef?) -> T,
    ): T {
        val dictionary =
            CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            entries.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
            return block(dictionary)
        } finally {
            CFRelease(dictionary)
            entries.take(release).forEach { CFRelease(it.second) }
        }
    }
}
