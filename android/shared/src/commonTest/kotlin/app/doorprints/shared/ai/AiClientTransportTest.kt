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

package app.doorprints.shared.ai

import app.doorprints.shared.api.ApiException
import app.doorprints.shared.api.ApiHttp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What both AI adapters do with the network itself (docs/03 §13, ADR-35; TC-U-168), through their public methods and a
 * fake engine: a failed connection, an answer whose body never finishes, and a redirect. The two adapters must word
 * each of them the same way, so the screens do (AssistantScreen).
 */
class AiClientTransportTest {
    private val geminiHost = "generativelanguage.googleapis.com"
    private val elsewhere = "evil.example.net"

    private fun openAi(engine: MockEngine, timeoutMs: Long? = null) =
        OpenAiCompatClient(ApiHttp.client(engine), "https://api.example.com/v1", "test-model", "sk-secret-key", timeoutMs)

    private fun gemini(engine: MockEngine, timeoutMs: Long? = null) =
        GeminiClient(ApiHttp.client(engine), "AIza-secret-key", timeoutMs = timeoutMs)

    /** Both adapters, built on [engine], by name for the failure message. */
    private fun both(engine: MockEngine, timeoutMs: Long? = null): Map<String, JsonChatModel> =
        mapOf("openai" to openAi(engine, timeoutMs), "gemini" to gemini(engine, timeoutMs))

    private suspend fun JsonChatModel.ask() = generateJson("system", "user", OnDeviceAi.ANSWER_SCHEMA, 0.1)

    @Test
    fun aCallThatCannotConnectIsUnavailableWithCodeZeroInBothAdapters() = runTest {
        val engine = MockEngine { throw IOException("connection refused") }
        for ((name, model) in both(engine)) {
            val e = assertFailsWith<ApiException>(name) { model.ask() }
            assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind, name)
            assertEquals(0, e.code, name)
            assertEquals(0, assertFailsWith<ApiException>("$name ping") { model.ping() }.code, "$name ping")
        }
    }

    @Test
    fun anAnswerWhoseBodyNeverFinishesTimesOutAs504InBothAdapters() = runTest {
        // Headers arrive at once; the body channel is never written or closed.
        val engine = MockEngine {
            respond(ByteChannel(), HttpStatusCode.OK, Headers.build { append(HttpHeaders.ContentType, "application/json") })
        }
        for ((name, model) in both(engine, timeoutMs = 5_000)) {
            val e = assertFailsWith<ApiException>(name) { model.ask() }
            assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind, name)
            assertEquals(504, e.code, name)
        }
    }

    @Test
    fun aRedirectIsNotFollowedAndTheKeyNeverReachesTheOtherHost() = runTest {
        val requestedHosts = mutableListOf<String>()
        val keyHeaders = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedHosts += request.url.host
            request.headers["Authorization"]?.let { keyHeaders += "${request.url.host} $it" }
            request.headers["x-goog-api-key"]?.let { keyHeaders += "${request.url.host} $it" }
            respond("", HttpStatusCode.Found, Headers.build { append(HttpHeaders.Location, "https://$elsewhere/steal") })
        }
        for ((name, model) in both(engine)) {
            val e = assertFailsWith<ApiException>(name) { model.ask() }
            assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind, name)
            assertEquals(302, e.code, name)
        }
        // One request per adapter, each to its own host, and none to the Location.
        assertEquals(listOf("api.example.com", geminiHost), requestedHosts)
        assertTrue(keyHeaders.none { elsewhere in it }, "the key was sent to $elsewhere")
    }

    @Test
    fun theSharedClientFollowsNoRedirectOnAnyMethodAndReturnsTheStatusInsteadOfThrowing() = runTest {
        // Ktor follows a redirect for GET and HEAD only, so the POSTs above cannot show the setting; a GET does.
        val requestedHosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedHosts += request.url.host
            respond("", HttpStatusCode.TemporaryRedirect, Headers.build { append(HttpHeaders.Location, "https://$elsewhere/steal") })
        }
        val client = ApiHttp.client(engine)
        assertEquals(HttpStatusCode.TemporaryRedirect, client.get("https://api.example.com/v1/models").status)
        assertEquals(HttpStatusCode.TemporaryRedirect, client.post("https://api.example.com/v1/chat/completions").status)
        assertEquals(listOf("api.example.com", "api.example.com"), requestedHosts)
    }
}
