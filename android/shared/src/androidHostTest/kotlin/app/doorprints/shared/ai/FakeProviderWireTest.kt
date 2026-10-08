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

import app.doorprints.shared.api.AndroidApiHttp
import app.doorprints.shared.api.ApiException
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three on-device AI adapters through the real Android HTTP stack (OkHttp via [AndroidApiHttp], as the app builds it)
 * against the fake provider server (`tools/fake-ai-provider/server.mjs`, a Node subprocess; S4b-BL-175, docs/06 TC-AI-24).
 * The in-memory mocks of the other tests cannot show what a real socket does: redirects, a body that never ends, headers
 * as sent, a vendor that refuses a wrong request. The server checks every request like a vendor (bearer key, `x-api-key`,
 * forced `tool_choice`, strict schemas), so a wrong request is a failing test here. Expected wire shapes come from the
 * vendors' documentation cited in `server.mjs`, not from the adapters. It does not prove that a vendor still behaves like
 * its documentation, any quota, or the quality of a real model.
 *
 * Skipped with a message, and counted in the test output ("FAKE-AI-WIRE skipped N"), when `node` is not on the PATH
 * (CI has Node).
 */
class FakeProviderWireTest {
    private val answer = OnDeviceAi.ANSWER_SCHEMA
    private val clients = mutableListOf<HttpClient>()

    private fun http(): HttpClient = AndroidApiHttp.create().also { clients += it }
    private fun openAi(key: String = "sk-test", timeoutMs: Long? = null, path: String = "/v1") =
        OpenAiCompatClient(http(), "http://127.0.0.1:$port$path", "test-model", key, timeoutMs)
    private fun anthropic(key: String = "ak-test", timeoutMs: Long? = null) =
        AnthropicClient(http(), "test-model", key, "http://127.0.0.1:$port", timeoutMs)
    private fun gemini(key: String = "gk-test", timeoutMs: Long? = null) =
        GeminiClient(http(), key, "test-model", "http://127.0.0.1:$port/v1beta", timeoutMs)

    @Before
    fun reset() {
        if (!nodeAvailable) {
            skipped.incrementAndGet()
            assumeTrue("node is not installed: the fake-provider wire tests are skipped", false)
        }
        control("DELETE", "/__requests")
        control("POST", "/__mode", """{"mode":"ok","cors":"permissive","key":null}""")
    }

    @After
    fun close() = clients.forEach { it.close() }

