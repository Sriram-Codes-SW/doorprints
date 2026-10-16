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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Whether the provider's own safety system declined a call (S4b-BL-232; docs/ai/ai-design.md 15), read from the real
 * response shapes and only from named fields, never from free text, so words the provider echoed (which can be the
 * abusive words the person typed) are neither read, kept nor logged. The text of the person is not filtered or censored
 * anywhere in the app; this is only the provider's verdict, worded as [ApiException.Kind.AI_BLOCKED] ("declined").
 *  - Gemini (`generateContent`, ai.google.dev/api/generate-content, read 2026-10-10): `promptFeedback.blockReason` other
 *    than `BLOCK_REASON_UNSPECIFIED` (the prompt was blocked, no candidates), or a candidate `finishReason` of `SAFETY`,
 *    `PROHIBITED_CONTENT`, `BLOCKLIST`, `SPII` or `RECITATION`. Anything else (`MAX_TOKENS`, `OTHER`, `LANGUAGE`, a
 *    malformed call, no candidates at all) stays [ApiException.Kind.AI_UNAVAILABLE].
 *  - OpenAI-compatible (`chat/completions`): `choices[0].finish_reason` of `content_filter` (OpenAI's SDK types and Azure
 *    OpenAI, learn.microsoft.com content filtering, read 2026-10-10), a non-empty `choices[0].message.refusal`, or an
 *    HTTP 400 whose `error.code` is `content_policy_violation` (OpenAI) or `content_filter` (Azure OpenAI's filtered prompt).
 *  - Anthropic (`/v1/messages`, platform.claude.com handle-streaming-refusals, read 2026-10-10): HTTP 200 with
 *    `stop_reason` `refusal`.
 */
internal object AiBlocked {
    private val json = Json { ignoreUnknownKeys = true }

    private val GEMINI_FINISH = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")
    private val OPENAI_CODES = setOf("content_policy_violation", "content_filter")

    /** The failure for a blocked answer: no text, only the kind and the status. */
    fun failure(status: Int): ApiException = ApiException(ApiException.Kind.AI_BLOCKED, status)

    fun isBlocked(provider: String, status: Int, body: String): Boolean = when (provider) {
        "gemini" -> gemini(status, body)
        "openai" -> openAi(status, body)
        "anthropic" -> anthropic(status, body)
        else -> false
    }

    fun gemini(status: Int, body: String): Boolean {
        if (status !in 200..299) return false
        val root = obj(body) ?: return false
        val reason = (root["promptFeedback"] as? JsonObject)?.get("blockReason").text()
        if (!reason.isNullOrEmpty() && reason != "BLOCK_REASON_UNSPECIFIED") return true
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return false
        return candidate["finishReason"].text() in GEMINI_FINISH
    }

    fun openAi(status: Int, body: String): Boolean {
        val root = obj(body) ?: return false
        if (status == 400) return (root["error"] as? JsonObject)?.get("code").text() in OPENAI_CODES
        if (status !in 200..299) return false
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return false
        if (choice["finish_reason"].text() == "content_filter") return true
        return !(choice["message"] as? JsonObject)?.get("refusal").text().isNullOrEmpty()
    }

    fun anthropic(status: Int, body: String): Boolean =
        status in 200..299 && obj(body)?.get("stop_reason").text() == "refusal"

    private fun obj(body: String): JsonObject? = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement?.text(): String? =
        if (this is JsonPrimitive && this !is JsonNull && isString) contentOrNull else null
}
