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
import app.doorprints.shared.api.AskResponseDto
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.api.PlanRequest
import app.doorprints.shared.api.PlanResponseDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.TimeSource

/**
 * AI with the person's own Gemini key, on this device (docs/03 §13.1, ADR-26): the same three calls as the server's
 * `/api/ai` endpoints, the same prompts, limits and checks (the common core in this package), and the model called directly
 * ([GeminiClient] or [OpenAiCompatClient], docs/03 §13.2). [houses] reads the saved houses when Ask or Plan needs them. At most [RATE_LIMIT] requests a
 * minute, as the server allows.
 */
class OnDeviceAi(
    private val gemini: JsonChatModel,
    private val houses: suspend () -> List<AiHouse>,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    companion object {
        const val MAX_INPUT_CHARS = 8000
        const val MAX_QUESTION_CHARS = 1000
        const val MAX_STOPS = 8
        const val RATE_LIMIT = 10
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun str(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
            put("nullable", true)
        }

        /** The server's `RawListing`, its field descriptions word for word. */
        val LISTING_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                put("label", str("Short human label, e.g. '2BHK near Indiranagar metro'"))
                put("address", str("Full postal address as written in the listing"))
                put("street", str("Street / road name only, e.g. 'MG Road'; null when no road is named"))
                put("locality", str("Locality / neighbourhood / area inside the city, e.g. 'Indiranagar', 'Sector 56'; never the city or district alone. When the listing names only a road, repeat the road here"))
                put("price", str("Monthly rent or sale price in rupees exactly as written, e.g. '25,000' or '1.2 Cr'"))
                put("priceType", str("RENT or SALE"))
                put("bedrooms", str("Number of bedrooms, e.g. '2' for 2BHK"))
                put("contactName", str("Contact person name"))
                put("contactPhone", str("Contact phone number exactly as written"))
                put("listingUrl", str("Listing URL if one is present in the text"))
                put("notes", str("Other useful facts (deposit, floor, furnishing, availability) in one short paragraph"))
                putJsonObject("amenities") {
                    put("type", "array")
                    put("description", "Amenities such as parking, lift, power backup, gym")
                    putJsonObject("items") { put("type", "string") }
                }
            }
        }

        /** The server's `ModelAnswer`. */
        val ANSWER_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("answer") {
                    put("type", "string")
                    put("description", "The answer, in 1-6 sentences, citing houses inline as [house:<id>]")
                }
                putJsonObject("citedHouseIds") {
                    put("type", "array")
                    put("description", "Ids of the houses cited inline as [house:<id>] in the answer, copied exactly from the context")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("answer")) }
        }

        /** For *Test key*: the smallest request that proves Google accepts the key. */
        val PING_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("ok") { put("type", "boolean") } }
        }

        /** The server's `AgentPlan`. */
        val PLAN_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("summary") {
                    put("type", "string")
                    put("description", "2-4 sentences explaining the plan")
                }
                putJsonObject("stops") {
                    put("type", "array")
                    put("description", "Houses to visit, in visiting order")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("houseId") {
                                put("type", "string")
                                put("description", "House id exactly as returned by a tool")
                            }
                            putJsonObject("reason") {
                                put("type", "string")
                                put("description", "Why this house is in the plan, one sentence")
                            }
                        }
                    }
                }
            }
        }
    }

    private val recent = ArrayDeque<TimeSource.Monotonic.ValueTimeMark>()

    /** At most [RATE_LIMIT] requests in any minute; the next one waits for the oldest to age out. */
    private fun admit() {
        val now = TimeSource.Monotonic.markNow()
        while (recent.isNotEmpty() && recent.first().elapsedNow().inWholeSeconds >= 60) recent.removeFirst()
        if (recent.size >= RATE_LIMIT) {
            val wait = 60 - recent.first().elapsedNow().inWholeSeconds
            throw ApiException(ApiException.Kind.RATE_LIMITED, 429, retryAfterSeconds = wait.coerceAtLeast(1))
        }
        recent.addLast(now)
    }

    /** Reads [text] into a checked draft. Refuses a blank or over-long text; counts towards the rate limit. */
    suspend fun extractListing(text: String): HouseDraftDto {
        require(text.isNotBlank()) { "text is empty" }
        require(text.length <= MAX_INPUT_CHARS) { "text is longer than $MAX_INPUT_CHARS characters" }
        admit()
        val prompt = AiPrompts.extraction(text, PromptSafety.nonce())
        val raw = parse<RawListing>(gemini.generateJson(prompt.system, prompt.user, LISTING_SCHEMA, 0.0))
        return DraftSanitizer.sanitize(raw, text)
    }

    /**
      * Answers [question] from the saved houses with citations. With no house to send it answers with the refusal
      * sentence
     * and makes no model call. The answer is checked by [AskChecks]; an unusable one becomes the refusal.
     */
    suspend fun ask(question: String, filters: AskFilters? = null): AskResponseDto {
        require(question.isNotBlank()) { "question is empty" }
        require(question.length <= MAX_QUESTION_CHARS) { "question is longer than $MAX_QUESTION_CHARS characters" }
        val docs = OnDeviceSelection.forAsk(houses(), question, filters)
        if (docs.isEmpty()) return AskResponseDto(AiPrompts.I_DONT_KNOW, emptyList(), false, 0)
        admit()
        val prompt = AiPrompts.ask(question, docs.map { it.id to it.text }, PromptSafety.nonce())
        val answer = parse<ModelAnswer>(gemini.generateJson(prompt.system, prompt.user, ANSWER_SCHEMA, 0.1))
        if (answer?.answer.isNullOrBlank()) return AskResponseDto(AiPrompts.I_DONT_KNOW, emptyList(), false, docs.size)
        val text = AnswerText.clean(answer!!.answer!!.trim(), docs.joinToString("\n") { it.text })
        val citations = AskChecks.citations(answer.copy(answer = text), docs, question)
        return AskResponseDto(text, citations, citations.isNotEmpty(), docs.size)
    }

    /**
      * A walking plan from the request's start point over the nearest saved houses. A model that answers unusably falls
      * back to
     * the nearest-neighbour order ([PlanChecks]); a request that fails outright is thrown, since nothing was planned.
     */
    suspend fun planVisits(request: PlanRequest): PlanResponseDto {
        require(request.question.isNotBlank()) { "question is empty" }
        require(request.question.length <= MAX_QUESTION_CHARS) { "question is longer than $MAX_QUESTION_CHARS characters" }
        // The server's PlanRequest bounds (docs/ai/ai-design.md 9); a NaN is outside both ranges.
        require(request.startLat in -90.0..90.0 && request.startLon in -180.0..180.0) { "the start point is not on Earth" }
        val maxStops = minOf(request.maxStops ?: MAX_STOPS, MAX_STOPS).coerceAtLeast(1)
        val candidates = OnDeviceSelection.forPlan(houses(), request.startLat, request.startLon)
        val seen = LinkedHashMap<String, PlanCandidate>().apply { candidates.forEach { put(it.id, it) } }
        if (candidates.isEmpty()) return PlanChecks.assemble(AgentPlan(null, emptyList()), seen, emptyList(), request.startLat, request.startLon, maxStops)
        admit()
        val prompt = AiPrompts.plan(request.question, request.startLat, request.startLon, maxStops,
            OnDeviceSelection.candidateLines(candidates), PromptSafety.nonce())
        // As on the server: a model that answers but not usably falls back to the candidates by distance; a request
        // that fails outright (key, quota, network) is reported, since nothing was planned.
        val plan = runCatching { parse<AgentPlan>(gemini.generateJson(prompt.system, prompt.user, PLAN_SCHEMA, 0.2)) }
            .getOrElse { e -> if (e is ApiException) throw e else null }
        return PlanChecks.assemble(plan, seen, emptyList(), request.startLat, request.startLon, maxStops)
    }

    private inline fun <reified T> parse(text: String): T? =
        runCatching { json.decodeFromString<T>(text) }.getOrNull()
}
