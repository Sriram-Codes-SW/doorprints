/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.doorprints.shared.sync.SyncOutcome
import app.doorprints.shared.trace.RepeatLook
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
    private class FakeSecrets(name: String = "sealed") : SecretStore {
        val slot = stringPreferencesKey(name)
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
    fun theViewingRemindersQuestionHasItsOwnAskedFlag() = runTest {
        // S4b-BL-93f: asking from Export or Import does not count as asking for the reminders, nor the reverse.
        store.setNotificationsAsked()
        assertFalse(store.viewingsNotificationsAsked.first())
        store.setViewingsNotificationsAsked()
        assertTrue(store.viewingsNotificationsAsked.first())
        assertEquals(true, raw()["viewings.notificationsAsked"])
    }

    @Test
    fun viewingRemindersStartOnAndAreKeptUnderTheWebKey() = runTest {
        // docs/11 5.8, slice 3b-2: on until the person turns them off; `viewings.remind`, the web's SETTING_KEYS key.
        assertTrue(store.viewingsRemind().first())
        store.setViewingsRemind(false)
        assertFalse(store.viewingsRemind().first())
        assertEquals(false, raw()["viewings.remind"])
        store.setViewingsRemind(true)
        assertTrue(store.viewingsRemind().first())
    }

    @Test
    fun huntRemindersStartOnAtFifteenMinutesAndTakeOnlyTheChoices() = runTest {
        // docs/11 5.16, slice 3c: `hunt.remind` on and `hunt.reminderMin` 15 until the person changes them; local only.
        assertTrue(store.huntRemind().first())
        assertEquals(15, store.huntReminderMin().first())
        store.setHuntRemind(false)
        assertFalse(store.huntRemind().first())
        assertEquals(false, raw()["hunt.remind"])
        for (m in listOf(5, 10, 15, 20, 30, 45, 60)) {
            store.setHuntReminderMin(m)
            assertEquals(m, store.huntReminderMin().first())
            assertEquals(m, raw()["hunt.reminderMin"])
        }
        // A value that is not a choice is not kept, and one already stored (another app, an old file) reads 15.
        store.setHuntReminderMin(7)
        assertEquals(15, store.huntReminderMin().first())
        dataStore.updateData { p -> p.toMutablePreferences().apply { this[androidx.datastore.preferences.core.intPreferencesKey("hunt.reminderMin")] = 90 } }
        assertEquals(15, store.huntReminderMin().first())
    }

    @Test
    fun aiFeaturesStartOffAndAreKept() = runTest {
        // docs/03 §12.1: off until the person turns them on.
        assertFalse(store.current().aiFeatures)
        store.saveAiFeatures(true)
        assertTrue(store.current().aiFeatures)
        assertEquals(true, raw()["aiFeatures"])
        store.saveAiFeatures(false)
        assertFalse(store.current().aiFeatures)
    }

    @Test
    fun theGeminiKeyHasItsOwnSlotAndChoosesOnDeviceAi() = runTest {
        val gemini = FakeSecrets("gemini")
        val withGemini = SettingsStore(dataStore, secrets, geminiSecrets = gemini) { clock }
        assertEquals(AiProviderChoice.SERVER, withGemini.current().aiProvider)
        withGemini.saveServer("https://api.example.com", TEST_KEY)
        withGemini.saveGeminiKey("  AIzaOwnKeyForTests1234  ")
        val saved = withGemini.current()
        assertEquals("AIzaOwnKeyForTests1234", saved.geminiKey)
        assertEquals("1234", saved.geminiKeyHint)
        assertEquals(AiProviderChoice.DEVICE, saved.aiProvider)
        assertEquals(TEST_KEY, saved.apiKey) // the server key is untouched
        assertTrue(raw().values.none { "AIzaOwnKey" in it.toString() }, "plain Gemini key in the settings")
        withGemini.saveAiProvider(AiProviderChoice.SERVER)
        assertEquals("AIzaOwnKeyForTests1234", withGemini.current().geminiKey) // kept when choosing the server
        withGemini.removeGeminiKey()
        assertEquals("", withGemini.current().geminiKey)
        assertEquals(AiProviderChoice.SERVER, withGemini.current().aiProvider)
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
        store.saveCursors(house = 5, visit = 6, record = 8)
        store.savePhotoCursor(7)
        store.saveSyncResult(SyncOutcome(SyncOutcome.Kind.AUTH))
        store.saveServer("https://a.example", "")
        assertEquals(SettingsStore.Cursors(5, 6, 7, 8), store.cursors())
        assertEquals(1, store.current().syncFailures)

        store.saveServer("https://a.example", "key-bbbbbbbbbbbb")
        assertEquals(SettingsStore.Cursors(5, 6, 7, 8), store.cursors())
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
        store.saveCursors(1, 2, 4)
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
                "syncFailures", "syncFailingSince", "lastSyncOkAt", "houseCursor", "visitCursor", "photoCursor", "cursor.record",
                "autoBackup", "autoBackupFolder", "autoBackupKeep", "lastAutoBackupAt", "lastAutoBackupError",
                "exportGrants", "exportDismissedRun", "exportToldRun", "importDismissedRun", "importToldRun",
                "notificationsAsked",
            ),
            raw().keys,
        )
        assertEquals("settings", SettingsStore.FILE_NAME)
    }

    @Test
    fun theAreaWakeupIsOffUntilTurnedOnUnderItsStoredName() = runTest {
        assertFalse(store.areaWakeup().first())
        assertFalse(store.areaWakeupOffNotice().first())
        store.setAreaWakeup(true)
        assertTrue(store.areaWakeup().first())
        assertEquals(true, raw()["areas.wakeup"])
        store.setAreaWakeup(false)
        assertFalse(store.areaWakeup().first())
    }

    @Test
    fun losingThePermissionSwitchesTheWakeupOffAndSetsTheOneTimeNotice() = runTest {
        // Off already: nothing to say.
        assertFalse(store.switchAreaWakeupOffForPermission())
        assertFalse(store.areaWakeupOffNotice().first())
        store.setAreaWakeup(true)
        assertTrue(store.switchAreaWakeupOffForPermission())
        assertFalse(store.areaWakeup().first())
        assertTrue(store.areaWakeupOffNotice().first())
        assertEquals(true, raw()["areas.wakeupOffNotice"])
        store.clearAreaWakeupOffNotice()
        assertFalse(store.areaWakeupOffNotice().first())
        // Turning it on again clears a notice not yet shown.
        store.setAreaWakeup(true)
        store.switchAreaWakeupOffForPermission()
        store.setAreaWakeup(true)
        assertFalse(store.areaWakeupOffNotice().first())
    }

    @Test
    fun eachAreaHasItsOwnLastNotifiedKeyRemovedWithTheArea() = runTest {
        assertNull(store.areaLastNotified("a_00000001"))
        store.setAreaLastNotified("a_00000001", 111)
        store.setAreaLastNotified("a_00000002", 222)
        assertEquals(111L, store.areaLastNotified("a_00000001"))
        assertEquals(222L, raw()["areas.lastNotified.a_00000002"])
        store.removeAreaLastNotified("a_00000001")
        assertNull(store.areaLastNotified("a_00000001"))
        store.setAreaLastNotified("a_00000003", 333)
        store.pruneAreaLastNotified(setOf("a_00000003"))
        assertNull(store.areaLastNotified("a_00000002"))
        assertEquals(333L, store.areaLastNotified("a_00000003"))
        assertEquals(setOf("areas.lastNotified.a_00000003"), raw().keys)
    }

    @Test
    fun theAppLockIsOffUntilTurnedOnAndKeepsOnlyKnownTimes() = runTest {
        assertFalse(store.current().appLock)
        assertEquals(60, store.current().appLockAfterSeconds)
        store.saveAppLock(true)
        store.saveAppLockAfter(300)
        assertTrue(store.current().appLock)
        assertEquals(300, store.current().appLockAfterSeconds)
        // Stored names: an upgraded install reads them unchanged.
        assertEquals(true, raw()["appLock"])
        assertEquals(300, raw()["appLockAfterSeconds"])
        // A time that is not one of the choices (an edited or future file) reads as the default.
        store.saveAppLockAfter(42)
        assertEquals(60, store.current().appLockAfterSeconds)
        store.saveAppLockAfter(0)
        assertEquals(0, store.current().appLockAfterSeconds)
        assertEquals(AppLockSetting(on = true, afterSeconds = 0), store.appLockSetting.first())
    }

    @Test
    fun theRepeatLookIsClearAndTheAlertOffUntilChangedAndBothSurviveARestart() = runTest {
        // docs/11 5.27.4, 5.27.5 (S4b-FR-14): per device, never exported.
        assertEquals(RepeatLook.CLEAR, store.current().repeatLook)
        assertFalse(store.current().repeatAlert)
        store.saveRepeatLook(RepeatLook.SUBTLE)
        store.saveRepeatAlert(true)
        val reopened = SettingsStore(dataStore, secrets) { clock }
        assertEquals(RepeatLook.SUBTLE, reopened.current().repeatLook)
        assertTrue(reopened.current().repeatAlert)
        store.saveRepeatLook(RepeatLook.OFF)
        assertEquals(RepeatLook.OFF, store.current().repeatLook)
        assertEquals("OFF", raw()["repeatLook"])
    }

    @Test
    fun anUnknownRepeatLookReadsAsClear() = runTest {
        dataStore.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("repeatLook")] = "LOUD" } }
        assertEquals(RepeatLook.CLEAR, store.current().repeatLook)
    }

    @Test
    fun theWalkWatermarkOnlyMovesForwardAndIsNeverPrinted() = runTest {
        assertEquals(0L, store.current().walkAskedUpTo)
        store.saveWalkAskedUpTo(1_760_000_123_456)
        store.saveWalkAskedUpTo(1_750_000_000_000) // an older answer does not move it back
        assertEquals(1_760_000_123_456, store.current().walkAskedUpTo)
        val printed = store.current().toString()
        assertFalse(printed.contains("walkAskedUpTo"), printed)
        assertFalse(printed.contains("1760000123456"), printed)
        assertTrue(printed.contains("repeatLook=CLEAR") && printed.contains("repeatAlert=false"), printed)
    }

    @Test
    fun thePathTraceIsOffUntilTurnedOn() = runTest {
        assertFalse(store.current().pathTrace)
        store.savePathTrace(true)
        assertTrue(store.current().pathTrace)
        assertEquals(true, raw()["pathTrace"])
    }

    @Test
    fun shareContactsAreAddedOnceMarkedAndRemoved() = runTest {
        assertTrue(store.current().shareContacts.isEmpty())
        val priya = store.addShareContact("  Priya ")!!
        assertEquals("Priya", priya.name)
        assertEquals(0L, priya.lastSharedAt)
        assertEquals(priya.id, store.addShareContact("priya")!!.id, "the same name, whatever the case, is one person")
        assertNull(store.addShareContact("   "))
        store.markShared(priya.id, 1_790_000_000_000)
        assertEquals(1_790_000_000_000, store.current().shareContacts.single().lastSharedAt)
        store.addShareContact("Amma")
        assertEquals(listOf("Priya", "Amma"), store.current().shareContacts.map { it.name })
        store.removeShareContact(priya.id)
        assertEquals(listOf("Amma"), store.current().shareContacts.map { it.name })
        assertTrue(store.current().toString().contains("shareContacts=1"))
    }

    @Test
    fun theTourIsUnseenUntilItEndsAndThenRemembersHowItEnded() = runTest {
        assertNull(store.tourEnd().first(), "a fresh install has not seen the tour")
        store.setTourEnd(TourEnd.SKIPPED)
        assertEquals(TourEnd.SKIPPED, store.tourEnd().first())
        assertEquals("skipped", raw()["tour"], "the stored words are the website's")
        store.setTourEnd(TourEnd.DONE)
        assertEquals(TourEnd.DONE, store.tourEnd().first())
        assertEquals("done", raw()["tour"])
        assertEquals(AppSettings(), store.current(), "the tour is not one of the app settings, so no export or sync carries it")
    }

    @Test
    fun aStoredTourWordIsReadTheWayTheWebsiteReadsIt() {
        assertNull(TourEnd.fromWire(null))
        assertEquals(TourEnd.DONE, TourEnd.fromWire("done"))
        assertEquals(TourEnd.SKIPPED, TourEnd.fromWire("skipped"))
        assertEquals(TourEnd.SKIPPED, TourEnd.fromWire("anything else"), "any stored value counts as seen")
    }

    @Test
    fun theSettingsPrintNeitherKey() = runTest {
        val gemini = FakeSecrets("gemini")
        val withGemini = SettingsStore(dataStore, secrets, gemini) { clock }
        withGemini.saveServer("https://api.example.com", TEST_KEY)
        withGemini.saveGeminiKey("g-$TEST_KEY")
        val printed = withGemini.current().toString()
        // S4b-BL-29: a data class's toString() would print both keys into any log that prints the settings.
        assertFalse(printed.contains(TEST_KEY), printed)
        assertTrue(printed.contains("apiKey=set") && printed.contains("geminiKey=set"), printed)
        assertTrue(AppSettings().toString().contains("apiKey=none"))
    }
}

/** A made-up key, built at run time so no key-like literal is committed (docs/07, secret scan). */
private val TEST_KEY = "not-a-real-key-" + 42
