package app.doorprints.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.doorprints.shared.sync.SyncOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SettingsStore] in common code (CMP-4 P4b): the rules it had in `:app`, on an in-memory DataStore, and the stored
 * key names, which an upgraded install needs unchanged (`SettingsUpgradeTest` in `:app` reads a real file written
 * the old way). The API key goes only through the [SecretStore]; the plain key never lands in the settings.
 */
class SettingsStoreTest {

    /** A DataStore held in memory: one transform at a time, as the real one. */
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    /** Seals by reversing, in the entry `sealed`; stands in for the Keystore or the Keychain. */
    private class FakeSecrets : SecretStore {
        val slot = stringPreferencesKey("sealed")
        override fun get(settings: Preferences) = settings[slot]?.removePrefix("s:")?.reversed()
        override fun put(settings: MutablePreferences, apiKey: String) {
            settings[slot] = "s:" + apiKey.reversed()
        }
        override fun clear(settings: MutablePreferences) {
            settings.remove(slot)
        }
    }

    private var clock = 1_000_000L
    private val dataStore = MemoryDataStore()
    private val secrets = FakeSecrets()
    private val store = SettingsStore(dataStore, secrets) { clock }

    private suspend fun raw(): Map<String, Any> = dataStore.data.first().asMap().mapKeys { it.key.name }

    @Test
    fun anEmptyStoreReadsAsTheDefaults() = runTest {
        assertEquals(AppSettings(), store.current())
        assertEquals(ResultMarks(), store.resultMarks.first())
        assertFalse(store.notificationsAsked.first())
        assertEquals(SettingsStore.Cursors(0, 0, 0), store.cursors())
    }

    @Test
    fun theKeyIsKeptOnlyThroughTheSecretStore() = runTest {
        store.saveServer(" https://api.example.com/ ", "  $TEST_KEY  ")
        val saved = store.current()
        assertEquals("https://api.example.com", saved.serverUrl)
        assertEquals(TEST_KEY, saved.apiKey)
        assertEquals(TEST_KEY.takeLast(4), saved.apiKeyHint)
        assertTrue(saved.serverConfigured)
        assertTrue(raw().values.none { it.toString().contains(TEST_KEY) }, "plain key in the settings")
        assertEquals("s:" + TEST_KEY.reversed(), raw()["sealed"])
    }

    @Test
    fun aBlankKeyKeepsTheSavedOneAndClearForgetsIt() = runTest {
        store.saveServer("https://a.example", "first-key-123456")
        store.saveServer("https://a.example", "   ")
        assertEquals("first-key-123456", store.current().apiKey)
        dataStore.edit { secrets.clear(it) }
        assertEquals("", store.current().apiKey)
        assertFalse(store.current().serverConfigured)
    }

    @Test
    fun aNewServerResetsTheCursorsAndANewSetupTheFailureCount() = runTest {
        store.saveServer("https://a.example", "key-aaaaaaaaaaaa")
        store.saveCursors(house = 5, visit = 6)
        store.savePhotoCursor(7)
        store.saveSyncResult(SyncOutcome(SyncOutcome.Kind.AUTH))
        store.saveServer("https://a.example", "")
        assertEquals(SettingsStore.Cursors(5, 6, 7), store.cursors())
        assertEquals(1, store.current().syncFailures)

        store.saveServer("https://a.example", "key-bbbbbbbbbbbb")
        assertEquals(SettingsStore.Cursors(5, 6, 7), store.cursors())
        assertEquals(0, store.current().syncFailures)

        store.saveSyncResult(SyncOutcome(SyncOutcome.Kind.SERVER))
        store.saveServer("https://b.example", "")
        assertEquals(SettingsStore.Cursors(0, 0, 0), store.cursors())
        assertEquals(0, store.current().syncFailures)
        assertEquals("key-bbbbbbbbbbbb", store.current().apiKey)
    }

    @Test
    fun aV01PlaintextKeyIsReadAndThenMovedIntoTheSecretStore() = runTest {
        dataStore.edit { it[stringPreferencesKey("apiKey")] = "legacy-key-12345" }
        assertEquals("legacy-key-12345", store.current().apiKey)
        store.migrateLegacyKey()
        assertNull(raw()["apiKey"])
        assertEquals("s:54321-yek-ycagel", raw()["sealed"])
        assertEquals("legacy-key-12345", store.current().apiKey)
        store.migrateLegacyKey() // safe on every start
        assertEquals("legacy-key-12345", store.current().apiKey)

        dataStore.edit { it[stringPreferencesKey("apiKey")] = " " }
        store.migrateLegacyKey()
        assertNull(raw()["apiKey"])
        assertEquals("legacy-key-12345", store.current().apiKey)
    }

