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
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Anthropic adapter against a fake server (docs/03 §13.2, ADR-35; TC-U-172): the request on the wire (address,
 * headers, forced tool), the answer taken from the tool call, the key that is required, and the errors the screens
 * already word. The vectors (AiProviderVectorsTest) hold the exact bodies; the network itself is AiClientTransportTest.
 */
class AnthropicClientTest {
    private class Seen(val url: String, val headers: Headers, val body: String) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
    }

    private class Reply(val status: Int, val body: String = "{}", val retryAfter: String? = null)

    /** A server answering each call with the next of [replies]. */
    private class FakeServer(vararg replies: Reply) {
        val seen = mutableListOf<Seen>()
        private val queue = replies.toMutableList()
        val engine = MockEngine { request ->
            seen += Seen(request.url.toString(), request.headers, request.body.toByteArray().decodeToString())
            val next = queue.removeAt(0)
            respond(next.body, HttpStatusCode.fromValue(next.status), Headers.build {
                append("Content-Type", "application/json")
                next.retryAfter?.let { append("Retry-After", it) }
            })
        }

        fun client(key: String = KEY, base: String = AnthropicClient.BASE_URL) =
            AnthropicClient(ApiHttp.client(engine), "test-model", key, base, timeoutMs = null)
    }

    private companion object {
        const val KEY = "test-key-not-real"
        const val ANSWER = """{"answer":"Yes","citedHouseIds":[]}"""
        val OK = Reply(
            200,
            """{"type":"message","role":"assistant","stop_reason":"tool_use","content":[{"type":"tool_use","id":"t1","name":"answer","input":$ANSWER}]}""",
        )
        val PING_OK = Reply(200, """{"type":"message","role":"assistant","stop_reason":"max_tokens","content":[{"type":"text","text":"{\"ok\""}]}""")
    }

    private suspend fun AnthropicClient.answer() =
        generateJson("You answer questions.", "Which house has parking?", OnDeviceAi.ANSWER_SCHEMA, 0.1)

    @Test
    fun theRequestGoesToV1MessagesWithTheKeyAndVersionHeadersAndNeverTheKeyInTheUrl() = runTest {
        val server = FakeServer(OK)
        assertEquals(ANSWER, server.client().answer())
        val sent = server.seen.single()
        assertEquals("https://api.anthropic.com/v1/messages", sent.url)
        assertFalse(KEY in sent.url)
        assertEquals(KEY, sent.headers["x-api-key"])
        assertEquals("2023-06-01", sent.headers["anthropic-version"])
        assertNull(sent.headers["Authorization"])
        // The phone is not a browser: the browser-access header is the website's alone.
        assertNull(sent.headers["anthropic-dangerous-direct-browser-access"])
        assertEquals("test-model", sent.json.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun theAnswerIsForcedThroughOneToolNamedForTheCall() = runTest {
        val server = FakeServer(OK)
        server.client().answer()
        val body = server.seen.single().json
        assertEquals("""{"type":"tool","name":"answer"}""", body.getValue("tool_choice").toString())
        val tool = (body.getValue("tools") as kotlinx.serialization.json.JsonArray).single().jsonObject
        assertEquals("answer", tool.getValue("name").jsonPrimitive.content)
        // The tool takes the strict schema the vectors hold for this call, not the Gemini-dialect one.
        val strict = Json.parseToJsonElement(PARITY_VECTORS_JSON).jsonObject.getValue("schemaDialect")
            .let { it as kotlinx.serialization.json.JsonArray }.map { it.jsonObject }
            .first { it.getValue("name").jsonPrimitive.content == "answer" }.getValue("strict")
        assertEquals(strict.toString(), tool.getValue("input_schema").toString())
    }

    @Test
    fun anAddressTheClientIsGivenIsUsed() = runTest {
        val server = FakeServer(OK)
        server.client(base = "https://proxy.example.com/anthropic").answer()
        assertEquals("https://proxy.example.com/anthropic/v1/messages", server.seen.single().url)
    }

    @Test
    fun theAnswerIsTheToolCallsInputNotTheTextAroundIt() = runTest {
        val noisy = Reply(
            200,
            """{"stop_reason":"tool_use","content":[{"type":"text","text":"Let me look."},{"type":"tool_use","id":"t1","name":"answer","input":$ANSWER}]}""",
        )
        assertEquals(ANSWER, FakeServer(noisy).client().answer())
    }

    @Test
    fun aKeyIsRequiredAndWithoutOneNothingIsSent() = runTest {
        for (blank in listOf("", "   ")) {
            val server = FakeServer(OK)
            val e = assertFailsWith<ApiException>("key '$blank'") { server.client(key = blank).answer() }
            assertEquals(ApiException.Kind.AI_KEY_REJECTED, e.kind)
            assertTrue(server.seen.isEmpty(), "a request went out without a key")
            assertFailsWith<ApiException>("ping '$blank'") { server.client(key = blank).ping() }
            assertTrue(server.seen.isEmpty())
        }
    }

    @Test
    fun theStatusesTheScreensWordAreMappedForAGenerateAndAPing() = runTest {
        val cases = listOf(
            Reply(401) to ApiException.Kind.AI_KEY_REJECTED,
            Reply(403) to ApiException.Kind.AI_KEY_REJECTED,
            Reply(404) to ApiException.Kind.AI_MODEL_NOT_FOUND,
            Reply(429, retryAfter = "30") to ApiException.Kind.RATE_LIMITED,
            Reply(400) to ApiException.Kind.AI_UNAVAILABLE,
            Reply(500) to ApiException.Kind.AI_UNAVAILABLE,
            Reply(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""") to ApiException.Kind.AI_UNAVAILABLE,
        )
        for ((reply, kind) in cases) {
            val e = assertFailsWith<ApiException>("HTTP ${reply.status}") { FakeServer(reply).client().answer() }
            assertEquals(kind, e.kind, "HTTP ${reply.status}")
            assertEquals(reply.status, e.code, "HTTP ${reply.status}")
            assertEquals(kind, assertFailsWith<ApiException>("ping ${reply.status}") { FakeServer(reply).client().ping() }.kind)
        }
        assertEquals(30L, assertFailsWith<ApiException> { FakeServer(Reply(429, retryAfter = "30")).client().answer() }.retryAfterSeconds)
    }

    @Test
    fun aBadRequestIsNotRetriedDownALadder() = runTest {
        val server = FakeServer(Reply(400, """{"error":{"message":"response_format json_schema strict is not supported"}}"""), OK)
        assertFailsWith<ApiException> { server.client().answer() }
        assertEquals(1, server.seen.size)
    }

    @Test
    fun anAnswerCutOffByTheTokenLimitIsUnavailableEvenWithAToolCall() = runTest {
        val cut = Reply(200, """{"stop_reason":"max_tokens","content":[{"type":"tool_use","id":"t1","name":"answer","input":{"answer":"Yes, bal"}}]}""")
        val e = assertFailsWith<ApiException> { FakeServer(cut).client().answer() }
        assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind)
    }

    @Test
    fun pingSendsFiveTokensNoToolAndAcceptsAnAnswerCutOffAtTheLimit() = runTest {
        val server = FakeServer(PING_OK)
        server.client().ping()
        val body = server.seen.single().json
        assertEquals(5, body.getValue("max_tokens").jsonPrimitive.content.toInt())
        assertFalse("tools" in body || "tool_choice" in body)
        assertEquals(KEY, server.seen.single().headers["x-api-key"])
    }
}
