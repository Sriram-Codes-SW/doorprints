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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Anthropic's Messages API (`POST {baseUrl}/v1/messages`), called straight from this device with the person's own key
 * (docs/03 §13.2, ADR-35). The key goes only in an `x-api-key` header to [baseUrl] (never in a URL) and is required:
 * without one nothing is sent and the call is a refused key. The answer is forced through one tool whose input schema is
 * the call's strict schema ([SchemaDialect]); the answer is that tool call's `input`. Failures are the [ApiException]s
 * the screens already word ([aiFailure]); a network failure, a timeout and a redirect are [postAiJson]'s. Neither
 * prompts, answers nor the key are logged.
 */
class AnthropicClient(
    private val http: HttpClient,
    private val model: String,
    private val apiKey: String,
    private val baseUrl: String = BASE_URL,
    /** Null turns the limit off (tests, whose virtual clock would end it at once). */
    private val timeoutMs: Long? = 60_000,
) : JsonChatModel {
    /**
      * Asks for [schema] as a forced tool call and returns the tool input as JSON text. A missing key, a refused key or
      * an unusable answer is an [ApiException].
     */
    override suspend fun generateJson(system: String, user: String, schema: JsonObject, temperature: Double): String {
        val body = requestBody(SchemaDialect.nameOf(schema), model, system, user, temperature, schema)
        val response = post(body)
        if (response.status !in 200..299) throw failure(response.status, response.retryAfter)
        return contentOf(response.body)
    }

    /** One call with 5 tokens and no tool: the key and the model work when it answers (it may stop at the limit). */
    override suspend fun ping() {
        val body = requestBody("ping", model, "Reply with {\"ok\": true}.", "ping", 0.0, schema = null, maxTokens = 5)
        val response = post(body)
        if (response.status !in 200..299) throw failure(response.status, response.retryAfter)
        pingAnswered(response.body)
    }

    private suspend fun post(body: JsonObject): AiReply {
        val key = apiKey.trim()
        if (key.isEmpty()) throw ApiException(ApiException.Kind.AI_KEY_REJECTED, 401)
        return postAiJson(http, "$baseUrl/v1/messages", mapOf("x-api-key" to key, "anthropic-version" to VERSION), body, timeoutMs)
    }

    companion object {
        const val BASE_URL = "https://api.anthropic.com"
        const val VERSION = "2023-06-01"
        const val MAX_TOKENS = 2048

        /** The tool's description (the vectors' `anthropicRequest`); the model is told what the tool is for. */
        private const val TOOL_DESCRIPTION = "Reply by calling this tool with the answer."
        private val json = Json { ignoreUnknownKeys = true }

        internal fun failure(status: Int, retryAfter: String?): ApiException = aiFailure(status, retryAfter)

        /**
         * The request body (docs/03 §13.2): the prompts, and for a [schema] one tool named [name] with its strict schema
         * as `input_schema`, chosen by `tool_choice` so the model has to answer through it. No [schema] (the ping) adds neither.
         */
        internal fun requestBody(
            name: String, model: String, system: String, user: String, temperature: Double,
            schema: JsonObject?, maxTokens: Int = MAX_TOKENS,
        ): JsonObject = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("system", system)
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", user) }) })
            if (schema != null) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("name", name)
                        put("description", TOOL_DESCRIPTION)
                        put("input_schema", SchemaDialect.strict(schema))
                    })
                })
                put("tool_choice", buildJsonObject { put("type", "tool"); put("name", name) })
            }
        }

        private fun unavailable() = ApiException(ApiException.Kind.AI_UNAVAILABLE, 502)

        private fun message(body: String): JsonObject =
            runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw unavailable()

        /**
         * The first `tool_use` block's `input` as compact JSON text. Unavailable when there is none (the model refused or
         * wrote text), when its input is not an object, or when the answer stopped at the token limit (a cut-off input).
         */
        internal fun contentOf(body: String): String {
            val reply = message(body)
            if ((reply["stop_reason"] as? JsonPrimitive)?.content == "max_tokens") throw unavailable()
            val blocks = reply["content"] as? JsonArray ?: throw unavailable()
            val call = blocks.firstOrNull { (it as? JsonObject)?.get("type")?.let { t -> (t as? JsonPrimitive)?.content } == "tool_use" }
            return (call as? JsonObject)?.get("input")?.let { it as? JsonObject }?.toString() ?: throw unavailable()
        }

        /** A ping is answered when the reply is a message with a `content` list, whatever it holds. */
        internal fun pingAnswered(body: String) {
            if (message(body)["content"] !is JsonArray) throw unavailable()
        }
    }
}
