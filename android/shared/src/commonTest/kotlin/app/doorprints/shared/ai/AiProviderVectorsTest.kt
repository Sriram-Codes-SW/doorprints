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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The AI provider's shared test vectors (docs/03 §13.2, ADR-35; `docs/ai/evals/parity-vectors.json`, sections
 * `schemaDialect`, `openaiRequest`, `openaiContent`, `providerErrors` and `baseUrl`; TC-U-168; `anthropicRequest`, `anthropicContent` and the Anthropic
 * rows of `providerErrors`: TC-U-172; `geminiRequest`: TC-U-192) run through the Kotlin
 * code. The website runs the same file through its TypeScript code. Common code, so the iPhone's simulator runs them too.
 */
class AiProviderVectorsTest {
    private val root = Json.parseToJsonElement(PARITY_VECTORS_JSON).jsonObject

    private fun cases(section: String) = root.getValue(section).jsonArray.map { it.jsonObject }

    /** The `providerErrors` rows of one provider; a row without `provider` is the OpenAI-compatible adapter's. */
    private fun errorCases(provider: String) =
        cases("providerErrors").filter { (it["provider"]?.jsonPrimitive?.content ?: "openai") == provider }

    /** JSON equality where `0` and `0.0` are the same number (the vectors come from JavaScript). */
    private fun same(a: JsonElement?, b: JsonElement?): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys.toList() == b.keys.toList() && a.keys.all { same(a[it], b[it]) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a.doubleOrNull != null ->
            a.doubleOrNull == b.doubleOrNull
        else -> a == b
    }

    private fun schemaFor(call: String): JsonObject? = when (call) {
        "listing" -> OnDeviceAi.LISTING_SCHEMA
        "answer" -> OnDeviceAi.ANSWER_SCHEMA
        "plan" -> OnDeviceAi.PLAN_SCHEMA
        else -> null
    }

    @Test
    fun schemaDialectMatchesTheVectors() {
        val all = cases("schemaDialect")
        assertEquals(listOf("listing", "answer", "plan"), all.map { it.getValue("name").jsonPrimitive.content })
        for (case in all) {
            val name = case.getValue("name").jsonPrimitive.content
            val gemini = case.getValue("gemini").jsonObject
            val strict = case.getValue("strict").jsonObject
            assertEquals(strict.toString(), SchemaDialect.strict(gemini).toString(), name)
            // The vectors were built from the real schemas, so the code's own are the vectors' input.
            assertTrue(same(gemini, schemaFor(name)), "$name: the app's schema is the vectors' input")
            assertEquals(name, SchemaDialect.nameOf(gemini))
        }
    }

    @Test
    fun theStrictSchemaListsEveryPropertyAndForbidsTheRest() {
        val strict = SchemaDialect.strict(OnDeviceAi.LISTING_SCHEMA)
        assertEquals(12, strict.getValue("required").jsonArray.size)
        assertEquals(false, strict.getValue("additionalProperties").jsonPrimitive.booleanOrNull)
        val plan = SchemaDialect.strict(OnDeviceAi.PLAN_SCHEMA)
        val item = plan.getValue("properties").jsonObject.getValue("stops").jsonObject.getValue("items").jsonObject
        assertEquals(listOf("houseId", "reason"), item.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(false, item.getValue("additionalProperties").jsonPrimitive.booleanOrNull)
        assertFalse("nullable" in strict.getValue("properties").jsonObject.getValue("label").jsonObject)
    }

    @Test
    fun openaiRequestMatchesTheVectorsForEveryTier() {
        val all = cases("openaiRequest")
        assertTrue(all.size >= 8)
        for (case in all) {
            val call = case.getValue("call").jsonPrimitive.content
            val tier = case.getValue("tier").jsonPrimitive.int
            val expected = case.getValue("expected").jsonObject
            val body = OpenAiCompatClient.requestBody(
                name = call, tier = tier, model = case.getValue("model").jsonPrimitive.content,
                system = case.getValue("system").jsonPrimitive.content, user = case.getValue("user").jsonPrimitive.content,
                temperature = case.getValue("temperature").jsonPrimitive.doubleOrNull!!, schema = schemaFor(call),
                maxTokens = expected.getValue("max_tokens").jsonPrimitive.int,
            )
            assertTrue(same(expected, body), "$call tier $tier:\nexpected $expected\nwas      $body")
        }
    }

    @Test
    fun openaiContentMatchesTheVectors() {
        for (case in cases("openaiContent")) {
            val response = case.getValue("response").jsonPrimitive.content
            val expected = case.getValue("expected").jsonObject
            val error = expected["error"]?.jsonPrimitive?.content
            if (error == null) {
                assertEquals(expected.getValue("text").jsonPrimitive.content, OpenAiCompatClient.contentOf(response), response)
            } else {
                assertEquals("unavailable", error)
                val e = assertFailsWith<ApiException>(response) { OpenAiCompatClient.contentOf(response) }
                assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind, response)
            }
        }
    }

