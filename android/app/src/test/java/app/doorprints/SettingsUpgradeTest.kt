package app.doorprints

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.doorprints.data.AppSettings
import app.doorprints.data.KeystoreSecretStore
import app.doorprints.data.ResultMarks
import app.doorprints.data.SettingsStore
import app.doorprints.data.openSettingsDataStore
import app.doorprints.data.settingsDataStoreFile
import app.doorprints.shared.sync.SyncOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Settings saved before CMP-4 P4b are read after it, and the other way round (ADR-23 P4b, ADR-24 stored names).
 *
 * The old code opened its store with the property delegate `preferencesDataStore("settings")`, which runs
 * `PreferenceDataStoreFactory.create(scope) { applicationContext.preferencesDataStoreFile("settings") }`. [oldStore]
 * makes that same call with a scope the test can end (a delegate's store stays open for the process, and DataStore
 * refuses two open stores on one file), and writes with the old code's key names, spelled out here. The new side is
 * the app's own [openSettingsDataStore] and [SettingsStore], with [KeystoreSecretStore] around a stand-in cipher:
 * Robolectric has no Android Keystore, and what matters here is the entry the sealed key is kept in (`apiKeyEnc`).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would open the settings itself and start MapLibre (native code) and WorkManager.
@Config(sdk = [35], application = Application::class)
class SettingsUpgradeTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    // Stand-in for ApiKeyCipher: the same "v1:" prefix, and null for anything it did not seal.
    private val seal: (String) -> String = { "v1:" + it.reversed() }
    private val open: (String?) -> String? = { s ->
        s?.takeIf { it.startsWith("v1:") }?.removePrefix("v1:")?.reversed()
    }

    private fun <T> withOldStore(block: suspend (DataStore<Preferences>) -> T): T = runBlocking {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            context.applicationContext.preferencesDataStoreFile("settings")
        }
        try {
            block(store)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun <T> withNewStore(block: suspend (SettingsStore) -> T): T = runBlocking {
        val job = SupervisorJob()
        val dataStore = openSettingsDataStore(context, CoroutineScope(Dispatchers.IO + job))
        try {
            block(SettingsStore(dataStore, KeystoreSecretStore(seal, open)))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Before
    @After
    fun cleanUp() {
        File(context.filesDir, "datastore").deleteRecursively()
    }

    @Test
    fun theFileIsTheOneTheOldDelegateUsed() {
        assertEquals(File(context.filesDir, "datastore/settings.preferences_pb"), settingsDataStoreFile(context))
        assertEquals(context.preferencesDataStoreFile("settings"), settingsDataStoreFile(context))
    }

    @Test
    fun settingsSavedBeforeP4bAreReadAfterIt() {
        val outcome = SyncOutcome(SyncOutcome.Kind.AUTH, httpCode = 401)
        withOldStore { old ->
            old.edit {
                it[stringPreferencesKey("serverUrl")] = "https://api.example.com"
                it[stringPreferencesKey("apiKeyEnc")] = seal(TEST_KEY)
                it[intPreferencesKey("alertRadius")] = 45
                it[intPreferencesKey("minStay")] = 7
                it[booleanPreferencesKey("photosOnWifiOnly")] = false
                it[longPreferencesKey("lastSyncAt")] = 1_000L
                it[stringPreferencesKey("lastSyncOutcome")] = outcome.encode()
                it[intPreferencesKey("syncFailures")] = 3
                it[longPreferencesKey("syncFailingSince")] = 900L
                it[longPreferencesKey("lastSyncOkAt")] = 800L
                it[longPreferencesKey("houseCursor")] = 11L
                it[longPreferencesKey("visitCursor")] = 12L
                it[longPreferencesKey("photoCursor")] = 13L
                it[booleanPreferencesKey("autoBackup")] = true
                it[stringPreferencesKey("autoBackupFolder")] = "content://tree/backups"
                it[intPreferencesKey("autoBackupKeep")] = 8
                it[longPreferencesKey("lastAutoBackupAt")] = 700L
                it[stringPreferencesKey("lastAutoBackupError")] = "FOLDER_GONE"
                it[stringPreferencesKey("exportGrants")] = "content://doc/2\ncontent://doc/1"
                it[stringPreferencesKey("exportDismissedRun")] = "e1"
                it[stringPreferencesKey("exportToldRun")] = "e2"
                it[stringPreferencesKey("importDismissedRun")] = "i1"
                it[stringPreferencesKey("importToldRun")] = "i2"
                it[booleanPreferencesKey("notificationsAsked")] = true
            }
        }
        assertTrue(File(context.filesDir, "datastore/settings.preferences_pb").isFile)

        withNewStore { store ->
            assertEquals(
                AppSettings(
                    serverUrl = "https://api.example.com",
                    apiKey = TEST_KEY,
                    alertRadiusM = 45,
                    minStayMinutes = 7,
                    photosOnWifiOnly = false,
                    lastSyncAt = 1_000L,
                    lastSync = outcome,
                    syncFailures = 3,
                    syncFailingSince = 900L,
                    lastSyncOkAt = 800L,
                    autoBackup = true,
                    autoBackupFolder = "content://tree/backups",
                    autoBackupKeep = 8,
                    lastAutoBackupAt = 700L,
                    lastAutoBackupError = "FOLDER_GONE",
                ),
                store.current(),
            )
            assertEquals(SettingsStore.Cursors(11L, 12L, 13L), store.cursors())
            assertEquals(ResultMarks("e1", "e2", "i1", "i2"), store.resultMarks.first())
            assertTrue(store.notificationsAsked.first())
            // The held grants are read too: a third grant with keep = 2 releases the oldest stored one.
            assertEquals(listOf("content://doc/1"), store.holdExportGrant("content://doc/3", keep = 2))
        }
    }

    @Test
    fun aV01PlaintextKeyIsStillReadAndMovedIntoTheKeystoreEntry() {
        withOldStore { old -> old.edit { it[stringPreferencesKey("apiKey")] = "legacy-key-12345" } }
        withNewStore { store ->
            assertEquals("legacy-key-12345", store.current().apiKey)
            store.migrateLegacyKey()
            assertEquals("legacy-key-12345", store.current().apiKey)
        }
        withOldStore { old ->
            val p = old.data.first()
            assertNull(p[stringPreferencesKey("apiKey")])
            assertEquals(seal("legacy-key-12345"), p[stringPreferencesKey("apiKeyEnc")])
        }
    }

    @Test
    fun settingsSavedAfterP4bAreWhereTheOldCodeLooked() {
        withNewStore { store ->
            store.saveServer("https://api.example.com/", TEST_KEY)
            store.saveTracking(alertRadiusM = 60, minStayMinutes = 9)
            store.saveCursors(house = 4, visit = 5)
        }
        withOldStore { old ->
            val p = old.data.first()
            assertEquals("https://api.example.com", p[stringPreferencesKey("serverUrl")])
            assertEquals(seal(TEST_KEY), p[stringPreferencesKey("apiKeyEnc")])
            assertNull(p[stringPreferencesKey("apiKey")])
            assertEquals(60, p[intPreferencesKey("alertRadius")])
            assertEquals(9, p[intPreferencesKey("minStay")])
            assertEquals(4L, p[longPreferencesKey("houseCursor")])
            assertEquals(5L, p[longPreferencesKey("visitCursor")])
            assertEquals(0L, p[longPreferencesKey("photoCursor")])
            // The plain key is in no entry of the file.
            assertFalse(p.asMap().values.any { it.toString().contains(TEST_KEY) })
        }
    }

    @Test
    fun theKeystoreStoreClearsItsEntryAndAnUnreadableKeyReadsAsNone() {
        withOldStore { old -> old.edit { it[stringPreferencesKey("apiKeyEnc")] = "v1:not-sealed-on-this-device" } }
        runBlocking {
            val job = SupervisorJob()
            val dataStore = openSettingsDataStore(context, CoroutineScope(Dispatchers.IO + job))
            // As ApiKeyCipher.decrypt does for a restored copy whose Keystore key stayed on the old phone.
            val unreadable = KeystoreSecretStore(seal) { null }
            try {
                val store = SettingsStore(dataStore, unreadable)
                assertEquals("", store.current().apiKey)
                dataStore.edit { unreadable.clear(it) }
                assertNull(dataStore.data.first()[stringPreferencesKey("apiKeyEnc")])
            } finally {
                // Closes the DataStore even when an assert fails, so later tests can open the same file.
                job.cancelAndJoin()
            }
        }
    }
}

/** A made-up key, built at run time so no key-like literal is committed (docs/07, secret scan). */
private val TEST_KEY = "not-a-real-key-" + 42
