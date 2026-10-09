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
import app.doorprints.shared.ai.AnthropicClient
import app.doorprints.shared.ai.GeminiClient
import app.doorprints.shared.ai.OpenAiCompatClient
import app.doorprints.shared.api.ApiClient
import app.doorprints.shared.api.ApiException
import app.doorprints.shared.api.ApiHttp
import app.doorprints.shared.api.PlanRequest
import app.doorprints.shared.model.Viewing
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
 * Whether AI is offered and why not, and where the Assistant's three calls go: the person's own key or the server
 * (docs/03 §13, ADR-26; S4b-BL-168 slice 8). Real Room and settings, fake servers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AiStatusRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val json = Headers.build { append("Content-Type", "application/json") }

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

    private val settings = SettingsStore(
        PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "ai-status.preferences_pb") },
        MemorySecrets("serverKey"), geminiSecrets = MemorySecrets("aiKey"),
    )

    /** What the server's `/api/ai/status` answers (null = a server error) and the paths the server saw. */
    private var statusBody: String? = """{"enabled":true}"""
    private val serverCalls = mutableListOf<String>()
    private val serverEngine = MockEngine { request ->
        serverCalls += request.url.encodedPath
        when (request.url.encodedPath) {
            "/api/ai/status" -> if (statusBody == "cancel") throw CancellationException("stopped") else statusBody?.let { respond(it, HttpStatusCode.OK, json) }
                ?: respond("{}", HttpStatusCode.InternalServerError, json)
            "/api/ai/ask" -> respond("""{"answer":"from the server"}""", HttpStatusCode.OK, json)
            "/api/ai/plan-visits" -> respond("""{"summary":"server plan"}""", HttpStatusCode.OK, json)
            "/api/ai/extract-listing" -> respond("""{"label":"From the server"}""", HttpStatusCode.OK, json)
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    /** The bodies Gemini was sent; it answers every call with one fixed JSON text. */
    private val geminiBodies = mutableListOf<String>()
    private val geminiEngine = MockEngine { request ->
        geminiBodies += request.body.toByteArray().decodeToString()
        respond(
            """{"candidates":[{"content":{"parts":[{"text":"{\"label\":\"From Gemini\",\"answer\":\"On device.\",\"summary\":\"Device plan\"}"}]}}]}""",
            HttpStatusCode.OK, json,
        )
    }

    private fun repository(withServer: Boolean = true, withProviders: Boolean = true) = CommonRepository(
        db, settings,
        photoDir = File(tmp.root, "photos").path,
        syncSoon = {},
        apiFor = { url, key -> if (withServer) ApiClient(url, key, ApiHttp.client(serverEngine), callTimeoutMs = null) else error("no server") },
        geminiFor = if (withProviders) ({ key -> GeminiClient(ApiHttp.client(geminiEngine), key, timeoutMs = null) }) else null,
        openAiFor = if (withProviders) ({ url, model, key -> OpenAiCompatClient(ApiHttp.client(geminiEngine), url, model, key, timeoutMs = null) }) else null,
        anthropicFor = if (withProviders) ({ url, model, key -> AnthropicClient(ApiHttp.client(geminiEngine), model, key, url, timeoutMs = null) }) else null,
    )

    private val at = 1_760_000_000_000

    private fun house(id: String, label: String, updatedAt: Long) =
        HouseEntity(id = id, label = label, lat = 12.97, lon = 77.59, createdAt = at, updatedAt = updatedAt, dirty = false)

    @After
    fun close() {
        db.close()
        scope.cancel()
    }

    @Test
    fun withNoServerAndNoOwnKeyAiIsOffWhateverThePhonesSwitchSays() = runBlocking {
        val repo = repository()
        assertEquals(AiOff.NO_SERVER, repo.aiOff.value)
        assertFalse(repo.aiEnabled.value)
        repo.setAiFeatures(true)
        assertEquals(AiOff.NO_SERVER, repo.aiOff.value)
        assertFalse(repo.aiEnabled.value)
        assertFalse(repo.refreshAiStatus())
        assertEquals(AiOff.NO_SERVER, repo.aiOff.value)
        assertTrue(serverCalls.isEmpty())
        // The device is chosen but there is no key: still the server's way, and still none.
        repo.setAiProvider(AiProviderChoice.DEVICE)
        assertEquals(AiOff.NO_SERVER, repo.aiOff.value)
        assertFalse(repo.aiEnabled.value)
    }

    @Test
    fun theServersAnswerDecidesAndThePhonesSwitchIsTheLastWord() = runBlocking {
        settings.saveServer("https://sync.example", "test-key")
        val repo = repository()
        // On at the server, off on this phone until the person opts in.
        assertFalse(repo.refreshAiStatus())
        assertEquals(AiOff.OPT_IN, repo.aiOff.value)
        assertFalse(repo.aiEnabled.value)
        repo.setAiFeatures(true)
        assertNull(repo.aiOff.value)
        assertTrue(repo.aiEnabled.value)
        assertTrue(repo.refreshAiStatus())
        repo.setAiFeatures(false)
        assertEquals(AiOff.OPT_IN, repo.aiOff.value)
        // Off at the server (the owner's switch), and off for this device only: the device wins over the server.
        repo.setAiFeatures(true)
        statusBody = """{"enabled":false}"""
        assertFalse(repo.refreshAiStatus())
        assertEquals(AiOff.SERVER, repo.aiOff.value)
        statusBody = """{"enabled":false,"offForDevice":true}"""
        repo.refreshAiStatus()
        assertEquals(AiOff.DEVICE, repo.aiOff.value)
        statusBody = """{"enabled":true,"offForDevice":true}"""
        repo.refreshAiStatus()
        assertEquals(AiOff.DEVICE, repo.aiOff.value)
    }

    @Test
    fun aFailedStatusRequestKeepsWhatWasKnownAndNoServerForgetsIt() = runBlocking {
        settings.saveServer("https://sync.example", "test-key")
        val repo = repository()
        statusBody = """{"enabled":false}"""
        repo.refreshAiStatus()
        assertEquals(AiOff.SERVER, repo.aiOff.value)
        statusBody = null
        assertFalse(repo.refreshAiStatus())
        assertEquals(AiOff.SERVER, repo.aiOff.value)
        statusBody = """{"enabled":true}"""
        repo.setAiFeatures(true)
        assertTrue(repo.refreshAiStatus())
        statusBody = null
        assertTrue("an answer that was good stays good", repo.refreshAiStatus())
        assertNull(repo.aiOff.value)
        settings.saveServer("", "")
        assertFalse(repo.refreshAiStatus())
        assertEquals(AiOff.NO_SERVER, repo.aiOff.value)
    }

    @Test
    fun aCancelledStatusRequestIsNotSwallowed() {
        runBlocking { settings.saveServer("https://sync.example", "test-key") }
        val repo = repository()
        statusBody = "cancel"
        assertThrows(CancellationException::class.java) { runBlocking { repo.refreshAiStatus() } }
    }

    @Test
    fun aChoiceIsAnOwnKeyOnlyWhenItHasWhatItsKindNeeds() = runBlocking {
        val local = AiProviderConfig(AiKind.OPENAI_COMPATIBLE, "http://localhost:11434/v1", "llama3.2")
        val anthropic = AiProviderConfig(AiKind.ANTHROPIC, "https://api.anthropic.com", "test-model")
        val repo = repository()
        suspend fun offWith(config: AiProviderConfig, key: String): AiOff? {
            settings.saveAiProviderConfig(config, key)
            repo.setAiFeatures(true)
            return repo.aiOff.value
        }
        // A model on the person's own computer needs no key; saving the choice publishes it at once.
        repo.saveAiProviderConfig(local, "")
        assertEquals(AiOff.OPT_IN, repo.aiOff.value)
        assertNull(offWith(local, ""))
        // What each kind needs: a key (Gemini, Anthropic), a model and a safe address (the other two).
        assertEquals("Gemini without a key", AiOff.NO_SERVER, offWith(AiProviderConfig.GEMINI, ""))
        assertNull("Gemini with a key", offWith(AiProviderConfig.GEMINI, "AIzaOwnKeyForTests1234"))
        assertEquals("no model", AiOff.NO_SERVER, offWith(local.copy(model = ""), ""))
        assertEquals("an unsafe address", AiOff.NO_SERVER, offWith(local.copy(baseUrl = "http://example.com/v1"), ""))
        assertEquals("Anthropic without a key", AiOff.NO_SERVER, offWith(anthropic, ""))
        assertEquals("Anthropic without a model", AiOff.NO_SERVER, offWith(anthropic.copy(model = ""), "k"))
        assertEquals("Anthropic at an unsafe address", AiOff.NO_SERVER, offWith(anthropic.copy(baseUrl = "http://example.com"), "k"))
        assertNull("Anthropic with all three", offWith(anthropic, "k"))
        // A platform that cannot build the client offers nothing, whatever is saved.
        val bare = repository(withProviders = false)
        for ((config, key) in listOf(AiProviderConfig.GEMINI to "AIzaOwnKeyForTests1234", local to "", anthropic to "k")) {
            settings.saveAiProviderConfig(config, key)
            bare.setAiFeatures(true)
            assertEquals(config.kind.name, AiOff.NO_SERVER, bare.aiOff.value)
        }
    }

    @Test
    fun theOwnKeyNeedsNoServerAndOnlyThePhonesSwitchCounts() = runBlocking {
        val repo = repository()
        repo.saveGeminiKey("AIzaOwnKeyForTests1234")
        assertEquals("saving a key chooses the device", AiOff.OPT_IN, repo.aiOff.value)
        repo.setAiProvider(AiProviderChoice.SERVER)
        assertEquals("the server is the way again, and there is none", AiOff.NO_SERVER, repo.aiOff.value)
        repo.setAiProvider(AiProviderChoice.DEVICE)
        assertEquals(AiOff.OPT_IN, repo.aiOff.value)
        assertFalse(repo.aiEnabled.value)
        repo.setAiFeatures(true)
        assertNull(repo.aiOff.value)
        assertTrue(repo.aiEnabled.value)
        // The server's report does not matter once the key is the way.
        settings.saveServer("https://sync.example", "test-key")
        statusBody = """{"enabled":false,"offForDevice":true}"""
        assertTrue(repo.refreshAiStatus())
        assertNull(repo.aiOff.value)
        repo.removeGeminiKey()
        assertEquals(AiOff.DEVICE, repo.aiOff.value)
    }

    @Test
    fun theThreeCallsGoToTheOwnKeyWhenThereIsOneAndToTheServerWhenThereIsNot() = runBlocking {
        settings.saveServer("https://sync.example", "test-key")
        val repo = repository()
        assertEquals("from the server", repo.ask("Which house is quiet?").answer)
        assertEquals("server plan", repo.planVisits(PlanRequest("Plan", 12.97, 77.59)).summary)
        assertEquals("From the server", repo.extractListing("2BHK in Indiranagar").label)
        assertEquals(listOf("/api/ai/ask", "/api/ai/plan-visits", "/api/ai/extract-listing"), serverCalls)
        assertTrue(geminiBodies.isEmpty())

        db.houses().upsert(house("h1", "Quiet corner flat", at))
        repo.saveGeminiKey("AIzaOwnKeyForTests1234")
        repo.setAiProvider(AiProviderChoice.DEVICE)
        assertEquals("From Gemini", repo.extractListing("2BHK in Indiranagar").label)
        assertEquals("On device.", repo.ask("Which house is quiet?").answer)
        assertEquals("Device plan", repo.planVisits(PlanRequest("Plan", 12.97, 77.59)).summary)
        assertEquals(3, geminiBodies.size)
        assertEquals("nothing more went to the server", 3, serverCalls.size)
    }

    @Test
    fun withNeitherAServerNorAKeyTheCallsSayTheServerIsNotSet() {
        val repo = repository(withServer = false)
        for (call in listOf<suspend () -> Any>(
            { repo.ask("Which house is quiet?") },
            { repo.planVisits(PlanRequest("Plan", 12.97, 77.59)) },
            { repo.extractListing("2BHK") },
        )) {
            val e = assertThrows(IllegalStateException::class.java) { runBlocking { call() } }
            assertEquals("Server not configured", e.message)
        }
    }

    @Test
    fun oneOnDeviceAiServesAKeyAndAChangedKeyStartsItsRateLimitAgain() = runBlocking {
        val repo = repository()
        repo.saveGeminiKey("AIzaOwnKeyForTests1234")
        repo.setAiProvider(AiProviderChoice.DEVICE)
        repeat(10) { repo.extractListing("listing $it") }
        val limited = runCatching { repo.extractListing("one more") }.exceptionOrNull()
        assertEquals(ApiException.Kind.RATE_LIMITED, (limited as ApiException).kind)
        assertEquals(10, geminiBodies.size)
        repo.saveGeminiKey("AIzaAnotherKeyForTests99")
        assertEquals("From Gemini", repo.extractListing("listing again").label)
        assertEquals(11, geminiBodies.size)
    }

    @Test
    fun theAssistantReadsTheLiveHousesNewestFirstWithTheirViewings() = runBlocking {
        val repo = repository()
        repo.saveGeminiKey("AIzaOwnKeyForTests1234")
        repo.setAiProvider(AiProviderChoice.DEVICE)
        db.houses().upsert(house("old", "Alpha oldest flat", at))
        db.houses().upsert(house("new", "Bravo newest flat", at + 5_000))
        db.houses().upsert(house("gone", "Charlie deleted flat", at + 9_000))
        repo.deleteHouse("gone")
        repo.saveViewing(Viewing(id = "v1", houseId = "old", startsAt = at, notes = "Ask about the generator"))
        val answer = repo.ask("Which flat is best?")
        assertEquals(2, answer.retrieved)
        val body = geminiBodies.single()
        assertTrue(body, "Alpha oldest flat" in body && "Bravo newest flat" in body)
        assertFalse(body, "Charlie deleted flat" in body)
        assertTrue("newest first", body.indexOf("Bravo newest flat") < body.indexOf("Alpha oldest flat"))
        assertTrue(body, "Notes: Ask about the generator" in body)
    }
}