    /** The vectors' name for what a failed call became. */
    private fun kindOf(e: ApiException): String = when (e.kind) {
        ApiException.Kind.AI_KEY_REJECTED -> "keyRejected"
        ApiException.Kind.AI_MODEL_NOT_FOUND -> "modelNotFound"
        ApiException.Kind.RATE_LIMITED -> "rateLimited"
        ApiException.Kind.AI_UNAVAILABLE -> if (e.code == OpenAiCompatClient.UNREACHABLE) "unreachable" else "unavailable"
        else -> e.kind.name
    }

    @Test
    fun providerErrorsMatchTheVectors() {
        val all = errorCases("openai")
        assertEquals(25, all.size)
        for (case in all) {
            val tier = case.getValue("tier").jsonPrimitive.int
            val status = case.getValue("status").jsonPrimitive.int
            val body = case.getValue("body").jsonPrimitive.content
            val retryAfter = case["retryAfter"]?.jsonPrimitive?.contentOrNull
            val expected = case.getValue("expected").jsonObject
            val label = "tier $tier, HTTP $status, $body"
            val step = OpenAiCompatClient.next(tier, status, body, retryAfter)
            when (expected.getValue("action").jsonPrimitive.content) {
                "nextTier" -> assertEquals(expected.getValue("tier").jsonPrimitive.int, assertIs<OpenAiCompatClient.Companion.Step.Down>(step, label).tier, label)
                else -> {
                    val e = assertIs<OpenAiCompatClient.Companion.Step.Fail>(step, label).error
                    assertEquals(expected.getValue("kind").jsonPrimitive.content, kindOf(e), label)
                    val seconds = expected["retryAfterSeconds"]
                    if (seconds != null) {
                        assertEquals(if (seconds is JsonNull) null else seconds.jsonPrimitive.longOrNull, e.retryAfterSeconds, label)
                    }
                }
            }
        }
    }

