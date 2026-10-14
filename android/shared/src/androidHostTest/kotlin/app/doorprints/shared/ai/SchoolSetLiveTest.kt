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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The school set (`docs/ai/evals/school-set.json`, S4b-BL-268, docs/ai/ai-design.md 8.7) through the phone's AI against the
 * real Gemini API, with the key from `DOORPRINTS_LIVE_GEMINI_KEY` (skipped without it; the manual *AI evals* workflow runs
 * this class only for `eval_set=school-set`). The phone's AI is built with the kinds on over the set's own schools (their
 * kind and extras, [SchoolSetFixtures]) and one case of each feature is run. Informational, like the set: it checks that
 * the school's prompts, schemas and slots are accepted and that what comes back passes the phone's own checks and sends no
 * saved contact, not the quality of the answers. Its keyless twin is [SchoolSetHarnessTest].
 */
class SchoolSetLiveTest {
    private val key = System.getenv("DOORPRINTS_LIVE_GEMINI_KEY").orEmpty()

    @Test
    fun schoolSetAgainstGemini() = runBlocking {
        assumeTrue("DOORPRINTS_LIVE_GEMINI_KEY is not set", key.isNotBlank())
        val http = AndroidApiHttp.create()
        val sent = mutableListOf<String>()
        http.plugin(HttpSend).intercept { request ->
            sent += (request.body as? TextContent)?.text.orEmpty()
            execute(request)
        }
        val schools = SchoolSetFixtures.houses()
        val ids = schools.map { it.id }.toSet()
        val ai = OnDeviceAi(GeminiClient(http, key), { schools }, devKinds = true)

        val extract = SchoolSetFixtures.case("school-extract-01-full-listing")["input"]!!.jsonObject
        val draft = ai.extractListing(extract["text"]!!.jsonPrimitive.content, extract["kind"]!!.jsonPrimitive.content)
        assertEquals("CBSE", draft.extras?.get("board")?.jsonPrimitive?.content, "board from the listing")
        println("School extract: label=${draft.label}, extras=${draft.extras}")
        delay(PAUSE_MS)

        val question = SchoolSetFixtures.case("school-ask-01-bus-and-board")["input"]!!.jsonObject["question"]!!.jsonPrimitive.content
        val answer = ai.ask(question)
        assertTrue(answer.answer.isNotBlank(), "an answer")
        assertTrue(answer.citations.all { it.houseId in ids }, "cites only schools it sent")
        println("School ask: grounded=${answer.grounded}, cited=${answer.citations.map { it.houseId }}")
        delay(PAUSE_MS)

        val plan = SchoolSetFixtures.case("school-plan-01-shortlisted-bengaluru")["input"]!!.jsonObject
        val planned = ai.planVisits(PlanRequest(plan["question"]!!.jsonPrimitive.content, 12.94, 77.69, maxStops = 3))
        assertTrue(planned.stops.all { it.houseId in ids }, "only saved schools")
        println("School plan: fallback=${planned.fallback}, stops=${planned.stops.map { it.houseId }}")

        assertEquals(3, sent.size, "one request per call")
        for (body in sent.drop(1)) {
            // The saved contact (the Dispur convent's) and the phone in its note never left the device.
            assertFalse("Sister Mary" in body || "98640" in body, "a saved contact left the device")
        }
    }

    private companion object {
        /** Free-tier requests-per-minute limits. */
        const val PAUSE_MS = 4_000L
    }
}