    private fun mode(name: String) = control("POST", "/__mode", """{"mode":"$name"}""")
    private fun log(): JsonArray = control("GET", "/__requests")["requests"]!!.jsonArray
    private fun posts(): List<JsonObject> = log().map { it.jsonObject }.filter { it["method"]!!.jsonPrimitive.content == "POST" }
    private fun JsonObject.header(name: String) = this["headers"]!!.jsonObject[name]?.jsonPrimitive?.content
    private fun JsonObject.body() = this["body"]!!.jsonObject
    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }
    private fun failure(block: suspend () -> Unit): ApiException = assertFailsWith<ApiException> { runBlocking { block() } }

    // ---- success paths -------------------------------------------------------------------------------------------

    @Test
    fun openAiCompatibleAnswersAStrictSchemaRequestOnTheFirstTier() {
        val client = openAi()
        val text = blocking { client.generateJson("system", "user", answer, 0.1) }
        assertTrue("answer" in Json.parseToJsonElement(text).jsonObject, text)
        val request = posts().single()
        assertEquals("/v1/chat/completions", request["url"]!!.jsonPrimitive.content, "no query, no key in the URL")
        assertEquals("Bearer sk-test", request.header("authorization"))
        assertEquals("json_schema", request.body()["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(1, client.tier)
    }

    @Test
    fun openAiCompatibleKeylessAgainstAnOllamaStyleServerSendsNoAuthorization() {
        mode("ollama")
        blocking { openAi(key = "").generateJson("system", "user", answer, 0.1) }
        assertNull(posts().single().header("authorization"))
    }

    @Test
    fun openAiCompatibleHonoursABaseUrlWithoutAPath() {
        // The base URL decides the route: /chat/completions as well as /v1/chat/completions.
        blocking { openAi(path = "").generateJson("system", "user", answer, 0.1) }
        assertEquals("/chat/completions", posts().single()["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicForcesTheToolAndSendsTheDocumentedHeadersWithoutTheBrowserHeader() {
        val text = blocking { anthropic().generateJson("system", "user", answer, 0.1) }
        assertTrue("answer" in Json.parseToJsonElement(text).jsonObject, text)
        val request = posts().single()
        assertEquals("ak-test", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertNull(request.header("anthropic-dangerous-direct-browser-access"), "the phones do not send the browser header")
        assertNull(request.header("origin"))
        assertEquals("tool", request.body()["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicPingIsAcceptedWhenItStopsAtTheTokenLimit() {
        blocking { anthropic().ping() }
        assertFalse("tools" in posts().single().body())
    }

    @Test
    fun geminiSendsTheKeyInTheGoogleHeaderAndParsesTheCandidate() {
        val text = blocking { gemini().generateJson("system", "user", answer, 0.1) }
        assertTrue("answer" in Json.parseToJsonElement(text).jsonObject, text)
        val request = posts().single()
        assertEquals("gk-test", request.header("x-goog-api-key"))
        assertEquals("/v1beta/models/test-model:generateContent", request["url"]!!.jsonPrimitive.content)
        blocking { gemini().ping() }
    }

    @Test
    fun theOnDeviceExtractPathReadsAGoldenSetAnswerThroughTheOpenAiAdapter() {
        val golden = Json.parseToJsonElement(File(root, "docs/ai/evals/golden-set.json").readText()).jsonObject
        val case = golden["cases"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "extract-01-whatsapp-rent" }
        val listing = case["input"]!!.jsonObject["text"]!!.jsonPrimitive.content
        val draft = blocking { OnDeviceAi(openAi(), { emptyList() }).extractListing(listing) }
        assertEquals(28_000L, draft.price)
        assertEquals(2, draft.bedrooms)
    }

    // ---- the ladder ----------------------------------------------------------------------------------------------

    @Test
    fun aServerThatOnlyKnowsJsonObjectMovesTheLadderFromTierOneToTwoAndKeepsIt() {
        mode("json-object-only")
        val client = openAi()
        blocking { client.generateJson("system", "user", answer, 0.1) }
        assertEquals(listOf("json_schema", "json_object"), posts().map { it.body()["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals(2, client.tier)
        blocking { client.generateJson("system", "user", answer, 0.1) }
        assertEquals(3, posts().size, "the next call starts at the tier that worked")
    }

    @Test
    fun aServerWithNoStructuredOutputGetsAPlainRequestOnTierThree() {
        mode("no-structured")
        val client = openAi()
        val text = blocking { client.generateJson("system", "user", answer, 0.1) }
        assertTrue("answer" in Json.parseToJsonElement(text).jsonObject, "the shape came from the system trailer: $text")
        val bodies = posts().map { it.body() }
        assertEquals(3, bodies.size)
        assertNull(bodies[2]["response_format"])
        assertTrue("of this shape" in bodies[2]["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(3, client.tier)
    }

    // ---- statuses ------------------------------------------------------------------------------------------------

    @Test
    fun everyAdapterMapsTheStatusesAProviderAnswersWithTheSameKinds() {
        val adapters = mapOf<String, () -> JsonChatModel>("openai" to { openAi() }, "anthropic" to { anthropic() }, "gemini" to { gemini() })
        for ((name, make) in adapters) {
            for ((m, kind, code) in listOf(
                Triple("unauthorized", ApiException.Kind.AI_KEY_REJECTED, 401),
                Triple("forbidden", ApiException.Kind.AI_KEY_REJECTED, 403),
                Triple("not-found", ApiException.Kind.AI_MODEL_NOT_FOUND, 404),
                Triple("server-error", ApiException.Kind.AI_UNAVAILABLE, 500),
                Triple("overloaded", ApiException.Kind.AI_UNAVAILABLE, 529),
            )) {
                if (name == "gemini" && m == "not-found") continue // Gemini's own client has no model-not-found kind (ADR-26)
                mode(m)
                val e = failure { make().ping() }
                // Gemini maps only 403 to a rejected key and everything else but 429 to unavailable; the others share aiFailure.
                val expected = if (name == "gemini" && m == "unauthorized") ApiException.Kind.AI_UNAVAILABLE else kind
                assertEquals(expected, e.kind, "$name $m")
                assertEquals(code, e.code, "$name $m")
            }
        }
    }

    @Test
    fun aRateLimitCarriesRetryAfterSecondsForOpenAiAndAnthropic() {
        mode("rate-limit")
        for (model in listOf(openAi(), anthropic())) {
            val e = failure { model.ping() }
            assertEquals(ApiException.Kind.RATE_LIMITED, e.kind)
            assertEquals(7L, e.retryAfterSeconds)
        }
        val g = failure { gemini().ping() }
        assertEquals(ApiException.Kind.RATE_LIMITED, g.kind, "Gemini: RESOURCE_EXHAUSTED")
    }

    @Test
    fun aGoogleKeyRejectedAs400WithApiKeyInvalidIsAKeyRejection() {
        mode("bad-key")
        assertEquals(ApiException.Kind.AI_KEY_REJECTED, failure { gemini().ping() }.kind)
    }

    @Test
    fun aRedirectIsNotFollowedAndIsUnavailableWithItsStatus() {
        mode("redirect")
        for (model in listOf(openAi(), anthropic(), gemini())) {
            val e = failure { model.ping() }
            assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind)
            assertEquals(302, e.code)
        }
        assertEquals(0, control("GET", "/__requests")["redirected"]!!.jsonPrimitive.content.toInt(), "nobody followed it")
    }

    @Test
    fun aBodyThatNeverEndsTimesOutAs504InEveryAdapter() {
        mode("stall")
        for (model in listOf(openAi(timeoutMs = 1_500), anthropic(timeoutMs = 1_500), gemini(timeoutMs = 1_500))) {
            val e = failure { model.generateJson("system", "user", answer, 0.1) }
            assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind)
            assertEquals(504, e.code)
        }
    }

    // ---- answers that are not an answer ---------------------------------------------------------------------------

    @Test
    fun aFencedAnswerIsUnwrappedByTheOpenAiAdapter() {
        mode("fenced")
        val text = blocking { openAi().generateJson("system", "user", answer, 0.1) }
        assertTrue(text.startsWith("{"), text)
        Json.parseToJsonElement(text)
    }

    @Test
    fun anAnswerWithNoChoicesOrOnlyAnErrorObjectIsUnavailable() {
        for (m in listOf("empty-choices", "openrouter-200-error")) {
            mode(m)
            val e = failure { openAi().generateJson("system", "user", answer, 0.1) }
            assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind, m)
            assertEquals(502, e.code, m)
        }
    }

    @Test
    fun aTruncatedOpenAiAnswerIsReturnedAsIsAndFailsToParseForTheCaller() {
        // Documented (docs/03 §13.2): the OpenAI adapter does not read finish_reason; its caller fails on the cut JSON.
        mode("truncated")
        val text = blocking { openAi().generateJson("system", "user", answer, 0.1) }
        assertFailsWith<Exception> { Json.parseToJsonElement(text) }
        // Gemini is the same today (finishReason MAX_TOKENS is not read).
        val g = blocking { gemini().generateJson("system", "user", answer, 0.1) }
        assertFailsWith<Exception> { Json.parseToJsonElement(g) }
    }

    @Test
    fun anAnthropicAnswerThatStoppedAtMaxTokensOrHasNoToolCallIsUnavailable() {
        for (m in listOf("max-tokens", "text-only")) {
            mode(m)
            val e = failure { anthropic().generateJson("system", "user", answer, 0.1) }
            assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind, m)
            assertEquals(502, e.code, m)
        }
    }

    @Test
    fun aKeylessAnthropicCallSendsNothing() {
        val e = failure { anthropic(key = "").generateJson("system", "user", answer, 0.1) }
        assertEquals(ApiException.Kind.AI_KEY_REJECTED, e.kind)
        assertTrue(posts().isEmpty(), "no request without a key")
    }

    /**
     * S4b-BL-175-F1: newer Claude models refuse a forced `tool_choice` with a 400 ("tool_choice: type "tool" and "any"
     * are not supported for this model", platform.claude.com/docs/en/api/errors). The client repeats the call once with
     * `auto` and keeps that for later calls.
     */
    @Test
    fun aModelThatRefusesAForcedToolIsAskedAgainWithAutoOnceAndThenRemembered() {
        mode("no-forced-tool")
        val client = anthropic()
        repeat(2) {
            val text = blocking { client.generateJson("system", "user", answer, 0.1) }
            assertTrue("answer" in Json.parseToJsonElement(text).jsonObject, text)
        }
        val sent = posts().map { it.body() }
        assertEquals(listOf("tool", "auto", "auto"), sent.map { it["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("system\n\nAnswer by calling the answer tool.", sent[1]["system"]!!.jsonPrimitive.content)
    }

    // ---- the server itself -------------------------------------------------------------------------------------

    private companion object {
        val skipped = AtomicInteger()
        var process: Process? = null
        var port = 0
        val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "tools/fake-ai-provider/server.mjs").isFile }
        val nodeAvailable: Boolean = runCatching {
            ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)

        @BeforeClass
        @JvmStatic
        fun startServer() {
            if (!nodeAvailable) return
            val p = ProcessBuilder(
                "node", File(root, "tools/fake-ai-provider/server.mjs").path, "--port", "0",
                "--golden", File(root, "docs/ai/evals/golden-set.json").path,
            ).redirectErrorStream(true).start()
            process = p
            val line = p.inputStream.bufferedReader().readLine() ?: error("the fake provider server printed nothing")
            port = Json.parseToJsonElement(line).jsonObject["listening"]!!.jsonPrimitive.content.toInt()
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            process?.destroy()
            println(if (nodeAvailable) "FAKE-AI-WIRE skipped 0" else "FAKE-AI-WIRE skipped ${skipped.get()}: node is not installed")
        }

        fun control(method: String, path: String, body: String? = null): JsonObject {
            val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            c.requestMethod = method
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            return Json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
        }
    }
}
