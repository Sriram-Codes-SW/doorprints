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

import app.doorprints.shared.api.ApiHttp
import app.doorprints.shared.api.PlanRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ask and Plan read one space on this phone's own AI (kinds PR C, S4b-BL-289; docs/03 §18.13): only the candidates the space
 * holds go to the model, and with no space (one kind on) every one does, as before. A school in Guwahati and a house in
 * Kochi, so the fixture is not one city; the request body is read off the fake Gemini's wire.
 */
class OnDeviceAiSpaceTest {
    private val house = "11111111-1111-4111-8111-111111111111"
    private val school = "22222222-2222-4222-8222-222222222222"
    private val all = listOf(
        AiHouse(house, "Marine Drive flat", locality = "Ernakulam", lat = 9.9816, lon = 76.2999, status = "SHORTLISTED", price = 28_000, priceType = "RENT", bedrooms = 2),
        AiHouse(school, "Pine Hill School", locality = "Guwahati", lat = 26.1433, lon = 91.7898, status = "SHORTLISTED", kind = "school"),
    )

    private fun ai(bodies: MutableList<String>): OnDeviceAi {
        val engine = MockEngine { request ->
            bodies += request.body.toByteArray().decodeToString()
            respond(
                buildJsonObject {
                    put("candidates", buildJsonArray {
                        add(buildJsonObject {
                            put("content", buildJsonObject {
                                put("parts", buildJsonArray { add(buildJsonObject { put("text", """{"answer":"x","citedHouseIds":[]}""") }) })
                            })
                        })
                    })
                }.toString(),
                HttpStatusCode.OK, Headers.build { append("Content-Type", "application/json") },
            )
        }
        return OnDeviceAi(GeminiClient(ApiHttp.client(engine), "AIzaTestKey", timeoutMs = null), { all }, devKinds = true)
    }

    @Test
    fun askSendsOnlyTheSpacesCandidates() = runTest {
        val bodies = mutableListOf<String>()
        ai(bodies).ask("Which one is near the bus stop?", holds = { it == "school" })
        assertTrue(school in bodies.single() && house !in bodies.single())
        bodies.clear()
        ai(bodies).ask("Which one is near the bus stop?", holds = { (it ?: "house") == "house" })
        assertTrue(house in bodies.single() && school !in bodies.single())
    }

    @Test
    fun withNoSpaceEveryCandidateIsSentAsBefore() = runTest {
        val bodies = mutableListOf<String>()
        ai(bodies).ask("Which one is near the bus stop?")
        assertTrue(house in bodies.single() && school in bodies.single())
    }

    @Test
    fun aSpaceWithNothingInItAsksNothingAndSaysItDoesNotKnow() = runTest {
        val bodies = mutableListOf<String>()
        val answer = ai(bodies).ask("Which one is near the bus stop?", holds = { it == "clinic" })
        assertEquals(AiPrompts.I_DONT_KNOW, answer.answer)
        assertTrue(bodies.isEmpty())
    }

    @Test
    fun planStopsAreOnlyTheSpacesCandidates() = runTest {
        val bodies = mutableListOf<String>()
        val plan = ai(bodies).planVisits(PlanRequest("Plan my visits", 26.14, 91.79), holds = { it == "school" })
        val sent = bodies.joinToString()
        assertTrue(school in sent)
        assertFalse(house in sent)
        assertTrue(plan.stops.all { it.houseId != house })
    }
}
