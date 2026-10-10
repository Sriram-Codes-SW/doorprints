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
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * Speech to text with Gemini and the person's own key (ADR-37): one `generateContent` call whose user message is the
 * audio as `inlineData` and a short line, with [TRANSCRIBE_INSTRUCTION] as the system instruction and a response schema
 * of `{text, language}`, no tools. The key goes only in `x-goog-api-key`, to Google; the call goes through [postAiJson]
 * (time limit, no redirect). A refused call is [aiFailure]'s error, except that a 400 naming an invalid key is a refused
 * key, as for [GeminiClient]. Neither the audio nor the transcript is logged or put in an error.
 *
 * [model] is the provider configuration's (the `gemini` kind has none of its own, so [GeminiClient.MODEL]). The key
 * check of 2026-10-10 (S4b-BL-217) found `gemini-3.5-transcribe` listed for the owner's key; it is not the default here
 * and is not verified for this request (docs/ai/voice-input.md §7).
 */
class GeminiTranscriber(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String = GeminiClient.MODEL,
    private val baseUrl: String = GeminiClient.BASE_URL,
    /** Null turns the limit off (tests, whose virtual clock would end it at once). */
    private val timeoutMs: Long? = 60_000,
) : Transcriber {
    override suspend fun transcribe(bytes: ByteArray, mime: String, langHint: String?, durationMs: Long?): Transcript {
        val type = AudioClip.check(bytes.size, mime, durationMs)
        val body = requestBody(Base64.Default.encode(bytes), type, langHint)
        val reply = postAiJson(http, url(model, baseUrl), mapOf("x-goog-api-key" to apiKey), body, timeoutMs)
        return transcriptOf(reply.status, reply.body, reply.retryAfter, langHint)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The schema of the answer, in Gemini's dialect. */
        val TRANSCRIPT_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("text", buildJsonObject { put("type", "string"); put("description", "The words heard, as spoken") })
                put("language", buildJsonObject {
                    put("type", "string"); put("description", "The spoken language as a BCP 47 code, e.g. hi-IN")
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("text")); add(JsonPrimitive("language")) })
        }

        internal fun url(model: String, baseUrl: String = GeminiClient.BASE_URL) = "$baseUrl/models/$model:generateContent"

        /** The line beside the audio: the language part of [langHint] when there is one. */
        internal fun userText(langHint: String?): String =
            AudioClip.languageOf(langHint)?.let { "Transcribe the audio. Language hint: $it." } ?: "Transcribe the audio."

        /**
         * The body of the call (the `transcribeRequest` vectors, with a placeholder for [audioBase64]). [mime] is already
         * [AudioClip.mimeOf]'s. Temperature 0 and the chat adapter's answer budget; no thinking setting.
         */
        internal fun requestBody(audioBase64: String, mime: String, langHint: String?): JsonObject = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", TRANSCRIBE_INSTRUCTION) }) })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("inlineData", buildJsonObject { put("mimeType", mime); put("data", audioBase64) })
                        })
                        add(buildJsonObject { put("text", userText(langHint)) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0)
                put("maxOutputTokens", GeminiClient.MAX_OUTPUT_TOKENS)
                put("responseMimeType", "application/json")
                put("responseSchema", TRANSCRIPT_SCHEMA)
            })
        }

        /**
         * What an answer means (the `transcribeContent` vectors, provider `gemini`): a 2xx whose first candidate's text
         * is a JSON object with a string `text`, or an error. A 2xx with no candidate (a blocked request today) is
         * unavailable.
         */
        internal fun transcriptOf(status: Int, body: String, retryAfter: String?, langHint: String?): Transcript {
            if (status !in 200..299) throw failure(status, body, retryAfter)
            val answer = runCatching {
                val candidate = json.parseToJsonElement(body).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                val parts = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
                parts?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
                    ?.let { json.parseToJsonElement(it).jsonObject }
            }.getOrNull() ?: throw noTranscript()
            val text = answer["text"] as? JsonPrimitive
            if (text == null || !text.isString) throw noTranscript()
            val language = (answer["language"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return Transcript(text.content.trim(), transcriptLanguage(language, langHint))
        }

        /** [aiFailure], and a 400 that names an invalid key is a refused key (as [GeminiClient]). */
        internal fun failure(status: Int, body: String, retryAfter: String?): ApiException =
            if (status == 400 && ("API_KEY_INVALID" in body || "API key not valid" in body)) {
                ApiException(ApiException.Kind.AI_KEY_REJECTED, status)
            } else {
                aiFailure(status, retryAfter)
            }
    }
}
