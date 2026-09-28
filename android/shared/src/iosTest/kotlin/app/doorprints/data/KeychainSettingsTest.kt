package app.doorprints.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.darwin.OSStatus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS settings on a simulator (S4b-BL-26, S4b-BL-30): [SettingsStore] on a real settings file with
 * [KeychainSecretStore] in front of a fake [Keychain], so each Keychain status can be set; and one round trip on the
 * real Keychain ([realKeychainRoundTrip]), skipped where the test app has no Keychain access.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainSettingsTest {

    /**
     * One generic-password item per service and account, held in memory; [copyStatus], [addStatus] and [updateStatus]
     * force errors.
     */
    private class FakeKeychain : Keychain {
        val items = mutableMapOf<Pair<String, String>, ByteArray>()
        val calls = mutableListOf<String>()
        var copyStatus: OSStatus? = null
        var addStatus: OSStatus? = null
        var updateStatus: OSStatus? = null

        override fun copy(service: String, account: String): KeychainRead {
            calls += "copy"
            copyStatus?.let { return KeychainRead(it, null) }
            val data = items[service to account] ?: return KeychainRead(errSecItemNotFound, null)
            return KeychainRead(errSecSuccess, data.copyOf())
        }

        override fun add(service: String, account: String, data: ByteArray): OSStatus {
            calls += "add"
            addStatus?.let { return it }
            if (service to account in items) return ERR_SEC_DUPLICATE_ITEM
            items[service to account] = data.copyOf()
            return errSecSuccess
        }

        override fun update(service: String, account: String, data: ByteArray): OSStatus {
            calls += "update"
            updateStatus?.let { return it }
            if (service to account !in items) return errSecItemNotFound
            items[service to account] = data.copyOf()
            return errSecSuccess
        }

        override fun delete(service: String, account: String): OSStatus {
            calls += "delete"
            return if (items.remove(service to account) != null) errSecSuccess else errSecItemNotFound
        }

        fun key(): String? = items[SERVICE to ACCOUNT]?.decodeToString()
    }

    /** A DataStore on a real file whose writes can be made to fail after the edit has run, as a full disk would. */
    private class FlakyDataStore(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        var failWrites = false
        override val data = real.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (!failWrites) return real.updateData(transform)
            transform(real.data.first())
            throw IllegalStateException("the settings file could not be written (test)")
        }
    }

    /** A DataStore on a real file that commits each write and then throws [CancellationException], as DataStore may. */
    private class CancelAfterCommitDataStore(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        override val data = real.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            real.updateData(transform)
            throw CancellationException("cancelled after the settings were written (test)")
        }
    }

    private val files = mutableListOf<String>()

    private fun newPath(): String =
        (NSTemporaryDirectory() + "settings-" + NSUUID().UUIDString + ".preferences_pb").also { files += it }

    private fun flaky(): FlakyDataStore {
        val path = newPath()
        return FlakyDataStore(PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() }))
    }

    @AfterTest
    fun removeFiles() {
        files.forEach { NSFileManager.defaultManager.removeItemAtPath(it, null) }
    }

    private val keychain = FakeKeychain()
    private val secrets = KeychainSecretStore(SERVICE, ACCOUNT, keychain)

    @Test
    fun aKeyIsSavedReadAndClearedThroughTheKeychain() = runTest {
        val store = iosSettingsStore(newPath(), secrets)
        assertEquals("", store.current().apiKey)

        store.saveServer("https://a.example/", "  $KEY_1  ")
        assertEquals("https://a.example", store.current().serverUrl)
        assertEquals(KEY_1, store.current().apiKey)
        assertEquals(KEY_1, keychain.key())

        // A blank key keeps the saved one.
        store.saveServer("https://b.example", "")
        assertEquals(KEY_1, store.current().apiKey)

        val dataStore = flaky()
        val cleared = SettingsStore(dataStore, secrets)
        cleared.saveServer("https://a.example", KEY_1)
        secrets.editing { dataStore.edit { secrets.clear(it) } }
        assertEquals("", cleared.current().apiKey)
        assertNull(keychain.key())
    }

    @Test
    fun aKeychainItemFromAnEarlierInstallIsNotReadIntoFreshSettings() = runTest {
        keychain.items[SERVICE to ACCOUNT] = "left-from-an-earlier-install".encodeToByteArray()
        val store = iosSettingsStore(newPath(), secrets)
        assertEquals("", store.current().apiKey, "a stale Keychain item was read into fresh settings")
        assertFalse(store.current().serverConfigured)

        store.saveServer("https://a.example", KEY_1)
        assertEquals(KEY_1, store.current().apiKey)
    }

    @Test
    fun putUpdatesTheItemAndAddsItOnlyWhenItIsMissing() = runTest {
        val store = iosSettingsStore(newPath(), secrets)
        store.saveServer("https://a.example", KEY_1)
        // The item as it was (for the undo), an update that finds none, then the add.
        assertEquals(listOf("copy", "update", "add"), keychain.calls)
        keychain.calls.clear()

        store.saveServer("https://a.example", KEY_2)
        assertTrue("update" in keychain.calls)
        assertFalse("add" in keychain.calls, "an existing item must be updated, not added")
        assertFalse("delete" in keychain.calls, "put must never delete the saved key")
        assertEquals(KEY_2, store.current().apiKey)
    }

    @Test
    fun aFailedAddLeavesNoMarkerAndNoKey() = runTest {
        val store = iosSettingsStore(newPath(), secrets)
        keychain.addStatus = -1
        assertFailsWith<IllegalStateException> { store.saveServer("https://a.example", KEY_1) }
        assertEquals("", store.current().apiKey)
        assertEquals("", store.current().serverUrl)
    }

    @Test
    fun aLockedKeychainIsToldApartFromAMissingKey() = runTest {
        val store = iosSettingsStore(newPath(), secrets)
        store.saveServer("https://a.example", KEY_1)

        keychain.copyStatus = errSecInteractionNotAllowed
        val locked = assertFailsWith<SecretUnavailableException> { store.current() }
        assertFalse(locked.message.orEmpty().contains(KEY_1), "the error names the key")

        keychain.copyStatus = null
        keychain.items.clear()
        assertEquals("", store.current().apiKey, "a missing item reads as no key")
    }

    @Test
    fun anyOtherKeychainErrorIsUnavailableAndDoesNotNameTheKey() = runTest {
        val store = iosSettingsStore(newPath(), secrets)
        store.saveServer("https://a.example", KEY_1)

        keychain.copyStatus = ERR_SEC_NOT_AVAILABLE
        val error = assertFailsWith<SecretUnavailableException> { store.current() }
        assertTrue(error.message.orEmpty().contains("$ERR_SEC_NOT_AVAILABLE"), "the error names the status")
        assertFalse(error.message.orEmpty().contains(KEY_1), "the error names the key")
    }

    @Test
    fun aLockedKeychainStopsASaveAsUnavailable() = runTest {
        val store = iosSettingsStore(newPath(), secrets)
        store.saveServer("https://a.example", KEY_1)

        // Locked when the item is read before the change.
        keychain.copyStatus = errSecInteractionNotAllowed
        val beforeLocked = assertFailsWith<SecretUnavailableException> { store.saveServer("https://b.example", KEY_2) }
        assertTrue(beforeLocked.message.orEmpty().startsWith("Keychain: locked;"))
        assertFalse(beforeLocked.message.orEmpty().contains(KEY_2), "the error names the key")
        keychain.copyStatus = null
        assertEquals(KEY_1, keychain.key())
        assertEquals("https://a.example", store.current().serverUrl)

        // Locked when the item is written.
        keychain.updateStatus = errSecInteractionNotAllowed
        val writeLocked = assertFailsWith<SecretUnavailableException> { store.saveServer("https://b.example", KEY_2) }
        assertTrue(writeLocked.message.orEmpty().startsWith("Keychain: locked;"))
        assertFalse(writeLocked.message.orEmpty().contains(KEY_2), "the error names the key")
        keychain.updateStatus = null
        assertEquals(KEY_1, keychain.key())
        assertEquals(KEY_1, store.current().apiKey)
        assertEquals("https://a.example", store.current().serverUrl)
    }

    @Test
    fun aCancellationAfterTheSettingsCommittedKeepsTheNewKey() = runTest {
        val path = newPath()
        val real = PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })
        SettingsStore(real, secrets).saveServer("https://a.example", KEY_1)

        val store = SettingsStore(CancelAfterCommitDataStore(real), secrets)
        assertFailsWith<CancellationException> { store.saveServer("https://a.example", KEY_2) }
        assertEquals(KEY_2, keychain.key(), "the Keychain was rolled back under settings that hold the new key")
        assertEquals(KEY_2, store.current().apiKey)
    }

    @Test
    fun concurrentSavesLeaveTheKeychainAndTheSettingsInStep() = runTest {
        val store = iosSettingsStore(newPath(), secrets)
        coroutineScope {
            launch { store.saveServer("https://a.example", KEY_1) }
            launch { store.saveServer("https://a.example", KEY_2) }
        }
        val saved = store.current().apiKey
        assertTrue(saved == KEY_1 || saved == KEY_2, "neither key was saved")
        assertEquals(saved, keychain.key(), "the Keychain and the settings disagree")
    }

    @Test
    fun aFailedSettingsWritePutsTheOldKeyBack() = runTest {
        val dataStore = flaky()
        val store = SettingsStore(dataStore, secrets)
        store.saveServer("https://a.example", KEY_1)

        dataStore.failWrites = true
        assertFailsWith<IllegalStateException> { store.saveServer("https://a.example", KEY_2) }
        dataStore.failWrites = false
        assertEquals(KEY_1, keychain.key(), "the Keychain kept a key the settings never saved")
        assertEquals(KEY_1, store.current().apiKey)
    }

    @Test
    fun aFailedFirstSaveLeavesNoKeychainItem() = runTest {
        val dataStore = flaky()
        val store = SettingsStore(dataStore, secrets)
        dataStore.failWrites = true
        assertFailsWith<IllegalStateException> { store.saveServer("https://a.example", KEY_1) }
        dataStore.failWrites = false
        assertNull(keychain.key())
        assertEquals("", store.current().apiKey)
    }

    @Test
    fun aFailedClearPutsTheKeyBack() = runTest {
        val dataStore = flaky()
        val store = SettingsStore(dataStore, secrets)
        store.saveServer("https://a.example", KEY_1)

        dataStore.failWrites = true
        assertFailsWith<IllegalStateException> { secrets.editing { dataStore.edit { secrets.clear(it) } } }
        dataStore.failWrites = false
        assertContentEquals(KEY_1.encodeToByteArray(), keychain.items[SERVICE to ACCOUNT])
        assertEquals(KEY_1, store.current().apiKey)
    }

    /**
     * Save, read and clear on the real Keychain, under a service of its own. An unsigned simulator test binary may have
     * no Keychain access (`errSecMissingEntitlement`), and KGP runs it with `simctl spawn --standalone` on a device that
     * is not booted, where the Keychain service may be missing (`errSecNotAvailable`); on either the test is skipped,
     * with a line naming the status in the results, not failed. Any other status fails.
     */
    @Test
    fun realKeychainRoundTrip() = runTest {
        val service = "app.doorprints.test." + NSUUID().UUIDString
        val probe = SecurityKeychain.add(service, "probe", byteArrayOf(1))
        if (probe == ERR_SEC_MISSING_ENTITLEMENT || probe == ERR_SEC_NOT_AVAILABLE) {
            println(
                "SKIPPED realKeychainRoundTrip: no Keychain for this test binary (status $probe; -34018 " +
                    "errSecMissingEntitlement, -25291 errSecNotAvailable); the fake-Keychain tests above still ran",
            )
            return@runTest
        }
        assertEquals(errSecSuccess, probe, "Keychain probe")
        SecurityKeychain.delete(service, "probe")
        val real = KeychainSecretStore(service, ACCOUNT)
        try {
            val dataStore = flaky()
            val store = SettingsStore(dataStore, real)
            store.saveServer("https://a.example", KEY_1)
            assertEquals(KEY_1, store.current().apiKey)
            store.saveServer("https://a.example", KEY_2)
            assertEquals(KEY_2, store.current().apiKey)
            real.editing { dataStore.edit { real.clear(it) } }
            assertEquals("", store.current().apiKey)
            assertEquals(errSecItemNotFound, SecurityKeychain.copy(service, ACCOUNT).status)
        } finally {
            SecurityKeychain.delete(service, ACCOUNT)
        }
    }

    private companion object {
        const val SERVICE = "app.doorprints.test"
        const val ACCOUNT = "api_key"
        const val KEY_1 = "test-key-one"
        const val KEY_2 = "test-key-two"
        const val ERR_SEC_DUPLICATE_ITEM: OSStatus = -25299
        const val ERR_SEC_MISSING_ENTITLEMENT: OSStatus = -34018
        const val ERR_SEC_NOT_AVAILABLE: OSStatus = -25291
    }
}
