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

import android.app.Application
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.ai.GeminiClient
import app.doorprints.shared.ai.OpenAiCompatClient
import app.doorprints.shared.api.ApiHttp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The repository's choice of AI (docs/03 §13.2, ADR-35; TC-U-168): the saved kind builds the Gemini client or the
 * OpenAI-compatible one, a bad address or a missing model is refused before anything is saved, and removing the key
 * clears the choice. Real Room and settings, fake servers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AiProviderRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val geminiCalls = mutableListOf<String>()
    private val openAiCalls = mutableListOf<Pair<String, String?>>()

    private class MemorySecrets(name: String) : SecretStore {
        private val key = stringPreferencesKey(name)
        override fun get(settings: Preferences): String? = settings[key]
        override fun put(settings: MutablePreferences, apiKey: String) {
            settings[key] = apiKey
        }
        override fun clear(settings: MutablePreferences) {
            settings.remove(key)
        }
    }

    private val json = Headers.build { append("Content-Type", "application/json") }
    private val geminiEngine = MockEngine { request ->
        geminiCalls += request.url.toString()
        respond("""{"candidates":[{"content":{"parts":[{"text":"{\"label\":\"From Gemini\"}"}]}}]}""", HttpStatusCode.OK, json)
    }
    private val openAiEngine = MockEngine { request ->
        openAiCalls += request.url.toString() to request.headers["Authorization"]
        respond("""{"choices":[{"message":{"role":"assistant","content":"{\"label\":\"From OpenAI\"}"}}]}""", HttpStatusCode.OK, json)
    }

    private val settings = SettingsStore(
        PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "ai.preferences_pb") },
        MemorySecrets("serverKey"), geminiSecrets = MemorySecrets("aiKey"),
    )

    private fun repository(emulator: Boolean = false) = CommonRepository(
        db, settings,
        photoDir = File(tmp.root, "photos").path,
        syncSoon = {},
        apiFor = { _, _ -> error("no server in this test") },
        geminiFor = { key -> GeminiClient(ApiHttp.client(geminiEngine), key, timeoutMs = null) },
        openAiFor = { url, model, key -> OpenAiCompatClient(ApiHttp.client(openAiEngine), url, model, key, timeoutMs = null) },
        emulatorHostAllowed = emulator,
    )

    private val local = AiProviderConfig(AiKind.OPENAI_COMPATIBLE, "http://localhost:11434/v1/", "llama3.2")

    @After
    fun close() {
        db.close()
        scope.cancel()
    }

    @Test
    fun geminiStaysTheDefaultKindAndIsCalledWithTheSavedKey() = runBlocking {
        val repo = repository()
        repo.saveGeminiKey("AIzaOwnKeyForTests1234")
        assertEquals("From Gemini", repo.extractListing("2BHK in Indiranagar").label)
        assertEquals(1, geminiCalls.size)
        assertTrue(openAiCalls.isEmpty())
    }

    @Test
    fun anOpenAiCompatibleChoiceBuildsTheOpenAiClientWithTheNormalisedAddressAndNoKeyHeaderWhenThereIsNoKey() = runBlocking {
        val repo = repository()
        repo.saveAiProviderConfig(local, "")
        assertEquals("http://localhost:11434/v1", settings.current().aiProviderConfig.baseUrl)
        assertEquals("From OpenAI", repo.extractListing("2BHK in Indiranagar").label)
        assertEquals(listOf<Pair<String, String?>>("http://localhost:11434/v1/chat/completions" to null), openAiCalls)
        assertTrue(geminiCalls.isEmpty())
        // With a key: the same client, a Bearer header.
        repo.saveAiProviderConfig(local, "sk-test-key")
        repo.extractListing("another listing")
        assertEquals("Bearer sk-test-key", openAiCalls.last().second)
    }

    @Test
    fun aBadAddressOrAnEmptyModelIsRefusedAndNothingIsSaved() = runBlocking {
        val repo = repository()
        val reasons = mapOf(
            "http://example.com/v1" to "insecureHost", "https://example.com/v1/chat/completions" to "endpoint",
            "https://u:p@example.com/v1" to "userinfo", "" to "empty", "ftp://example.com" to "scheme",
        )
        for ((url, reason) in reasons) {
            val e = runCatching { repo.saveAiProviderConfig(local.copy(baseUrl = url), "k") }.exceptionOrNull()
            assertEquals(url, reason, e?.message)
        }
        assertEquals("model", runCatching { repo.saveAiProviderConfig(local.copy(model = " "), "k") }.exceptionOrNull()?.message)
        assertEquals("kind", runCatching { repo.saveAiProviderConfig(AiProviderConfig(AiKind.ANTHROPIC), "k") }.exceptionOrNull()?.message)
        assertEquals(AiProviderConfig.GEMINI, settings.current().aiProviderConfig)
        assertEquals("", settings.current().geminiKey)
    }

    @Test
    fun theEmulatorsAddressIsAllowedOnlyWhereThePlatformSaysSo() = runBlocking {
        val config = local.copy(baseUrl = "http://10.0.2.2:11434/v1")
        assertEquals("insecureHost", runCatching { repository().saveAiProviderConfig(config, "") }.exceptionOrNull()?.message)
        repository(emulator = true).saveAiProviderConfig(config, "")
        assertEquals(config.baseUrl, settings.current().aiProviderConfig.baseUrl)
    }

    @Test
    fun testingAProviderSendsOnePingToItAndSavesNothing() = runBlocking {
        val repo = repository()
        assertTrue(repo.testAiProvider(local, "").isSuccess)
        assertEquals(1, openAiCalls.size)
        assertEquals(AiProviderConfig.GEMINI, settings.current().aiProviderConfig)
        assertTrue(repo.testAiProvider(local.copy(baseUrl = "http://example.com/v1"), "").isFailure)
        assertEquals(1, openAiCalls.size)
        assertTrue(repo.testGeminiKey("AIzaOwnKeyForTests1234").isSuccess)
        assertEquals(1, geminiCalls.size)
    }

    @Test
    fun removingTheKeyClearsTheChoiceAndAiLeavesTheDevice() = runBlocking {
        val repo = repository()
        repo.saveAiProviderConfig(local, "sk-test-key")
        repo.removeGeminiKey()
        assertEquals(AiProviderConfig.GEMINI, settings.current().aiProviderConfig)
        assertEquals(AiProviderChoice.SERVER, settings.current().aiProvider)
        assertFalse(settings.current().toString().contains("localhost"))
        val e = runCatching { repo.extractListing("x") }.exceptionOrNull()
        assertTrue("with nothing on the device AI asks the server, which this test does not have: $e", e != null)
        assertTrue(openAiCalls.isEmpty())
    }
}
