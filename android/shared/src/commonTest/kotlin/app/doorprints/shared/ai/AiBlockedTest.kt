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
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A response the provider's own safety system blocked is [ApiException.Kind.AI_BLOCKED] (S4b-BL-232; docs/ai/ai-design.md
 * 14): found in the shared vectors (`blockedResponses`), never retried, never walked down the structured-output ladder,
 * and the provider's words are neither in the failure nor kept. A normal answer, a 429 and a 500 are what they were.
 */
class AiBlockedTest {
    private val root = Json.parseToJsonElement(PARITY_VECTORS_JSON).jsonObject
    private val cases = root.getValue("blockedResponses").jsonArray.map { it.jsonObject }

    private companion object {
        /** In the provider's words of the vectors; it must never reach a failure. */
        const val SENTINEL = "SENTINEL-PROVIDER-WORDS"
        const val BLOCKED_GEMINI = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        const val SAFETY_GEMINI = """{"candidates":[{"finishReason":"SAFETY"}]}"""
        const val OK_GEMINI = """{"candidates":[{"content":{"parts":[{"text":"{\"ok\":true}"}]},"finishReason":"STOP"}]}"""
        const val CONTENT_FILTER_OPENAI = """{"choices":[{"index":0,"finish_reason":"content_filter","message":{"role":"assistant","content":null}}]}"""
        const val POLICY_OPENAI = """{"error":{"message":"rejected by the safety system, response_format SENTINEL-PROVIDER-WORDS","code":"content_policy_violation"}}"""
        const val OK_OPENAI = """{"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"{\"ok\":true}"}}]}"""
        const val REFUSAL_ANTHROPIC = """{"id":"msg_test","type":"message","role":"assistant","model":"m","content":[{"type":"text","text":"SENTINEL-PROVIDER-WORDS"}],"stop_reason":"refusal","stop_details":{"type":"refusal","category":null,"explanation":"SENTINEL-PROVIDER-WORDS"}}"""
        const val OK_ANTHROPIC = """{"id":"msg_test","type":"message","role":"assistant","model":"m","content":[{"type":"tool_use","id":"t","name":"ping","input":{"ok":true}}],"stop_reason":"tool_use"}"""
    }

    /** Answers each call with the next of [replies] and counts the calls. */
    private class Server(vararg replies: Pair<Int, String>) {
        var calls = 0
        private val queue = replies.toMutableList()
        val engine = MockEngine {
            calls++
            val (status, body) = queue.removeAt(0)
            respond(body, HttpStatusCode.fromValue(status), Headers.build { append("Content-Type", "application/json") })
        }
    }

    private suspend fun failure(block: suspend () -> Unit): ApiException = assertFailsWith<ApiException> { block() }

    private suspend fun gemini(server: Server) = GeminiClient(ApiHttp.client(server.engine), "AIzaTestKey1234", timeoutMs = null)
        .generateJson("s", "u", OnDeviceAi.PING_SCHEMA, 0.0)

    private suspend fun openAi(server: Server) = OpenAiCompatClient(ApiHttp.client(server.engine), "https://api.example.com/v1", "m", "k", timeoutMs = null)
        .generateJson("s", "u", OnDeviceAi.ANSWER_SCHEMA, 0.0)

    private suspend fun anthropic(server: Server) = AnthropicClient(ApiHttp.client(server.engine), "m", "key-not-real", timeoutMs = null)
        .generateJson("s", "u", OnDeviceAi.ANSWER_SCHEMA, 0.0)

    @Test
    fun theSharedVectorsSayWhichResponsesAreBlocked() {
        assertTrue(cases.size >= 40, "the vectors are the table both stacks read")
        for (case in cases) {
            val provider = case.getValue("provider").jsonPrimitive.content
            val status = case.getValue("status").jsonPrimitive.int
            val body = case.getValue("body").jsonPrimitive.content
            val expected = case.getValue("expected").jsonObject.getValue("blocked").jsonPrimitive.boolean
            assertEquals(expected, AiBlocked.isBlocked(provider, status, body), "$provider $status ${case["note"]}")
        }
        assertEquals(setOf("gemini", "openai", "anthropic"), cases.map { it.getValue("provider").jsonPrimitive.content }.toSet())
    }

    @Test
    fun aGeminiPromptBlockAndASafetyFinishAreBlockedAndCalledOnce() = runTest {
        for (body in listOf(BLOCKED_GEMINI, SAFETY_GEMINI)) {
            val server = Server(200 to body, 200 to OK_GEMINI)
            val e = failure { gemini(server) }
            assertEquals(ApiException.Kind.AI_BLOCKED, e.kind, body)
            assertEquals(1, server.calls, "a blocked answer is not asked again")
            assertFalse(SENTINEL in (e.message ?: ""))
        }
    }

    @Test
    fun anOpenAiContentFilterFinishIsBlockedWithoutWalkingTheLadder() = runTest {
        val server = Server(200 to CONTENT_FILTER_OPENAI, 200 to OK_OPENAI)
        val e = failure { openAi(server) }
        assertEquals(ApiException.Kind.AI_BLOCKED, e.kind)
        assertEquals(1, server.calls)
    }

    @Test
    fun anOpenAiContentPolicyViolationIsBlockedEvenWhenItsTextMentionsResponseFormat() = runTest {
        val server = Server(400 to POLICY_OPENAI, 200 to OK_OPENAI)
        val e = failure { openAi(server) }
        assertEquals(ApiException.Kind.AI_BLOCKED, e.kind)
        assertEquals(1, server.calls, "the ladder's trigger words are not read in a blocked answer")
        assertFalse(SENTINEL in (e.message ?: ""), "the provider's words are not in the failure")
    }

    @Test
    fun anAnthropicRefusalIsBlockedAndCalledOnce() = runTest {
        val server = Server(200 to REFUSAL_ANTHROPIC, 200 to OK_ANTHROPIC)
        val e = failure { anthropic(server) }
        assertEquals(ApiException.Kind.AI_BLOCKED, e.kind)
        assertEquals(1, server.calls)
        assertFalse(SENTINEL in (e.message ?: ""))
    }

    @Test
    fun aNormalAnswerAnd429And500AreWhatTheyWere() = runTest {
        assertEquals("{\"ok\":true}", gemini(Server(200 to OK_GEMINI)))
        assertEquals("{\"ok\":true}", openAi(Server(200 to OK_OPENAI)))
        assertEquals("""{"ok":true}""", anthropic(Server(200 to OK_ANTHROPIC)))
        for (status in listOf(429, 500)) {
            val expected = if (status == 429) ApiException.Kind.RATE_LIMITED else ApiException.Kind.AI_UNAVAILABLE
            assertEquals(expected, failure { gemini(Server(status to "{}")) }.kind, "gemini $status")
            assertEquals(expected, failure { openAi(Server(status to "{}")) }.kind, "openai $status")
            assertEquals(expected, failure { anthropic(Server(status to "{}")) }.kind, "anthropic $status")
        }
    }
}
