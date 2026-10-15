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
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sarvam speech to text (voice PR 7, S4b-BL-224, ADR-37, TC-U-265): the `sarvamRequest` and `sarvamContent` vectors the
 * website runs too, then the adapter through a fake engine: one multipart call to api.sarvam.ai with the key in
 * `api-subscription-key` only, the 30 second cap, and that neither the audio, the words nor the key reach an error.
 * Common code, so the iPhone's simulator runs it as well.
 */
class SarvamTranscriberTest {
    private val root = Json.parseToJsonElement(PARITY_VECTORS_JSON).jsonObject
    private val audio = "FAKE-AUDIO-not-speech-98450-12345-Beltola".encodeToByteArray()
    /** Built at run time: no key-looking literal is committed. */
    private val fakeKey = listOf("sarvam", "fake", "key", "0001").joinToString("-")
    private val fakeKey2 = listOf("sarvam", "fake", "key", "9999").joinToString("-")
    private val spoken = "my number is nine eight four five zero one two three four five"

    private fun rows(section: String, provider: String) =
        root.getValue(section).jsonArray.map { it.jsonObject }.filter { it.getValue("provider").jsonPrimitive.content == provider }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun ok(transcript: String = spoken, language: String? = "en-IN") =
        """{"request_id":"r","transcript":"$transcript","language_code":${if (language == null) "null" else "\"$language\""}}"""

    private fun engine(requests: MutableList<HttpRequestData>, status: HttpStatusCode = HttpStatusCode.OK, body: String = ok()) =
        MockEngine { request ->
            requests += request
            respond(body, status, Headers.build { append(HttpHeaders.ContentType, "application/json") })
        }

    private fun sarvam(engine: MockEngine, key: String = fakeKey) = SarvamTranscriber(ApiHttp.client(engine), key, timeoutMs = null)

    @Test
    fun theRequestMatchesTheVectors() = runTest {
        val list = rows("sarvamRequest", "sarvam")
        assertEquals(6, list.size)
        for (case in list) {
            val expected = case.getValue("expected").jsonObject
            val label = case.getValue("mime").jsonPrimitive.content
            val mime = AudioClip.mimeOf(label)!!
            val fields = SarvamTranscriber.fields(mime, case.text("langHint"))
            val want = expected.getValue("fields").jsonArray.map { it.jsonObject }.map {
                AiFormField(it.getValue("name").jsonPrimitive.content, it.text("value"), it.text("fileName"), it.text("contentType"))
            }
            assertEquals(want, fields, label)
            val key = case.getValue("apiKey").jsonPrimitive.content
            assertEquals(expected.getValue("headers").jsonObject.mapValues { it.value.jsonPrimitive.content }, SarvamTranscriber.headers(key), label)
            val requests = mutableListOf<HttpRequestData>()
            sarvam(engine(requests), key).transcribe(audio, label, case.text("langHint"), null)
            assertEquals(expected.getValue("url").jsonPrimitive.content, requests.single().url.toString(), label)
            assertEquals(key, requests.single().headers["api-subscription-key"], label)
            assertNull(requests.single().headers[HttpHeaders.Authorization], label)
        }
    }

    @Test
    fun theThirtySecondCapAndTheOtherClipChecksMatchTheVectors() {
        for (case in rows("sarvamRequest", "clip")) {
            val size = case.getValue("size").jsonPrimitive.int
            val mime = case.getValue("mime").jsonPrimitive.content
            val duration = (case["durationMs"] as? JsonPrimitive)?.longOrNull
            val expected = case.getValue("expected").jsonObject
            val label = "$size bytes, '$mime', $duration ms"
            val cap = AudioClip.SARVAM_MAX_DURATION_MS
            val rejected = expected.text("rejected")
            if (rejected == null) {
                assertNull(AudioClip.problemOf(size, mime, duration, cap), label)
                assertEquals(expected.getValue("mime").jsonPrimitive.content, AudioClip.check(size, mime, duration, cap), label)
            } else {
                assertEquals(rejected, AudioClip.problemOf(size, mime, duration, cap)?.wire, label)
            }
        }
        assertEquals(30_000L, AudioClip.SARVAM_MAX_DURATION_MS)
        assertTrue(AudioClip.SARVAM_MAX_DURATION_MS < AudioClip.MAX_DURATION_MS)
    }

