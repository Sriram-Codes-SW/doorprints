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

import app.doorprints.shared.api.AndroidApiHttp
import app.doorprints.shared.api.PlanRequest
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phones' on-device AI against the real Gemini API (docs/03 §13.1, ADR-26; docs/06 TC-U-88): one Extract, one Ask
 * and one Plan through [OnDeviceAi] and [GeminiClient] on the Android HTTP stack, with the key from
 * `DOORPRINTS_LIVE_GEMINI_KEY`. Skipped without it, so the normal build never calls Google; the manual *AI evals*
 * workflow (job *On-device AI, real key*) sets it from the `AI_API_KEY` secret. Checks what a fake cannot: Google
 * accepts the request, the schemas and the key header, and the answers pass the server's checks. It also reads every
 * request body as sent and checks that no saved contact name, phone number or email address left the device.
 */
class OnDeviceAiLiveTest {
    private val key = System.getenv("DOORPRINTS_LIVE_GEMINI_KEY").orEmpty()
    private val quiet = "11111111-1111-4111-8111-111111111111"
    private val noisy = "22222222-2222-4222-8222-222222222222"
    private val houses = listOf(
        AiHouse(quiet, "Blue gate", locality = "Indiranagar", lat = 12.9719, lon = 77.6412, status = "SHORTLISTED",
            price = 25_000, priceType = "RENT", bedrooms = 2, contactName = "Ramesh Kumar", contactPhone = "98450 12345",
            notes = "Very quiet lane, 24x7 water. Ramesh says call 98450 12345 after 6 pm or mail kumar.r83@example.com."),
        AiHouse(noisy, "Green view", locality = "Koramangala", lat = 12.9352, lon = 77.6245, status = "SHORTLISTED",
            price = 40_000, priceType = "RENT", bedrooms = 3, notes = "On the main road, traffic noise all day."),
    )

    @Test
    fun extractAskAndPlanAgainstGemini() = runBlocking {
        assumeTrue("DOORPRINTS_LIVE_GEMINI_KEY is not set", key.isNotBlank())
        val http = AndroidApiHttp.create()
        val sent = mutableListOf<String>()
        http.plugin(HttpSend).intercept { request ->
            sent += (request.body as? TextContent)?.text.orEmpty()
            execute(request)
        }
        val ai = OnDeviceAi(GeminiClient(http, key), { houses })

        val draft = ai.extractListing("2BHK flat in Indiranagar for rent, 25,000 a month, lift and parking. Call 98450 12345.")
        assertEquals(25_000L, draft.price, "price from the listing")
        assertEquals(2, draft.bedrooms, "bedrooms from the listing")
        println("Extract: label=${draft.label}, price=${draft.price}, bedrooms=${draft.bedrooms}")
        delay(PAUSE_MS)

        val answer = ai.ask("Which house is quiet?")
        assertTrue(answer.answer.isNotBlank(), "an answer")
        assertTrue(answer.citations.all { it.houseId == quiet || it.houseId == noisy }, "cites only houses it sent")
        println("Ask: grounded=${answer.grounded}, cited=${answer.citations.map { it.houseId }}")
        delay(PAUSE_MS)

        val plan = ai.planVisits(PlanRequest("Plan visits to my shortlisted houses", 12.9716, 77.5946, maxStops = 2))
        assertTrue(plan.stops.isNotEmpty(), "at least one stop")
        assertTrue(plan.stops.all { it.houseId == quiet || it.houseId == noisy }, "only candidate houses")
        println("Plan: fallback=${plan.fallback}, stops=${plan.stops.map { it.houseId }}, total=${plan.totalMeters} m")

        assertEquals(3, sent.size, "one request per call")
        for (body in sent.drop(1)) {
            assertFalse("Ramesh" in body || "98450" in body, "a saved contact left the device")
            assertFalse("@example.com" in body || "kumar.r83" in body, "an email address left the device")
        }
    }

    private companion object {
        /** Free-tier requests-per-minute limits. */
        const val PAUSE_MS = 4_000L
    }
}
