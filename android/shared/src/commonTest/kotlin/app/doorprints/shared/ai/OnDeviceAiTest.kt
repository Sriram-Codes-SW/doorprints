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
import app.doorprints.shared.api.PlanRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** On-device AI against a fake Gemini (docs/03 §13.1, ADR-26): the request on the wire, the checks on the answers. */
class OnDeviceAiTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val houses = listOf(
        AiHouse(a, "Blue gate", locality = "Indiranagar", lat = 12.975, lon = 77.60, status = "SHORTLISTED",
            price = 25_000, priceType = "RENT", bedrooms = 2, contactName = "Ramesh Kumar", contactPhone = "98450 12345",
            notes = "Ramesh says 24x7 water. Call 98450 12345."),
        AiHouse(b, "Green view", locality = "Koramangala", lat = 12.935, lon = 77.62, status = "REJECTED", price = 40_000,
            priceType = "RENT", bedrooms = 3),
    )

    private class Seen(val url: String, val headers: Headers, val body: String)

    /** Gemini answering each call with the next of [answers] (JSON text for the model's reply, or an HTTP status). */
    private class FakeGemini(vararg answers: Any) {
        val seen = mutableListOf<Seen>()
        private val replies = answers.toMutableList()
        val engine = MockEngine { request ->
            seen += Seen(request.url.toString(), request.headers, request.body.toByteArray().decodeToString())
            when (val next = replies.removeAt(0)) {
                is Int -> respond("""{"error":{"status":"x","message":"API key not valid. Please pass a valid API key."}}""", HttpStatusCode.fromValue(next))
                else -> respond(
                    buildJsonObject {
                        put("candidates", buildJsonArray {
                            add(buildJsonObject {
                                put("content", buildJsonObject {
                                    put("parts", buildJsonArray { add(buildJsonObject { put("text", next as String) }) })
                                })
                            })
                        })
                    }.toString(),
                    HttpStatusCode.OK, Headers.build { append("Content-Type", "application/json") },
                )
            }
        }
        fun ai(houses: List<AiHouse>) = OnDeviceAi(GeminiClient(ApiHttp.client(engine), "AIzaTestKey", timeoutMs = null), { houses })
    }

    @Test
    fun theKeyGoesInAHeaderAndTheRequestAsksForTheSchema() = runTest {
        val gemini = FakeGemini("""{"label":"2BHK","price":"25k","contactPhone":"99999 88888"}""")
        val draft = gemini.ai(houses).extractListing("2BHK in Indiranagar for 25k")
        val sent = gemini.seen.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent", sent.url)
        assertEquals("AIzaTestKey", sent.headers["x-goog-api-key"])
        assertFalse("AIzaTestKey" in sent.url)
        val body = Json.parseToJsonElement(sent.body).jsonObject
        val config = body.getValue("generationConfig").jsonObject
        assertEquals("application/json", config.getValue("responseMimeType").jsonPrimitive.content)
        assertEquals(0.0, config.getValue("temperature").jsonPrimitive.content.toDouble())
        assertTrue(config.getValue("responseSchema").jsonObject.getValue("properties").jsonObject.containsKey("contactPhone"))
        assertTrue(body.toString().contains("<listing-"))
        // The server's checks: an invented phone is dropped, the price read.
        assertEquals(25_000, draft.price)
        assertNull(draft.contactPhone)
        assertTrue(draft.warnings.any { "contactPhone" in it })
    }

    @Test
    fun askSendsNoContactAndCitesOnlyHousesItSent() = runTest {
        val gemini = FakeGemini("""{"answer":"Blue gate has 24x7 water [house:$a]; see also [house:33333333-3333-4333-8333-333333333333].","citedHouseIds":["$a"]}""")
        val answer = gemini.ai(houses).ask("Which house has 24x7 water?")
        val sent = gemini.seen.single().body
        assertFalse("Ramesh" in sent || "98450" in sent, sent)
        assertTrue("[house:$a]" in sent && "[house:$b]" in sent)
        assertEquals(listOf(a), answer.citations.map { it.houseId })
        assertTrue(answer.grounded)
        assertEquals(2, answer.retrieved)
    }

    @Test
    fun askRemovesALinkOrImageTheModelPutInTheAnswerAndKeepsTheCitation() = runTest {
        val gemini = FakeGemini("""{"answer":"Quiet [house:$a] ![x](https://evil.example/t.png?d=1) see https://evil.example/log and https://example.com/l/1","citedHouseIds":["$a"]}""")
        val withLink = listOf(houses[0].copy(notes = "Quiet street, photos at https://example.com/l/1."))
        val answer = gemini.ai(withLink).ask("Which house is quiet?")
        assertEquals("Quiet [house:$a] x see [link removed] and https://example.com/l/1", answer.answer)
        assertEquals(listOf(a), answer.citations.map { it.houseId })
    }

    @Test
    fun askWithNoHousesDoesNotCallGeminiAndABlankAnswerIsTheRefusal() = runTest {
        val none = FakeGemini()
        assertEquals(AiPrompts.I_DONT_KNOW, none.ai(emptyList()).ask("anything?").answer)
        assertTrue(none.seen.isEmpty())
        val blank = FakeGemini("""{"answer":"  "}""").ai(houses).ask("anything?")
        assertEquals(AiPrompts.I_DONT_KNOW, blank.answer)
        assertFalse(blank.grounded)
    }

    @Test
    fun planKeepsCandidatesAndFallsBackOnAnUnusableAnswer() = runTest {
        val good = FakeGemini("""{"summary":"One stop.","stops":[{"houseId":"$a","reason":"shortlisted"},{"houseId":"made-up"}]}""")
        val plan = good.ai(houses).planVisits(PlanRequest("2BHK near me", 12.9716, 77.5946))
        assertEquals(listOf(a), plan.stops.map { it.houseId })
        assertFalse(plan.fallback)
        assertFalse("Ramesh" in good.seen.single().body)

        val garbled = FakeGemini("not json").ai(houses).planVisits(PlanRequest("anything", 12.9716, 77.5946))
        assertTrue(garbled.fallback)
        assertEquals(listOf(a), garbled.stops.map { it.houseId }) // the rejected house is left out
    }

    @Test
    fun googlesAnswersBecomeTheAppsErrors() = runTest {
        suspend fun kindFor(status: Int) = assertFailsWith<ApiException> { FakeGemini(status).ai(houses).ask("x?") }.kind
        assertEquals(ApiException.Kind.RATE_LIMITED, kindFor(429))
        assertEquals(ApiException.Kind.AI_KEY_REJECTED, kindFor(400))
        assertEquals(ApiException.Kind.AI_KEY_REJECTED, kindFor(403))
        assertEquals(ApiException.Kind.AI_UNAVAILABLE, kindFor(500))
        // A plan request that fails outright is reported, not replaced by a fallback.
        assertFailsWith<ApiException> { FakeGemini(429).ai(houses).planVisits(PlanRequest("x", 12.97, 77.59)) }
    }

    @Test
    fun atMostTenRequestsAMinute() = runTest {
        val gemini = FakeGemini(*Array(11) { """{"answer":"ok"}""" })
        val ai = gemini.ai(houses)
        repeat(10) { ai.ask("q$it?") }
        val limited = assertFailsWith<ApiException> { ai.ask("one more?") }
        assertEquals(ApiException.Kind.RATE_LIMITED, limited.kind)
        assertEquals(10, gemini.seen.size)
        assertFailsWith<IllegalArgumentException> { ai.ask("x".repeat(1001)) }
        assertFailsWith<IllegalArgumentException> { ai.extractListing("x".repeat(8001)) }
    }

    @Test
    fun theSchemasCarryTheServersFieldDescriptions() {
        val listing = OnDeviceAi.LISTING_SCHEMA.getValue("properties").jsonObject
        assertEquals(12, listing.size)
        assertEquals(JsonPrimitive("Contact phone number exactly as written"), listing.getValue("contactPhone").jsonObject["description"])
        assertEquals("answer", OnDeviceAi.ANSWER_SCHEMA.getValue("required").jsonArray.single().jsonPrimitive.content)
    }
}
