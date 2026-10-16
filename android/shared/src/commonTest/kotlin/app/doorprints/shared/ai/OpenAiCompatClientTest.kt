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
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The OpenAI-compatible adapter against a fake server (docs/03 §13.2, ADR-35; TC-U-168): the request on the
 * wire, the three-tier ladder and its cache, the key in an `Authorization: Bearer` header only when there is one, and
 * the errors the screens already word. The vectors (AiProviderVectorsTest) hold the exact bodies and rules.
 */
class OpenAiCompatClientTest {
    private class Seen(val url: String, val headers: Headers, val body: String) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject

        /** 1, 2 or 3: what the request asked for. */
        val tier: Int
            get() = when (json["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content) {
                "json_schema" -> 1
                "json_object" -> 2
                else -> 3
            }
    }

    /** A server answering each call with the next of [replies] (a status with a body, or JSON text for the model's reply). */
    private class FakeServer(vararg replies: Any, private val failWith: Throwable? = null) {
        val seen = mutableListOf<Seen>()
        private val queue = replies.toMutableList()
        val engine = MockEngine { request ->
            seen += Seen(request.url.toString(), request.headers, request.body.toByteArray().decodeToString())
            if (failWith != null) throw failWith
            when (val next = queue.removeAt(0)) {
                is Reply -> respond(next.body, HttpStatusCode.fromValue(next.status), Headers.build {
                    append("Content-Type", "application/json")
                    next.retryAfter?.let { append("Retry-After", it) }
                })
                else -> respond(completion(next as String), HttpStatusCode.OK, Headers.build { append("Content-Type", "application/json") })
            }
        }

        fun client(key: String = "sk-test-key", base: String = "https://api.example.com/v1", model: String = "test-model") =
            OpenAiCompatClient(ApiHttp.client(engine), base, model, key, timeoutMs = null)
    }

    private class Reply(val status: Int, val body: String = "{}", val retryAfter: String? = null)

