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
import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.BackupFormat
import app.doorprints.shared.export.ExportFixture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The person's AI choice in the settings (docs/03 §13.2, ADR-35; TC-U-170): kind, address and model beside
 * the key's slot, the read-time migration of a device from before it, what removing the key clears, and that none of it
 * is printed or exported.
 */
class AiProviderSettingsTest {
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    private class FakeSecrets(name: String) : SecretStore {
        val slot = stringPreferencesKey(name)
        override fun get(settings: Preferences) = settings[slot]?.removePrefix("s:")?.reversed()
        override fun put(settings: MutablePreferences, apiKey: String) {
            settings[slot] = "s:" + apiKey.reversed()
        }
        override fun clear(settings: MutablePreferences) {
            settings.remove(slot)
        }
    }

    private val dataStore = MemoryDataStore()
    private val gemini = FakeSecrets("gemini")
    private val store = SettingsStore(dataStore, FakeSecrets("sealed"), gemini) { 1_000_000L }
    private val local = AiProviderConfig(AiKind.OPENAI_COMPATIBLE, "http://localhost:11434/v1", "llama3.2")

    private suspend fun raw(): Map<String, Any> = dataStore.data.first().asMap().mapKeys { it.key.name }

    @Test
    fun aDeviceFromBeforeTheChoiceWithAKeyReadsAsGeminiAndNothingIsRewritten() = runTest {
        // What ADR-26 wrote: the provider is this device and the key is saved, with no kind, address or model.
        dataStore.edit {
            it[stringPreferencesKey("aiProvider")] = "DEVICE"
            gemini.put(it, "AIzaOldKeyForTests1234")
        }
        val settings = store.current()
        assertEquals(AiProviderChoice.DEVICE, settings.aiProvider)
        assertEquals("AIzaOldKeyForTests1234", settings.geminiKey)
        assertEquals(AiKind.GEMINI, settings.aiProviderConfig.kind)
        assertEquals(AiProviderConfig.GEMINI, settings.aiProviderConfig)
        assertTrue(raw().keys.none { it == "aiKind" || it == "aiBaseUrl" || it == "aiModel" }, "a read-time default only: ${raw().keys}")
        // Someone on the server stays on it.
        assertEquals(AiProviderChoice.SERVER, SettingsStore(MemoryDataStore(), FakeSecrets("sealed"), gemini) { 1L }.current().aiProvider)
    }

    @Test
    fun anOpenAiCompatibleChoiceIsSavedBesideTheKeyWhichIsEncryptedAsBefore() = runTest {
        store.saveAiProviderConfig(local.copy(baseUrl = " http://localhost:11434/v1 ", model = " llama3.2 "), "  sk-LocalKey-1234  ")
        val saved = store.current()
        assertEquals(local, saved.aiProviderConfig)
        assertEquals("sk-LocalKey-1234", saved.geminiKey)
        assertEquals(AiProviderChoice.DEVICE, saved.aiProvider)
        assertEquals("openai-compatible", raw()["aiKind"])
        assertEquals("http://localhost:11434/v1", raw()["aiBaseUrl"])
        assertEquals("llama3.2", raw()["aiModel"])
        assertTrue(raw().values.none { "sk-LocalKey" in it.toString() }, "the plain key landed in the settings")
    }

    @Test
    fun aLocalModelNeedsNoKeyAndABlankKeyEmptiesTheSlot() = runTest {
        store.saveGeminiKey("AIzaOwnKeyForTests1234")
        store.saveAiProviderConfig(local, "")
        assertEquals("", store.current().geminiKey)
        assertEquals(local, store.current().aiProviderConfig)
        assertEquals(AiProviderChoice.DEVICE, store.current().aiProvider)
    }

    @Test
    fun savingAGeminiKeyChoosesGeminiAndDropsTheAddressAndModel() = runTest {
        store.saveAiProviderConfig(local, "sk-LocalKey-1234")
        store.saveGeminiKey("AIzaOwnKeyForTests1234")
        assertEquals(AiProviderConfig.GEMINI, store.current().aiProviderConfig)
        assertEquals("", raw()["aiBaseUrl"])
        assertEquals("", raw()["aiModel"])
        // And a Gemini config never keeps an address even when one is passed in.
        store.saveAiProviderConfig(AiProviderConfig(AiKind.GEMINI, "https://x.example/v1", "m"), "AIzaOwnKeyForTests1234")
        assertEquals(AiProviderConfig.GEMINI, store.current().aiProviderConfig)
    }

    @Test
    fun removingTheKeyResetsTheKindTheAddressAndTheModelAndGoesBackToTheServer() = runTest {
        store.saveAiProviderConfig(local, "sk-LocalKey-1234")
        store.removeGeminiKey()
        val settings = store.current()
        assertEquals("", settings.geminiKey)
        assertEquals(AiProviderConfig.GEMINI, settings.aiProviderConfig)
        assertEquals(AiProviderChoice.SERVER, settings.aiProvider)
        assertTrue(raw().keys.none { it == "aiKind" || it == "aiBaseUrl" || it == "aiModel" }, raw().keys.toString())
    }

    @Test
    fun theSettingsPrintNoKeyNoAddressAndNoModel() = runTest {
        store.saveAiProviderConfig(local, "sk-LocalKey-1234")
        val printed = store.current().toString()
        assertFalse("sk-LocalKey" in printed, printed)
        assertFalse("localhost" in printed || "11434" in printed || "llama3.2" in printed, printed)
        assertTrue("geminiKey=set" in printed && "aiKind=openai-compatible" in printed, printed)
    }

    @Test
    fun aBackupFileNamesNoAddressNoKeyAndNoGemini() = runTest {
        // TC-U-170: the choice lives in the device's settings only; no export, copy, sync or share file carries it.
        store.saveAiProviderConfig(local, "sk-LocalKey-1234")
        val text = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(ExportFixture.bundle()))
        val lower = text.lowercase()
        for (word in listOf("baseurl", "apikey", "gemini", "openai", "aikind", "aimodel", "localhost", "sk-localkey")) {
            assertFalse(word in lower, "the backup JSON mentions '$word'")
        }
        assertTrue(text.length > 100)
    }
}