    @Test
    fun theAnswersMatchTheVectors() {
        val list = rows("sarvamContent", "sarvam")
        assertEquals(20, list.size)
        for (case in list) {
            val status = case.getValue("status").jsonPrimitive.int
            val body = case.getValue("body").jsonPrimitive.content
            val expected = case.getValue("expected").jsonObject
            val error = expected.text("error")
            val label = "HTTP $status $body"
            if (error == null) {
                assertEquals(
                    Transcript(expected.getValue("text").jsonPrimitive.content, expected.getValue("language").jsonPrimitive.content),
                    SarvamTranscriber.transcriptOf(status, body, case.text("retryAfter"), case.text("langHint")), label,
                )
            } else {
                val e = assertFailsWith<ApiException>(label) { SarvamTranscriber.transcriptOf(status, body, case.text("retryAfter"), case.text("langHint")) }
                val kind = when (e.kind) {
                    ApiException.Kind.AI_KEY_REJECTED -> "keyRejected"
                    ApiException.Kind.AI_MODEL_NOT_FOUND -> "modelNotFound"
                    ApiException.Kind.RATE_LIMITED -> "rateLimited"
                    ApiException.Kind.AI_UNAVAILABLE -> if (e.code == OpenAiCompatClient.UNREACHABLE) "unreachable" else "unavailable"
                    else -> e.kind.name
                }
                assertEquals(error, kind, label)
                val seconds = expected["retryAfterSeconds"]
                if (seconds != null && seconds !is JsonNull) assertEquals(seconds.jsonPrimitive.long, e.retryAfterSeconds, label)
            }
        }
    }

    @Test
    fun theLanguageCodesAreHiTaTeOrUnknown() {
        assertEquals(
            listOf("hi-IN", "ta-IN", "te-IN", "unknown", "unknown", "unknown", "unknown"),
            listOf("hi", "ta-IN", "te_IN", "en-IN", "fr", null, "").map { SarvamTranscriber.languageCode(it) },
        )
    }

    @Test
    fun oneMultipartCallToSarvamWithTheKeyInTheHeaderOnly() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val heard = sarvam(engine(requests, body = ok(spoken, "hi-IN"))).transcribe(audio, "audio/webm;codecs=opus", "hi-IN", 12_000)
        assertEquals(Transcript(spoken, "hi-IN"), heard)
        val request = requests.single()
        assertEquals("https://api.sarvam.ai/speech-to-text", request.url.toString())
        assertEquals(fakeKey, request.headers["api-subscription-key"])
        assertFalse(fakeKey in request.url.toString())
        val posted = request.body.toByteArray().decodeToString()
        assertFalse(fakeKey in posted, "the key is in the body")
        assertTrue("language_code" in posted && "hi-IN" in posted && "FAKE-AUDIO" in posted)
    }

    @Test
    fun aClipOverThirtySecondsIsRefusedBeforeAnythingIsSentAndThirtyExactlyIsSent() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val t = sarvam(engine(requests))
        assertEquals(AudioClipRejected.Reason.TOO_LONG, assertFailsWith<AudioClipRejected> { t.transcribe(audio, "audio/webm", null, 30_001) }.reason)
        assertEquals(AudioClipRejected.Reason.EMPTY, assertFailsWith<AudioClipRejected> { t.transcribe(ByteArray(0), "audio/webm", null, 1_000) }.reason)
        assertEquals(AudioClipRejected.Reason.UNSUPPORTED_TYPE, assertFailsWith<AudioClipRejected> { t.transcribe(audio, "video/webm", null, 1_000) }.reason)
        assertTrue(requests.isEmpty())
        t.transcribe(audio, "audio/webm", null, 30_000)
        assertEquals(1, requests.size)
    }

    @Test
    fun neitherTheAudioTheWordsNorTheKeyIsInAnError() = runTest {
        for ((status, body) in listOf(401 to "", 500 to spoken, 200 to "not json")) {
            val e = assertFailsWith<ApiException> {
                sarvam(engine(mutableListOf(), HttpStatusCode.fromValue(status), body), fakeKey2).transcribe(audio, "audio/ogg", "ta", 2_000)
            }
            val printed = "$e ${e.message}"
            for (secret in listOf("FAKE-AUDIO", spoken, "98450", fakeKey2)) assertFalse(secret in printed, secret)
        }
    }

    @Test
    fun theSarvamChatPresetTakesNoOpenAiStyleAudio() {
        val config = AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.SARVAM.baseUrl)
        assertEquals("sarvam", TranscribeCapability.presetOf(config))
        assertEquals(TranscribeSupport.NO, TranscribeCapability.support(config))
        assertFalse(TranscribeCapability.canTranscribe(config, customOptIn = true))
    }
}