    private companion object {
        fun completion(content: String) = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject { put("message", buildJsonObject { put("role", "assistant"); put("content", content) }) })
            })
        }.toString()

        const val NO_SCHEMA = """{"error":{"message":"response_format json_schema is not supported"}}"""
        const val NO_OBJECT = """{"error":{"message":"Invalid parameter: response_format json_object"}}"""
        const val ANSWER = """{"answer":"Yes","citedHouseIds":[]}"""
    }

    private suspend fun OpenAiCompatClient.answer() =
        generateJson("You answer questions.", "Which house has parking?", OnDeviceAi.ANSWER_SCHEMA, 0.1)

    @Test
    fun theRequestGoesToChatCompletionsAndTheKeyIsNeverInTheUrl() = runTest {
        val server = FakeServer(ANSWER)
        assertEquals(ANSWER, server.client().answer())
        val sent = server.seen.single()
        assertEquals("https://api.example.com/v1/chat/completions", sent.url)
        assertFalse("sk-test-key" in sent.url)
        assertEquals("test-model", sent.json.getValue("model").jsonPrimitive.content)
        assertEquals(1, sent.tier)
    }

    @Test
    fun theKeyGoesInABearerHeaderOnlyWhenThereIsOne() = runTest {
        val withKey = FakeServer(ANSWER)
        withKey.client(key = "sk-test-key").answer()
        assertEquals("Bearer sk-test-key", withKey.seen.single().headers["Authorization"])
        // A model on the person's own computer needs no key, and then no header at all.
        for (blank in listOf("", "   ")) {
            val without = FakeServer(ANSWER)
            without.client(key = blank, base = "http://localhost:11434/v1").answer()
            assertNull(without.seen.single().headers["Authorization"], "key '$blank'")
        }
    }

    @Test
    fun aServerThatLacksJsonSchemaFallsDownTheLadderToTheTierItAcceptsAndKeepsIt() = runTest {
        val server = FakeServer(Reply(400, NO_SCHEMA), Reply(400, NO_OBJECT), ANSWER, ANSWER)
        val client = server.client()
        assertEquals(ANSWER, client.answer())
        assertEquals(listOf(1, 2, 3), server.seen.map { it.tier })
        assertEquals(3, client.tier)
        // The trailer is in the system prompt of the tiers that cannot take a schema, the shared prompt alone in tier 1.
        val system = { s: Seen -> s.json.getValue("messages").let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject.getValue("content").jsonPrimitive.content } }
        assertEquals("You answer questions.", system(server.seen[0]))
        assertTrue(system(server.seen[1]).startsWith("You answer questions.\n\nReply with only a JSON object of this shape: {"))
        // The winning tier is kept: the next call goes straight to tier 3.
        assertEquals(ANSWER, client.answer())
        assertEquals(listOf(1, 2, 3, 3), server.seen.map { it.tier })
    }

    @Test
    fun theLadderStopsAtTierTwoWhenTheServerTakesAJsonObject() = runTest {
        val server = FakeServer(Reply(400, NO_SCHEMA), ANSWER)
        val client = server.client()
        client.answer()
        assertEquals(listOf(1, 2), server.seen.map { it.tier })
        assertEquals(2, client.tier)
    }

    @Test
    fun aClientThatNeverNeededALadderStaysAtTierOneAndAFreshOneStartsThere() = runTest {
        val server = FakeServer(ANSWER, ANSWER)
        val client = server.client()
        client.answer()
        client.answer()
        assertEquals(listOf(1, 1), server.seen.map { it.tier })
        assertEquals(1, server.client(model = "another").tier)
    }

    @Test
    fun aFourHundredWithoutTheTriggerWordsDoesNotMoveDownATier() = runTest {
        val server = FakeServer(Reply(400, """{"error":{"message":"max_tokens is too large"}}"""))
        val e = assertFailsWith<ApiException> { server.client().answer() }
        assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind)
        assertEquals(1, server.seen.size)
    }

    @Test
    fun onlyAFourHundredMovesDownATierNotAnotherClientErrorWithTheSameWords() = runTest {
        for (status in listOf(409, 413, 422, 451)) {
            val server = FakeServer(Reply(status, NO_SCHEMA))
            val e = assertFailsWith<ApiException>("HTTP $status") { server.client().answer() }
            assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind, "HTTP $status")
            assertEquals(1, server.seen.size, "HTTP $status")
        }
    }

    @Test
    fun aFourHundredAtTierThreeIsUnavailableAndTheLadderHasNoFourthTier() = runTest {
        val server = FakeServer(Reply(400, NO_SCHEMA), Reply(400, NO_OBJECT), Reply(400, NO_SCHEMA))
        val e = assertFailsWith<ApiException> { server.client().answer() }
        assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind)
        assertEquals(listOf(1, 2, 3), server.seen.map { it.tier })
    }

    @Test
    fun theErrorsAreTheOnesTheScreensAlreadyWord() = runTest {
        for (status in listOf(401, 403)) {
            val e = assertFailsWith<ApiException> { FakeServer(Reply(status)).client().answer() }
            assertEquals(ApiException.Kind.AI_KEY_REJECTED, e.kind, "HTTP $status")
        }
        assertEquals(ApiException.Kind.AI_MODEL_NOT_FOUND, assertFailsWith<ApiException> { FakeServer(Reply(404)).client().answer() }.kind)
        val limited = assertFailsWith<ApiException> { FakeServer(Reply(429, retryAfter = "20")).client().answer() }
        assertEquals(ApiException.Kind.RATE_LIMITED, limited.kind)
        assertEquals(20L, limited.retryAfterSeconds)
        assertNull(assertFailsWith<ApiException> { FakeServer(Reply(429)).client().answer() }.retryAfterSeconds)
        for (status in listOf(500, 502, 503)) {
            val e = assertFailsWith<ApiException> { FakeServer(Reply(status)).client().answer() }
            assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind, "HTTP $status")
            assertEquals(status, e.code)
        }
    }

    @Test
    fun aServerThatCannotBeReachedIsUnavailableWithCodeZero() = runTest {
        val server = FakeServer(failWith = IOException("connection refused"))
        val e = assertFailsWith<ApiException> { server.client(base = "http://localhost:11434/v1").answer() }
        assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind)
        assertEquals(OpenAiCompatClient.UNREACHABLE, e.code)
        assertFailsWith<ApiException> { server.client().ping() }.also { assertEquals(0, it.code) }
    }

    @Test
    fun aSuccessWithNoAnswerInItIsUnavailableAndStillWinsItsTier() = runTest {
        val server = FakeServer(Reply(200, """{"choices":[]}"""))
        val client = server.client()
        assertEquals(ApiException.Kind.AI_UNAVAILABLE, assertFailsWith<ApiException> { client.answer() }.kind)
        assertEquals(1, client.tier)
    }

    @Test
    fun pingIsOneSmallCallWithNoResponseFormatAndTheSameErrors() = runTest {
        val server = FakeServer("""{"ok":true}""")
        server.client().ping()
        val sent = server.seen.single().json
        assertEquals(5, sent.getValue("max_tokens").jsonPrimitive.content.toInt())
        assertFalse("response_format" in sent)
        assertEquals("Bearer sk-test-key", server.seen.single().headers["Authorization"])
        assertEquals(ApiException.Kind.AI_KEY_REJECTED, assertFailsWith<ApiException> { FakeServer(Reply(401)).client().ping() }.kind)
        assertEquals(ApiException.Kind.AI_MODEL_NOT_FOUND, assertFailsWith<ApiException> { FakeServer(Reply(404)).client().ping() }.kind)
        assertEquals(ApiException.Kind.AI_UNAVAILABLE, assertFailsWith<ApiException> { FakeServer(Reply(200, """{"choices":[]}""")).client().ping() }.kind)
    }

    @Test
    fun onDeviceAiAnswersThroughAnOpenAiCompatibleModelExactlyAsThroughGemini() = runTest {
        val server = FakeServer("""{"label":"2BHK","price":"25k","contactPhone":"99999 88888"}""")
        val ai = OnDeviceAi(server.client(), { emptyList() })
        val draft = ai.extractListing("2BHK in Indiranagar for 25k")
        assertEquals(25_000, draft.price)
        assertNull(draft.contactPhone) // the same checks: an invented phone number is dropped
        val sent = server.seen.single().json
        assertEquals("listing", sent.getValue("response_format").jsonObject.getValue("json_schema").jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue(sent.toString().contains("<listing-"))
    }

    @Test
    fun geminiIsAJsonChatModelAndItsPingIsTheTinyCallItAlwaysWas() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += request.url.toString()
            respond(
                """{"candidates":[{"content":{"parts":[{"text":"{\"ok\":true}"}]}}]}""",
                HttpStatusCode.OK, Headers.build { append("Content-Type", "application/json") },
            )
        }
        val model: JsonChatModel = GeminiClient(ApiHttp.client(engine), "AIzaTestKey", timeoutMs = null)
        model.ping()
        assertEquals(listOf("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"), seen)
    }
}
