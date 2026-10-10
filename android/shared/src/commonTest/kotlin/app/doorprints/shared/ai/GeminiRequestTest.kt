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

import app.doorprints.shared.api.ApiHttp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The *AI speed and cost* setting and the Gemini request it shapes (S4b-BL-198 step 2, docs/ai/ai-design.md 13.2, TC-U-192):
 * the stored text read as a choice, the `thinkingLevel` of each choice, the answer budget of 8,192 tokens (the limit
 * includes Gemini 3.x thinking tokens), and that the other services' limits stay as the vectors pin them. The exact bodies
 * are compared with the shared vectors in [AiProviderVectorsTest].
 */
class GeminiRequestTest {
    private val answer = OnDeviceAi.ANSWER_SCHEMA

    // --- the choice -------------------------------------------------------------------------------------------

    @Test
    fun qualityIsTheDefaultAndOffersQualityBalancedAndEconomyInThatOrder() {
        assertEquals(AiQuality.QUALITY, AiQuality.DEFAULT)
        assertEquals(listOf("quality", "balanced", "economy"), AiQuality.entries.map { it.wire })
    }

    @Test
    fun aStoredTextIsAChoiceOnlyWhenItIsExactlyOneOfTheThreeNamesElseQuality() {
        assertEquals(AiQuality.BALANCED, AiQuality.fromWire("balanced"))
        assertEquals(AiQuality.ECONOMY, AiQuality.fromWire("economy"))
        assertEquals(AiQuality.QUALITY, AiQuality.fromWire("quality"))
        for (unknown in listOf(null, "", "Economy", "ECONOMY", " economy", "fast", "LOW", "0")) {
            assertEquals(AiQuality.QUALITY, AiQuality.fromWire(unknown), "'$unknown'")
        }
    }

    @Test
    fun theChoicesMapToGeminisThinkingLevelAndQualityToNone() {
        assertNull(AiQuality.QUALITY.thinkingLevel)
        assertEquals("MEDIUM", AiQuality.BALANCED.thinkingLevel)
        assertEquals("LOW", AiQuality.ECONOMY.thinkingLevel)
    }

    // --- the request ------------------------------------------------------------------------------------------

    private fun config(quality: AiQuality?): JsonObject {
        val body = if (quality == null) GeminiClient.requestBody("s", "u", answer, 0.1) else GeminiClient.requestBody("s", "u", answer, 0.1, quality)
        return body.getValue("generationConfig").jsonObject
    }

    @Test
    fun qualityAndNoSettingSendNoThinkingFieldAtAllSoNothingChangesForAnExistingUser() {
        for (quality in listOf(null, AiQuality.QUALITY)) {
            val body = if (quality == null) GeminiClient.requestBody("s", "u", answer, 0.1) else GeminiClient.requestBody("s", "u", answer, 0.1, quality)
            assertFalse("thinking" in body.toString().lowercase(), "$quality: $body")
            assertFalse("thinkingConfig" in config(quality))
        }
    }

    @Test
    fun balancedAsksForMediumThinkingAndEconomyForLowInsideGenerationConfig() {
        assertEquals("MEDIUM", config(AiQuality.BALANCED).getValue("thinkingConfig").jsonObject.getValue("thinkingLevel").jsonPrimitive.content)
        assertEquals("LOW", config(AiQuality.ECONOMY).getValue("thinkingConfig").jsonObject.getValue("thinkingLevel").jsonPrimitive.content)
        // Nothing else is added to the body: the thinking config is the one extra key, and it goes last.
        assertEquals(
            listOf("temperature", "maxOutputTokens", "responseMimeType", "responseSchema", "thinkingConfig"),
            config(AiQuality.ECONOMY).keys.toList(),
        )
        assertEquals(listOf("thinkingLevel"), config(AiQuality.BALANCED).getValue("thinkingConfig").jsonObject.keys.toList())
    }

    @Test
    fun everyChoiceAsksForAnAnswerBudgetOf8192TokensBecauseThinkingTokensCountTowardIt() {
        assertEquals(8192, GeminiClient.MAX_OUTPUT_TOKENS)
        for (quality in AiQuality.entries) {
            assertEquals(8192, config(quality).getValue("maxOutputTokens").jsonPrimitive.int, quality.name)
        }
    }

    @Test
    fun theOtherServicesKeepTheirLimitOf2048() {
        assertEquals(2048, OpenAiCompatClient.MAX_TOKENS)
        assertEquals(2048, AnthropicClient.MAX_TOKENS)
        // And neither of their bodies has a thinking field.
        val openAi = OpenAiCompatClient.requestBody("answer", 1, "m", "s", "u", 0.1, answer, OpenAiCompatClient.MAX_TOKENS)
        val anthropic = AnthropicClient.requestBody("answer", "m", "s", "u", 0.1, answer, AnthropicClient.MAX_TOKENS, true)
        assertFalse("thinking" in openAi.toString().lowercase())
        assertFalse("thinking" in anthropic.toString().lowercase())
    }

    @Test
    fun theClientPostsTheBodyOfItsChoiceWithTheKeyInTheHeaderOnly() = runTest {
        val expected = mapOf(AiQuality.QUALITY to null, AiQuality.BALANCED to "MEDIUM", AiQuality.ECONOMY to "LOW")
        for ((quality, level) in expected) {
            var posted = ""
            var url = ""
            var keyHeader: String? = null
            val engine = MockEngine { request ->
                posted = request.body.toByteArray().decodeToString()
                url = request.url.toString()
                keyHeader = request.headers["x-goog-api-key"]
                respond(
                    """{"candidates":[{"content":{"parts":[{"text":"{\"ok\":true}"}]}}]}""", HttpStatusCode.OK,
                    Headers.build { append("Content-Type", "application/json") },
                )
            }
            GeminiClient(ApiHttp.client(engine), "AIzaTestKey1234", timeoutMs = null, quality = quality).ping()
            val body = Json.parseToJsonElement(posted).jsonObject
            val generation = body.getValue("generationConfig").jsonObject
            assertEquals(level, generation["thinkingConfig"]?.jsonObject?.get("thinkingLevel")?.jsonPrimitive?.content, quality.name)
            assertEquals(8192, generation.getValue("maxOutputTokens").jsonPrimitive.int, quality.name)
            assertEquals("AIzaTestKey1234", keyHeader)
            assertFalse("AIzaTestKey1234" in url || "AIzaTestKey1234" in posted, "the key is in the URL or the body")
            assertTrue(body.getValue("contents").jsonArray.size == 1)
        }
    }

    @Test
    fun theClientDefaultsToQualityWhenNoChoiceIsGiven() = runTest {
        var posted = ""
        val engine = MockEngine { request ->
            posted = request.body.toByteArray().decodeToString()
            respond(
                """{"candidates":[{"content":{"parts":[{"text":"{\"ok\":true}"}]}}]}""", HttpStatusCode.OK,
                Headers.build { append("Content-Type", "application/json") },
            )
        }
        GeminiClient(ApiHttp.client(engine), "AIzaTestKey1234", timeoutMs = null).ping()
        assertFalse("thinking" in posted.lowercase(), posted)
    }
}
