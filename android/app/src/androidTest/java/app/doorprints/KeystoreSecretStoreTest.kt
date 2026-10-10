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

package app.doorprints

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.ApiKeyCipher
import app.doorprints.data.KeystoreSecretStore
import app.doorprints.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real Android Keystore path of the saved API key (S4b-BL-27, CMP-4 P4b): `SettingsUpgradeTest` runs
 * [KeystoreSecretStore] with a stand-in cipher, because the JVM has no Keystore. Here the settings are written and read
 * through [KeystoreSecretStore] with [ApiKeyCipher] itself, as `SettingsStore.create(context)` wires them, on a DataStore
 * file of the test's own (the app's real settings file is open in this process, and a second DataStore on it would
 * throw): the key is saved, read back whole, and kept in the entries `apiKeyEnc` and `geminiKeyEnc` sealed (`v1:`, not
 * the key, a fresh IV each time), and a value that was not sealed on this device reads as no key. It runs on every
 * emulator API level of android-emulator.yml (26, 34, 36), the Keystore of each. The upgrade from a build before PR #22
 * on a real phone (the TC-M-27 kind of check) stays manual.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(ApplicationProvider.getApplicationContext<DoorprintsApp>().cacheDir, "keystore-test.preferences_pb")
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
    private val settings = SettingsStore(
        dataStore, KeystoreSecretStore(), geminiSecrets = KeystoreSecretStore(entry = KeystoreSecretStore.GEMINI_ENTRY),
    )
    private val apiKeyEnc = stringPreferencesKey("apiKeyEnc")
    private val geminiKeyEnc = stringPreferencesKey("geminiKeyEnc")
    private val key = "sk-test-0123456789abcdef"

    @Before fun clean() {
        file.delete()
    }

    @After fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test fun theServerKeyIsSavedSealedAndReadBackWhole() = runBlocking {
        settings.saveServer("https://doorprints.example", key)
        assertEquals(key, settings.current().apiKey)
        val raw = dataStore.data.first()[apiKeyEnc]
        assertTrue("sealed with the v1 prefix: $raw", raw != null && raw.startsWith("v1:"))
        assertFalse("the plain key is not in the file", raw!!.contains(key))
        // The Keystore opens what it sealed, through the cipher alone as well.
        assertEquals(key, ApiKeyCipher.decrypt(raw))
    }

    @Test fun eachSealIsDifferentAndBothOpen() {
        val a = ApiKeyCipher.encrypt(key)
        val b = ApiKeyCipher.encrypt(key)
        assertNotEquals("a fresh IV every time", a, b)
        assertEquals(key, ApiKeyCipher.decrypt(a))
        assertEquals(key, ApiKeyCipher.decrypt(b))
    }

    @Test fun theGeminiKeyHasItsOwnSealedEntry() = runBlocking {
        settings.saveServer("https://doorprints.example", key)
        settings.saveGeminiKey("AIza-test-0123456789")
        val prefs = dataStore.data.first()
        assertEquals("AIza-test-0123456789", settings.current().geminiKey)
        assertEquals(key, settings.current().apiKey)
        assertTrue(prefs[geminiKeyEnc]!!.startsWith("v1:"))
        assertNotEquals(prefs[apiKeyEnc], prefs[geminiKeyEnc])
    }

    @Test fun aValueNotSealedOnThisDeviceReadsAsNoKey() = runBlocking {
        // A restored or copied settings file: the Keystore key is not in the backup, so the value cannot be opened.
        dataStore.updateData { p -> p.toMutablePreferences().also { it[apiKeyEnc] = "v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" } }
        assertNull(ApiKeyCipher.decrypt("v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertEquals("", settings.current().apiKey)
    }
}