    @Test
    fun syncResultsUseTheClockAndCountFailuresInARow() = runTest {
        store.saveSyncResult(SyncOutcome(SyncOutcome.Kind.AUTH, httpCode = 401))
        val firstFailure = clock
        clock += 60_000
        store.saveSyncResult(SyncOutcome(SyncOutcome.Kind.AUTH, httpCode = 401))
        with(store.current()) {
            assertEquals(2, syncFailures)
            assertEquals(firstFailure, syncFailingSince)
            assertEquals(clock, lastSyncAt)
            assertEquals(0L, lastSyncOkAt)
            assertEquals(SyncOutcome(SyncOutcome.Kind.AUTH, httpCode = 401), lastSync)
        }
        clock += 60_000
        store.saveSyncResult(SyncOutcome(SyncOutcome.Kind.OK, pushed = 1))
        with(store.current()) {
            assertEquals(0, syncFailures)
            assertEquals(0L, syncFailingSince)
            assertEquals(clock, lastSyncOkAt)
        }
    }

    @Test
    fun exportGrantsAndResultMarksKeepTheirRules() = runTest {
        repeat(3) { store.holdExportGrant("content://doc/$it", keep = 2) }
        assertEquals(listOf("content://doc/2", "content://doc/1").joinToString("\n"), raw()["exportGrants"])
        store.dropExportGrant("content://doc/2")
        assertEquals("content://doc/1", raw()["exportGrants"])

        store.markResultTold(ResultScreen.EXPORT, "run-1")
        store.markResultDismissed(ResultScreen.IMPORT, "run-2")
        assertEquals(ResultMarks(exportTold = "run-1", importDismissed = "run-2", importTold = "run-2"),
            store.resultMarks.first())
    }

    @Test
    fun autoBackupClampsKeepAndNeedsAFolder() = runTest {
        store.saveAutoBackupResult(at = 5, error = "folder gone")
        store.saveAutoBackup(enabled = true, folder = "", keep = 50)
        with(store.current()) {
            assertFalse(autoBackup)
            assertEquals(20, autoBackupKeep)
            assertEquals("folder gone", lastAutoBackupError)
        }
        store.saveAutoBackup(enabled = true, folder = "content://tree/a", keep = 0)
        with(store.current()) {
            assertTrue(autoBackup)
            assertEquals(1, autoBackupKeep)
            assertEquals("", lastAutoBackupError)
        }
    }

    /** Stored names (ADR-24): renaming one would lose that setting on every upgraded phone. */
    @Test
    fun theStoredKeyNamesAreUnchanged() = runTest {
        dataStore.edit { it[stringPreferencesKey("apiKey")] = "legacy-key-12345" }
        store.saveServer("https://a.example", "")
        store.saveTracking(alertRadiusM = 50, minStayMinutes = 6)
        store.savePhotosOnWifiOnly(false)
        store.saveSyncResult(SyncOutcome(SyncOutcome.Kind.NETWORK))
        store.saveSyncResult(SyncOutcome(SyncOutcome.Kind.OK))
        store.saveSyncResult(SyncOutcome(SyncOutcome.Kind.NETWORK))
        store.saveCursors(1, 2)
        store.savePhotoCursor(3)
        store.saveAutoBackup(enabled = true, folder = "content://tree/a", keep = 4)
        store.saveAutoBackupResult(at = 9, error = "e")
        store.holdExportGrant("content://doc/1", keep = 5)
        store.markResultDismissed(ResultScreen.EXPORT, "e1")
        store.markResultDismissed(ResultScreen.IMPORT, "i1")
        store.setNotificationsAsked()
        assertEquals(
            setOf(
                "serverUrl", "apiKey", "alertRadius", "minStay", "photosOnWifiOnly", "lastSyncAt", "lastSyncOutcome",
                "syncFailures", "syncFailingSince", "lastSyncOkAt", "houseCursor", "visitCursor", "photoCursor",
                "autoBackup", "autoBackupFolder", "autoBackupKeep", "lastAutoBackupAt", "lastAutoBackupError",
                "exportGrants", "exportDismissedRun", "exportToldRun", "importDismissedRun", "importToldRun",
                "notificationsAsked",
            ),
            raw().keys,
        )
        assertEquals("settings", SettingsStore.FILE_NAME)
    }
}

/** A made-up key, built at run time so no key-like literal is committed (docs/07, secret scan). */
private val TEST_KEY = "not-a-real-key-" + 42
