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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The voice sections of the shared AI test vectors (`docs/ai/evals/parity-vectors.json`, `transcribeRequest` and
 * `transcribeContent`; voice PR 2, S4b-BL-219, ADR-37, TC-U-194) run through the Kotlin code. The website runs the same
 * rows through its TypeScript (`transcriber.spec.ts`). Common code, so the iPhone's simulator runs them too.
 */
class TranscribeVectorsTest {
    private val root = Json.parseToJsonElement(PARITY_VECTORS_JSON).jsonObject

    private fun rows(section: String, provider: String) =
        root.getValue(section).jsonArray.map { it.jsonObject }.filter { it.getValue("provider").jsonPrimitive.content == provider }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** JSON equality with keys in order, where `0` and `0.0` are the same number. */
    private fun same(a: JsonElement?, b: JsonElement?): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys.toList() == b.keys.toList() && a.keys.all { same(a[it], b[it]) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a.doubleOrNull != null ->
            a.doubleOrNull == b.doubleOrNull
        else -> a == b
    }

    @Test
    fun theVoiceSectionsHaveTheirRowsAndEveryProviderIsCovered() {
        assertEquals(3, rows("transcribeRequest", "gemini").size)
        assertEquals(5, rows("transcribeRequest", "openai").size)
        assertEquals(20, rows("transcribeRequest", "clip").size)
        assertEquals(12, rows("transcribeRequest", "capability").size)
        assertEquals(23, rows("transcribeContent", "gemini").size)
        assertEquals(22, rows("transcribeContent", "openai").size)
    }

    @Test
    fun geminiTranscribeRequestMatchesTheVectors() {
        for (case in rows("transcribeRequest", "gemini")) {
            val expected = case.getValue("expected").jsonObject
            val mime = AudioClip.mimeOf(case.getValue("mime").jsonPrimitive.content)!!
            val body = GeminiTranscriber.requestBody(case.getValue("audioBase64").jsonPrimitive.content, mime, case.text("langHint"))
            val label = case.getValue("mime").jsonPrimitive.content
            assertTrue(same(expected.getValue("body"), body), "$label:\nexpected ${expected.getValue("body")}\nwas      $body")
            assertEquals(expected.getValue("url").jsonPrimitive.content, GeminiTranscriber.url(case.getValue("model").jsonPrimitive.content), label)
        }
    }

    @Test
    fun openaiTranscribeRequestMatchesTheVectors() {
        for (case in rows("transcribeRequest", "openai")) {
            val expected = case.getValue("expected").jsonObject
            val label = case.getValue("baseUrl").jsonPrimitive.content
            val mime = AudioClip.mimeOf(case.getValue("mime").jsonPrimitive.content)!!
            val fields = OpenAiAudioTranscriber.fields(
                mime, case.getValue("model").jsonPrimitive.content, case.text("prompt"), case.text("langHint"),
            )
            val expectedFields = expected.getValue("fields").jsonArray.map { it.jsonObject }.map {
                AiFormField(it.getValue("name").jsonPrimitive.content, it.text("value"), it.text("fileName"), it.text("contentType"))
            }
            assertEquals(expectedFields, fields, label)
            assertEquals(expected.getValue("url").jsonPrimitive.content, OpenAiAudioTranscriber.url(case.getValue("baseUrl").jsonPrimitive.content), label)
            val headers = expected.getValue("headers").jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertEquals(headers, OpenAiAudioTranscriber.headers(case.getValue("apiKey").jsonPrimitive.content), label)
        }
    }

    @Test
    fun clipRowsMatchTheChecksMadeBeforeSending() {
        for (case in rows("transcribeRequest", "clip")) {
            val size = case.getValue("size").jsonPrimitive.int
            val mime = case.getValue("mime").jsonPrimitive.content
            val duration = (case["durationMs"] as? JsonPrimitive)?.longOrNull
            val expected = case.getValue("expected").jsonObject
            val label = "$size bytes, '$mime', $duration ms"
            val rejected = expected.text("rejected")
            if (rejected == null) {
                assertNull(AudioClip.problemOf(size, mime, duration), label)
                assertEquals(expected.getValue("mime").jsonPrimitive.content, AudioClip.check(size, mime, duration), label)
                assertEquals(expected.text("language"), AudioClip.languageOf(case.text("langHint")), label)
            } else {
                assertEquals(rejected, AudioClip.problemOf(size, mime, duration)?.wire, label)
                assertEquals(rejected, assertFailsWith<AudioClipRejected>(label) { AudioClip.check(size, mime, duration) }.reason.wire)
            }
        }
    }

    @Test
    fun capabilityRowsMatchTheProviderTable() {
        for (case in rows("transcribeRequest", "capability")) {
            val config = AiProviderConfig(AiKind.fromWire(case.getValue("kind").jsonPrimitive.content), case.getValue("baseUrl").jsonPrimitive.content)
            val optIn = case.getValue("customOptIn").jsonPrimitive.boolean
            val expected = case.getValue("expected").jsonObject
            val label = "${config.kind.wire} '${config.baseUrl}' optIn=$optIn"
            assertEquals(expected.getValue("preset").jsonPrimitive.content, TranscribeCapability.presetOf(config), label)
            assertEquals(expected.getValue("support").jsonPrimitive.content, TranscribeCapability.support(config).wire, label)
            assertEquals(expected.getValue("canTranscribe").jsonPrimitive.boolean, TranscribeCapability.canTranscribe(config, optIn), label)
        }
    }

    /** The vectors' name for what a failed call became. */
    private fun kindOf(e: ApiException): String = when (e.kind) {
        ApiException.Kind.AI_KEY_REJECTED -> "keyRejected"
        ApiException.Kind.AI_MODEL_NOT_FOUND -> "modelNotFound"
        ApiException.Kind.RATE_LIMITED -> "rateLimited"
        ApiException.Kind.AI_UNAVAILABLE -> if (e.code == UNREACHABLE) "unreachable" else "unavailable"
        else -> e.kind.name
    }

    private fun checkContent(provider: String, parse: (Int, String, String?, String?) -> Transcript) {
        for (case in rows("transcribeContent", provider)) {
            val status = case.getValue("status").jsonPrimitive.int
            val body = case.getValue("body").jsonPrimitive.content
            val retryAfter = case.text("retryAfter")
            val hint = case.text("langHint")
            val expected = case.getValue("expected").jsonObject
            val label = "$provider HTTP $status $body"
            val error = expected.text("error")
            if (error == null) {
                assertEquals(
                    Transcript(expected.getValue("text").jsonPrimitive.content, expected.getValue("language").jsonPrimitive.content),
                    parse(status, body, retryAfter, hint), label,
                )
            } else {
                val e = assertFailsWith<ApiException>(label) { parse(status, body, retryAfter, hint) }
                assertEquals(error, kindOf(e), label)
                val seconds = expected["retryAfterSeconds"]
                if (seconds != null && seconds !is JsonNull) assertEquals(seconds.jsonPrimitive.long, e.retryAfterSeconds, label)
            }
        }
    }

    @Test
    fun geminiTranscribeContentMatchesTheVectors() = checkContent("gemini") { s, b, r, h -> GeminiTranscriber.transcriptOf(s, b, r, h) }

    @Test
    fun openaiTranscribeContentMatchesTheVectors() = checkContent("openai") { s, b, r, h -> OpenAiAudioTranscriber.transcriptOf(s, b, r, h) }
}
