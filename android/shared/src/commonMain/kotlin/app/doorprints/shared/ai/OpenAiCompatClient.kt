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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Any server that speaks `POST {baseUrl}/chat/completions` (OpenAI, OpenRouter, Groq, Ollama, LM Studio and more), called
 * straight from this device with the person's own key (docs/03 §13.2, ADR-35). [baseUrl] is already checked with
 * [BaseUrlValidator]; the key goes only in an `Authorization: Bearer` header, and only when there is one (a model on
 * the person's own computer needs none). Neither prompts, answers nor the key are logged.
 *
 * Not every server honours `json_schema`, so there are three tiers (strict schema, `json_object`, plain text with the
 * schema in the prompt); the first one a server accepts is kept for this instance, which the repository keeps per
 * address, model and key. Failures are [ApiException]s the screens already word.
 */
class OpenAiCompatClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String = "",
    /** Null turns the limit off (tests, whose virtual clock would end it at once). */
    private val timeoutMs: Long? = 60_000,
) : JsonChatModel {
    /** The tier that worked last, or 1 until one has. */
    internal var tier: Int = 1
        private set

    override suspend fun generateJson(system: String, user: String, schema: JsonObject, temperature: Double): String {
        var current = tier
        while (true) {
            val body = requestBody(SchemaDialect.nameOf(schema), current, model, system, user, temperature, schema)
            val response = post(body)
            val status = response.status
            if (status in 200..299) {
                tier = current
                return contentOf(response.body)
            }
            when (val step = next(current, status, response.body, response.retryAfter)) {
                is Step.Down -> current = step.tier
                is Step.Fail -> throw step.error
            }
        }
    }

    /** One call with no `response_format` and 5 tokens: the URL, the key and the model all work when it answers. */
    override suspend fun ping() {
        val body = requestBody("ping", 3, model, "Reply with {\"ok\": true}.", "ping", 0.0, schema = null, maxTokens = 5)
        val response = post(body)
        val status = response.status
        if (status !in 200..299) throw failure(status, response.retryAfter)
        contentOf(response.body)
    }

    private suspend fun post(body: JsonObject): AiReply = postAiJson(
        http, "$baseUrl/chat/completions",
        if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey"),
        body, timeoutMs,
    )

    companion object {
        const val MAX_TOKENS = 2048

        /** The code of an [ApiException.Kind.AI_UNAVAILABLE] for a call that never got an answer. */
        const val UNREACHABLE = app.doorprints.shared.ai.UNREACHABLE

        /** The words in a 400 body (lower case) that send the call down a tier, by the tier that was refused. */
        private val TRIGGERS = mapOf(
            1 to listOf("response_format", "json_schema", "strict", "unsupported", "not supported"),
            2 to listOf("response_format", "json_object"),
        )
        private val json = Json { ignoreUnknownKeys = true }

        internal sealed interface Step {
            class Down(val tier: Int) : Step
            class Fail(val error: ApiException) : Step
        }

        internal fun next(tier: Int, status: Int, body: String, retryAfter: String?): Step {
            if (status == 400 && tier < 3 && body.lowercase().let { text -> TRIGGERS.getValue(tier).any { it in text } }) {
                return Step.Down(tier + 1)
            }
            return Step.Fail(failure(status, retryAfter))
        }

        internal fun failure(status: Int, retryAfter: String?): ApiException = when (status) {
            401, 403 -> ApiException(ApiException.Kind.AI_KEY_REJECTED, status)
            404 -> ApiException(ApiException.Kind.AI_MODEL_NOT_FOUND, status)
            429 -> ApiException(ApiException.Kind.RATE_LIMITED, status, retryAfterSeconds = retryAfter?.trim()?.toLongOrNull())
            else -> ApiException(ApiException.Kind.AI_UNAVAILABLE, status)
        }

        /**
         * The request body for [tier] (docs/03 §13.2): 1 asks for the strict schema, 2 for a JSON object with the schema
         * in the system prompt, 3 for nothing but that prompt. No [schema] (the ping) adds neither.
         */
        internal fun requestBody(
            name: String, tier: Int, model: String, system: String, user: String, temperature: Double,
            schema: JsonObject?, maxTokens: Int = MAX_TOKENS,
        ): JsonObject {
            val strict = schema?.let { SchemaDialect.strict(it) }
            val systemText = if (strict != null && tier > 1) "$system\n\nReply with only a JSON object of this shape: $strict" else system
            return buildJsonObject {
                put("model", model)
                put("temperature", temperature)
                put("max_tokens", maxTokens)
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", systemText) })
                    add(buildJsonObject { put("role", "user"); put("content", user) })
                })
                if (strict != null && tier == 1) {
                    put("response_format", buildJsonObject {
                        put("type", "json_schema")
                        put("json_schema", buildJsonObject {
                            put("name", name)
                            put("strict", true)
                            put("schema", strict)
                        })
                    })
                } else if (strict != null && tier == 2) {
                    put("response_format", buildJsonObject { put("type", "json_object") })
                }
            }
        }

        /** `choices[0].message.content` without its whitespace and Markdown fence, or an unavailable error. */
        internal fun contentOf(body: String): String {
            val unavailable = ApiException(ApiException.Kind.AI_UNAVAILABLE, 502)
            val message = runCatching {
                json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            }.getOrNull() ?: throw unavailable
            val content = message["content"] as? JsonPrimitive
            if (content == null || !content.isString) throw unavailable
            return unfenced(content.content)
        }

        internal fun unfenced(text: String): String {
            val t = text.trim()
            if (t.length < 6 || !t.startsWith("```") || !t.endsWith("```")) return t
            val inner = t.substring(3, t.length - 3)
            val firstLine = inner.substringBefore('\n', missingDelimiterValue = "")
            val body = if ('\n' in inner && firstLine.trim().all { it.isLetterOrDigit() }) inner.substringAfter('\n') else inner
            return body.trim()
        }
    }
}
