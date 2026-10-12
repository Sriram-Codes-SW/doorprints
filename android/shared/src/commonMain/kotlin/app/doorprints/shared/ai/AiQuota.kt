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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Whether a provider answered that the person's own key has no free quota or credit left (S4b-BL-243; docs/ai/ai-design.md
 * 2.1), read from the status and named fields only, never from free text a provider may have echoed. A quota used up for
 * the day or the month is not a wait of a minute, so it is its own failure ([app.doorprints.shared.api.ApiException.Kind.AI_QUOTA_EXHAUSTED]),
 * not a rate limit and not "unavailable". The same rules as the website's `ai-quota.ts`, pinned by the shared vectors
 * (`quotaResponses`).
 *  - HTTP 402 from any provider (payment required: a spent balance or an unpaid account).
 *  - Gemini (ai.google.dev/gemini-api/docs/rate-limits): HTTP 429 `RESOURCE_EXHAUSTED` whose
 *    `error.details[].violations[].quotaId` holds `PerDay` (`GenerateRequestsPerDayPerProjectPerModel-FreeTier`). A
 *    `PerMinute` quota id is still a wait.
 *  - OpenAI and compatible servers (platform.openai.com/docs/guides/error-codes): HTTP 429 (or 400) whose `error.code`
 *    or `error.type` is `insufficient_quota`. A plain 429 "rate limit reached" is still a wait.
 *  - Anthropic (platform.claude.com/docs/en/api/errors): `error.type` `billing_error` (HTTP 402), or HTTP 400
 *    `invalid_request_error` whose message says the credit balance is too low (the older form of the same error).
 */
internal object AiQuota {
    private val json = Json { ignoreUnknownKeys = true }
    private const val DAILY = "PerDay"
    private const val LOW_CREDIT = "credit balance is too low"

    fun isExhausted(status: Int, body: String): Boolean {
        if (status == 402) return true
        if (status != 429 && status != 400) return false
        val error = (runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()?.get("error") as? JsonObject) ?: return false
        if (error["code"].text() == "insufficient_quota" || error["type"].text() == "insufficient_quota") return true
        if (error["type"].text() == "billing_error") return true
        if (status == 429) return dailyQuota(error)
        return error["message"].text()?.lowercase()?.contains(LOW_CREDIT) == true
    }

    private fun dailyQuota(error: JsonObject): Boolean =
        (error["details"] as? JsonArray)?.any { detail ->
            ((detail as? JsonObject)?.get("violations") as? JsonArray)?.any { v ->
                ((v as? JsonObject)?.get("quotaId")).text()?.contains(DAILY) == true
            } == true
        } == true

    private fun JsonElement?.text(): String? = if (this is JsonPrimitive && this !is JsonNull && isString) contentOrNull else null
}
