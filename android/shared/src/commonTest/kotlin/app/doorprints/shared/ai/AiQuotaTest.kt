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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A key whose free quota or credit is used up is [ApiException.Kind.AI_QUOTA_EXHAUSTED] (S4b-BL-243; docs/ai/ai-design.md
 * 2.1), not a wait of a minute and not "unavailable": an HTTP 402, a Gemini 429 with a `PerDay` quota id, an
 * OpenAI-compatible `insufficient_quota`, an Anthropic billing error. A per-minute 429 stays a rate limit with its seconds.
 * The vectors are `quotaResponses`; the website runs the same ones in `ai-quota.spec.ts`.
 */
class AiQuotaTest {
    private val cases = Json.parseToJsonElement(PARITY_VECTORS_JSON).jsonObject.getValue("quotaResponses").jsonArray.map { it.jsonObject }

    private fun row(note: String) = cases.first { it.getValue("note").jsonPrimitive.content == note }
    private fun body(note: String) = row(note).getValue("body").jsonPrimitive.content
    private fun status(note: String) = row(note).getValue("status").jsonPrimitive.int

    private class Server(private val status: Int, private val body: String, private val retryAfter: String? = null) {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(body, HttpStatusCode.fromValue(status), Headers.build {
                append("Content-Type", "application/json")
                retryAfter?.let { append("Retry-After", it) }
            })
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
    fun theSharedVectorsSayWhichRepliesAreASpentQuota() {
        assertTrue(cases.size >= 19, "the vectors are the table both stacks read")
        for (case in cases) {
            val provider = case.getValue("provider").jsonPrimitive.content
            val status = case.getValue("status").jsonPrimitive.int
            val text = case.getValue("body").jsonPrimitive.content
            val expected = case.getValue("expected").jsonObject.getValue("quota").jsonPrimitive.boolean
            assertEquals(expected, AiQuota.isExhausted(status, text), "$provider $status ${case["note"]}")
        }
        assertEquals(setOf("gemini", "openai", "anthropic"), cases.map { it.getValue("provider").jsonPrimitive.content }.toSet())
    }

    @Test
    fun aiFailureNamesTheKindAndKeepsAWaitForAPerMinute429() {
        assertEquals(ApiException.Kind.AI_QUOTA_EXHAUSTED, aiFailure(402, null).kind)
        val day = aiFailure(429, "30", body("Gemini 429 with a PerDay quotaId"))
        assertEquals(ApiException.Kind.AI_QUOTA_EXHAUSTED, day.kind)
        assertEquals(429, day.code)
        val minute = aiFailure(429, "30", body("Gemini 429 with a PerMinute quotaId waits"))
        assertEquals(ApiException.Kind.RATE_LIMITED, minute.kind)
        assertEquals(30L, minute.retryAfterSeconds)
        assertEquals(ApiException.Kind.RATE_LIMITED, aiFailure(429, null).kind)
        assertEquals(ApiException.Kind.AI_KEY_REJECTED, aiFailure(401, null, body("OpenAI insufficient_quota (code and type)")).kind)
    }

    @Test
    fun geminiMapsADailyQuotaAndA402ToExhaustedAndAMinuteToAWait() = runTest {
        assertEquals(ApiException.Kind.AI_QUOTA_EXHAUSTED, failure { gemini(Server(429, body("Gemini 429 with a PerDay quotaId"))) }.kind)
        assertEquals(ApiException.Kind.AI_QUOTA_EXHAUSTED, failure { gemini(Server(402, """{"error":{"message":"payment required"}}""")) }.kind)
        val wait = failure { gemini(Server(429, body("Gemini 429 with a PerMinute quotaId waits"))) }
        assertEquals(ApiException.Kind.RATE_LIMITED, wait.kind)
        assertEquals(60L, wait.retryAfterSeconds)
        assertEquals(ApiException.Kind.RATE_LIMITED, failure { gemini(Server(429, body("Gemini 429 without details waits"))) }.kind)
        assertEquals(ApiException.Kind.AI_KEY_REJECTED, failure { gemini(Server(status("Gemini 400 invalid key"), body("Gemini 400 invalid key"))) }.kind)
    }

    @Test
    fun anOpenAiCompatibleInsufficientQuotaAnd402AreExhaustedAfterOneCall() = runTest {
        for (note in listOf("OpenAI insufficient_quota (code and type)", "OpenAI-compatible 402 (OpenRouter style)", "402 with no body")) {
            val server = Server(status(note), body(note))
            assertEquals(ApiException.Kind.AI_QUOTA_EXHAUSTED, failure { openAi(server) }.kind, note)
            assertEquals(1, server.calls, note)
        }
        val ladder = Server(400, """{"error":{"message":"response_format json_schema not supported; quota","code":"insufficient_quota"}}""")
        assertEquals(ApiException.Kind.AI_QUOTA_EXHAUSTED, failure { openAi(ladder) }.kind)
        assertEquals(1, ladder.calls, "a spent quota is not walked down the structured-output ladder")
        val wait = failure { openAi(Server(429, body("OpenAI rate_limit_exceeded waits"), retryAfter = "20")) }
        assertEquals(ApiException.Kind.RATE_LIMITED, wait.kind)
        assertEquals(20L, wait.retryAfterSeconds)
    }

    @Test
    fun anAnthropicBillingErrorAndALowCreditBalanceAreExhausted() = runTest {
        assertEquals(ApiException.Kind.AI_QUOTA_EXHAUSTED, failure { anthropic(Server(402, body("Anthropic billing_error 402"))) }.kind)
        assertEquals(ApiException.Kind.AI_QUOTA_EXHAUSTED, failure { anthropic(Server(400, body("Anthropic low credit 400"))) }.kind)
        assertEquals(ApiException.Kind.AI_QUOTA_EXHAUSTED, failure { anthropic(Server(400, body("billing_error type on its own"))) }.kind)
        assertEquals(ApiException.Kind.AI_UNAVAILABLE, failure { anthropic(Server(529, body("Anthropic 529"))) }.kind)
        assertEquals(ApiException.Kind.RATE_LIMITED, failure { anthropic(Server(429, body("Anthropic 429 waits"))) }.kind)
    }
}