    @Test
    fun anthropicRequestMatchesTheVectorsForEveryCall() {
        val all = cases("anthropicRequest")
        assertEquals(listOf("listing", "answer", "plan", "ping", "listing", "answer"), all.map { it.getValue("call").jsonPrimitive.content })
        for (case in all) {
            val call = case.getValue("call").jsonPrimitive.content
            val expected = case.getValue("expected").jsonObject
            val body = AnthropicClient.requestBody(
                name = call, model = case.getValue("model").jsonPrimitive.content,
                system = case.getValue("system").jsonPrimitive.content, user = case.getValue("user").jsonPrimitive.content,
                temperature = case.getValue("temperature").jsonPrimitive.doubleOrNull!!, schema = schemaFor(call),
                maxTokens = expected.getValue("max_tokens").jsonPrimitive.int,
                forceTool = case["forceTool"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
            assertTrue(same(expected, body), "$call:\nexpected $expected\nwas      $body")
        }
    }

    @Test
    fun geminiRequestMatchesTheVectorsForEveryCallAndSetting() {
        val all = cases("geminiRequest")
        assertEquals(
            listOf("answer/quality", "answer/balanced", "answer/economy", "plan/economy", "ping/quality", "ping/economy"),
            all.map { it.getValue("call").jsonPrimitive.content + "/" + it.getValue("quality").jsonPrimitive.content },
        )
        for (case in all) {
            val call = case.getValue("call").jsonPrimitive.content
            val quality = AiQuality.fromWire(case.getValue("quality").jsonPrimitive.content)
            val expected = case.getValue("expected").jsonObject
            val schema = if (call == "ping") OnDeviceAi.PING_SCHEMA else schemaFor(call)!!
            val body = GeminiClient.requestBody(
                system = case.getValue("system").jsonPrimitive.content, user = case.getValue("user").jsonPrimitive.content,
                schema = schema, temperature = case.getValue("temperature").jsonPrimitive.doubleOrNull!!, quality = quality,
            )
            assertTrue(same(expected, body), "$call/${quality.wire}:\nexpected $expected\nwas      $body")
        }
    }

    @Test
    fun anthropicToolChoiceMatchesTheVectors() {
        val all = cases("anthropicToolChoice")
        assertTrue(all.size >= 8)
        for (case in all) {
            val status = case.getValue("status").jsonPrimitive.int
            val body = case.getValue("body").jsonPrimitive.content
            val expected = case.getValue("expected").jsonObject.getValue("retryWithAuto").jsonPrimitive.boolean
            assertEquals(expected, AnthropicClient.retryWithAuto(status, body), "$status $body")
        }
    }

    @Test
    fun anthropicContentMatchesTheVectors() {
        val all = cases("anthropicContent")
        assertTrue(all.size >= 16)
        for (case in all) {
            val call = case.getValue("call").jsonPrimitive.content
            val response = case.getValue("response").jsonPrimitive.content
            val expected = case.getValue("expected").jsonObject
            val error = expected["error"]?.jsonPrimitive?.content
            if (error == null) {
                if (call == "ping") AnthropicClient.pingAnswered(response)
                else assertEquals(expected.getValue("text").jsonPrimitive.content, AnthropicClient.contentOf(response), response)
            } else {
                assertEquals("unavailable", error)
                val e = assertFailsWith<ApiException>(response) {
                    if (call == "ping") AnthropicClient.pingAnswered(response) else AnthropicClient.contentOf(response)
                }
                assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind, response)
            }
        }
    }

    @Test
    fun anthropicErrorsMatchTheVectors() {
        val all = errorCases("anthropic")
        assertEquals(12, all.size)
        for (case in all) {
            val status = case.getValue("status").jsonPrimitive.int
            val retryAfter = case["retryAfter"]?.jsonPrimitive?.contentOrNull
            val expected = case.getValue("expected").jsonObject
            val label = "HTTP $status, $retryAfter"
            // Status 0 is a call that never got an answer; postAiJson words it (AiClientTransportTest), not the status map.
            if (status == 0) {
                assertEquals("unreachable", expected.getValue("kind").jsonPrimitive.content)
                continue
            }
            val e = AnthropicClient.failure(status, retryAfter)
            assertEquals(expected.getValue("kind").jsonPrimitive.content, kindOf(e), label)
            val seconds = expected["retryAfterSeconds"]
            if (seconds != null) {
                assertEquals(if (seconds is JsonNull) null else seconds.jsonPrimitive.longOrNull, e.retryAfterSeconds, label)
            }
        }
    }

    @Test
    fun baseUrlMatchesTheVectors() {
        val all = cases("baseUrl")
        assertTrue(all.size >= 30)
        for (case in all) {
            val input = case.getValue("input").jsonPrimitive.content
            val android = case["android"]?.jsonPrimitive?.booleanOrNull == true
            val expected = case.getValue("expected").jsonObject
            val result = BaseUrlValidator.check(input, android)
            val label = "'$input' (android=$android)"
            if (expected.getValue("valid").jsonPrimitive.booleanOrNull == true) {
                val valid = assertIs<BaseUrlCheck.Valid>(result, label)
                assertEquals(expected.getValue("normalised").jsonPrimitive.content, valid.normalised, label)
                assertEquals(expected.getValue("host").jsonPrimitive.content, valid.host, label)
            } else {
                assertEquals(expected.getValue("reason").jsonPrimitive.content, assertIs<BaseUrlCheck.Invalid>(result, label).reason.wire, label)
            }
        }
    }

    @Test
    fun theEmulatorsNameForTheComputerIsAllowedOnAndroidOnly() {
        val url = "http://10.0.2.2:11434/v1"
        assertIs<BaseUrlCheck.Invalid>(BaseUrlValidator.check(url))
        assertIs<BaseUrlCheck.Valid>(BaseUrlValidator.check(url, android = true))
        // Android does not open the door to the rest of the network.
        assertEquals(BaseUrlReason.INSECURE_HOST, assertIs<BaseUrlCheck.Invalid>(BaseUrlValidator.check("http://192.168.1.20/v1", android = true)).reason)
    }

    @Test
    fun theSixPresetsFillTheAddressesOfAdr35AndTheyAllPassTheValidator() {
        assertEquals(
            listOf("openai", "openrouter", "groq", "ollama", "lmstudio", "custom"),
            AiPreset.ALL.map { it.id },
        )
        assertEquals("https://api.openai.com/v1", AiPreset.OPENAI.baseUrl)
        assertEquals("https://openrouter.ai/api/v1", AiPreset.OPENROUTER.baseUrl)
        assertEquals("https://api.groq.com/openai/v1", AiPreset.GROQ.baseUrl)
        assertEquals("http://localhost:11434/v1", AiPreset.OLLAMA.baseUrl)
        assertEquals("http://localhost:1234/v1", AiPreset.LM_STUDIO.baseUrl)
        assertEquals(listOf("ollama", "lmstudio"), AiPreset.ALL.filter { it.keyOptional }.map { it.id })
        for (preset in AiPreset.ALL.filter { it.baseUrl.isNotEmpty() }) {
            assertEquals(preset.baseUrl, assertIs<BaseUrlCheck.Valid>(BaseUrlValidator.check(preset.baseUrl)).normalised, preset.id)
        }
    }

    @Test
    fun theConfigsToStringNeverPrintsTheAddressOrAKey() {
        val config = AiProviderConfig(AiKind.OPENAI_COMPATIBLE, "http://localhost:11434/v1", "llama3.2")
        val printed = config.toString()
        assertFalse("localhost" in printed || "11434" in printed || "llama3.2" in printed, printed)
        assertTrue("openai-compatible" in printed, printed)
        assertEquals("gemini", AiProviderConfig.GEMINI.kind.wire)
    }

    @Test
    fun aSavedKindThatIsNotKnownReadsAsGeminiAndAnthropicIsKnown() {
        assertEquals(AiKind.GEMINI, AiKind.fromWire(null))
        assertEquals(AiKind.GEMINI, AiKind.fromWire("something"))
        assertEquals(AiKind.OPENAI_COMPATIBLE, AiKind.fromWire("openai-compatible"))
        assertEquals(AiKind.ANTHROPIC, AiKind.fromWire("anthropic"))
        assertEquals("anthropic", AiKind.ANTHROPIC.wire)
    }
}
