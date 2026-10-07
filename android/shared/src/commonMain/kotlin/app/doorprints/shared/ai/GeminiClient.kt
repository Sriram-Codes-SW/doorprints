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
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Gemini called straight from this phone with the person's own key (docs/03 §13.1, ADR-26): the native
 * `generateContent` API with a system instruction, one user message and a JSON response schema. The key goes only in
 * the `x-goog-api-key` header (never in the URL) and only to Google. Failures are [ApiException]s the screens already
 * word: 429 is [ApiException.Kind.RATE_LIMITED] ("out of free quota"), a refused key
 * [ApiException.Kind.AI_KEY_REJECTED], anything else from Google or a timeout [ApiException.Kind.AI_UNAVAILABLE].
 * Neither prompts nor answers are logged.
 */
class GeminiClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String = MODEL,
    private val baseUrl: String = BASE_URL,
    /** Null turns the limit off (tests, whose virtual clock would end it at once). */
    private val timeoutMs: Long? = 60_000,
) : JsonChatModel {
    companion object {
        const val MODEL = "gemini-3.5-flash"
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val MAX_OUTPUT_TOKENS = 2048
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** The model's JSON answer as text, for [schema] (an OpenAPI-style object schema). */
    override suspend fun generateJson(system: String, user: String, schema: JsonObject, temperature: Double): String {
        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) }) })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", user) }) })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", temperature)
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                put("responseMimeType", "application/json")
                put("responseSchema", schema)
            })
        }
        suspend fun send() = http.post("$baseUrl/models/$model:generateContent") {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val response = if (timeoutMs == null) send() else withTimeoutOrNull(timeoutMs) { send() }
            ?: throw ApiException(ApiException.Kind.AI_UNAVAILABLE, 504)
        val status = response.status.value
        val text = response.bodyAsText()
        if (status !in 200..299) throw failure(status, text)
        val candidate = runCatching { json.parseToJsonElement(text).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject }
            .getOrNull() ?: throw ApiException(ApiException.Kind.AI_UNAVAILABLE, 502)
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: throw ApiException(ApiException.Kind.AI_UNAVAILABLE, 502)
        return parts.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
    }

    /** *Test*: one tiny call that proves Google accepts the key. */
    override suspend fun ping() {
        generateJson("Reply with {\"ok\": true}.", "ping", OnDeviceAi.PING_SCHEMA, 0.0)
    }

    private fun failure(status: Int, body: String): ApiException = when {
        status == 429 -> ApiException(ApiException.Kind.RATE_LIMITED, 429, retryAfterSeconds = 60)
        status == 403 || (status == 400 && ("API_KEY_INVALID" in body || "API key not valid" in body)) ->
            ApiException(ApiException.Kind.AI_KEY_REJECTED, status)
        else -> ApiException(ApiException.Kind.AI_UNAVAILABLE, status)
    }
}
