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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The AI limits on the phones (S4b-BL-181, docs/ai/ai-design.md 7 and 9): 40 houses, 8 stops, 3,000 characters of notes
 * and then " …", 8,000 characters of listing. The numbers are written from the rules, not read from the constants.
 * tools/mutations/ai-limits-kotlin.json changes each limit by one and names the test that must fail.
 */
class AiLimitsTest {
    private val startLat = 12.9716
    private val startLon = 77.5946

    /** A house [i] thousandths of a degree north of the start point: every one a different, increasing distance away. */
    private fun north(i: Int, label: String = "House $i", id: String = "h$i") =
        AiHouse(id, label, locality = "Indiranagar", lat = startLat + i * 0.001, lon = startLon, status = "SHORTLISTED")

    private fun uuid(i: Int) = "00000000-0000-4000-8000-" + i.toString().padStart(12, '0')

    private fun notesLine(notes: String): String =
        HouseDocuments.text(AiHouse("n", "N", lat = 1.0, lon = 1.0, notes = notes)).lines().first { it.startsWith("Notes: ") }

    private fun hasBrokenPair(s: String): Boolean = s.indices.any { i ->
        (s[i].isHighSurrogate() && !(i + 1 < s.length && s[i + 1].isLowSurrogate())) ||
            (s[i].isLowSurrogate() && !(i > 0 && s[i - 1].isHighSurrogate()))
    }

    // ---- which houses the model may see

    @Test
    fun planKeepsTheFortyNearestOfFortyFiveHousesNearestFirst() {
        val listed = (0 until 45).map { north(44 - it) }
        assertEquals(40, OnDeviceSelection.MAX_HOUSES)
        val chosen = OnDeviceSelection.forPlan(listed, startLat, startLon)
        assertEquals((0 until 40).map { "h$it" }, chosen.map { it.id })
    }

    @Test
    fun planSendsFortyOfFortyOneAndBreaksATieByTheOrderSaved() {
        assertEquals(40, OnDeviceSelection.forPlan((0 until 40).map { north(it) }, startLat, startLon).size)
        assertEquals(40, OnDeviceSelection.forPlan((0 until 41).map { north(it) }, startLat, startLon).size)
        val twins = (0 until 45).map { north(0, id = "t$it") }
        assertEquals((0 until 40).map { "t$it" }, OnDeviceSelection.forPlan(twins, startLat, startLon).map { it.id })
    }

    @Test
    fun askKeepsTheFortyHousesSharingMostWordsOutOfFortyFiveInTheOrderSavedWhenTheyTie() {
        val many = (0 until 45).map { north(it, label = if (it == 43) "Lake view villa" else if (it == 44) "Lake house" else "House $it") }
        val chosen = OnDeviceSelection.forAsk(many, "lake view").map { it.id }
        // h43 shares two words (lake, view), h44 one (lake); the other thirty-eight share none and keep their order.
        assertEquals(listOf("h43", "h44") + (0 until 38).map { "h$it" }, chosen)
    }

    @Test
    fun askDoesNotRankOrCutExactlyFortyHousesAndCutsFortyOne() {
        val forty = (0 until 40).map { north(it, label = if (it == 39) "Lake view villa" else "House $it") }
        assertEquals(forty.map { it.id }, OnDeviceSelection.forAsk(forty, "lake view").map { it.id })
        assertEquals(40, OnDeviceSelection.forAsk(forty + north(40), "lake view").size)
    }

    // ---- at most eight stops

    private val seen = (0 until 12).map { i ->
        PlanCandidate(uuid(i), "H$i", "L", null, "SHORTLISTED", null, null, null, null, startLat + i * 0.001, startLon, i * 111L)
    }.associateBy { it.id }
    private val allIds = seen.keys.toList()

    @Test
    fun aPlanStopsAtTheCapWithTheFirstHousesTheModelNamedInItsOrder() {
        val stops = allIds.map { AgentStop(it, "near") }
        fun ids(plan: AgentPlan, cap: Int) = PlanChecks.assemble(plan, seen, emptyList(), startLat, startLon, cap).stops.map { it.houseId }
        assertEquals(allIds.take(8), ids(AgentPlan("s", stops), 8))
        assertEquals(allIds.take(3), ids(AgentPlan("s", stops), 3))
        assertEquals(8, ids(AgentPlan("s", stops.take(8)), 8).size)
    }

    @Test
    fun theFallbackRouteKeepsTheCapTheEightNearestHousesInTheRunning() {
        val fallback = PlanChecks.assemble(AgentPlan("s", listOf(AgentStop("made-up", "x"))), seen, emptyList(), startLat, startLon, 8)
        assertTrue(fallback.fallback)
        assertEquals(allIds.take(8), fallback.stops.map { it.houseId })
        assertTrue(fallback.stops.all { it.reason == PlanChecks.FALLBACK_REASON })
        assertEquals(allIds.take(2), PlanChecks.assemble(null, seen, emptyList(), startLat, startLon, 2).stops.map { it.houseId })
    }

    // ---- notes

    @Test
    fun notesAreCutAtThreeThousandCharactersWithTheMarkerAndNotBefore() {
        assertEquals(3000, HouseDocuments.NOTES_MAX)
        assertEquals("Notes: " + "x".repeat(3000) + " …", notesLine("x".repeat(3001)))
        assertEquals("Notes: " + "x".repeat(3000), notesLine("x".repeat(3000)))
        assertEquals("Notes: " + "x".repeat(2999), notesLine("x".repeat(2999)))
    }

    @Test
    fun notesAreMeasuredAfterTrimming() {
        assertEquals("Notes: " + "x".repeat(3000), notesLine("  " + "x".repeat(3000) + "  \n"))
    }

    @Test
    fun aCutNeverLeavesHalfOfAnEmoji() {
        // The emoji is two UTF-16 units at 2,999 and 3,000: the cut falls between them, so the whole emoji goes.
        assertEquals("Notes: " + "a".repeat(2999) + " …", notesLine("a".repeat(2999) + "😀tail"))
        // One unit earlier it fits whole.
        assertEquals("Notes: " + "a".repeat(2998) + "😀 …", notesLine("a".repeat(2998) + "😀tail"))
        assertFalse(hasBrokenPair(notesLine("a".repeat(2999) + "😀tail")))
    }

    @Test
    fun theCutHelperKeepsATextOfExactlyTheLimitWholeAndDropsAWholeCharacterAtTheEdge() {
        assertEquals("ab", HouseDocuments.clipUnits("ab", 2))
        assertEquals("a", HouseDocuments.clipUnits("a", 2))
        assertEquals("ab", HouseDocuments.clipUnits("abc", 2))
        // A text that ends exactly at the limit is returned as it is, even when its last unit is half a pair.
        assertEquals("a\uD83D", HouseDocuments.clipUnits("a\uD83D", 2))
        assertEquals("a", HouseDocuments.clipUnits("a😀", 2))
        assertEquals("a😀", HouseDocuments.clipUnits("a😀", 3))
        assertEquals("😀", HouseDocuments.clipUnits("😀😀", 3))
    }

    @Test
    fun hindiTamilAndTeluguNotesAreCutAtThreeThousandCharactersWithoutBreakingOne() {
        for (unit in listOf("घर बहुत अच्छा है। ", "வீடு மிகவும் நல்லது. ", "ఇల్లు చాలా బాగుంది. ")) {
            val notes = unit.repeat(3100 / unit.length + 1)
            val line = notesLine(notes)
            assertTrue(line.endsWith(" …"))
            assertEquals(notes.take(3000), line.removePrefix("Notes: ").removeSuffix(" …"))
            assertFalse(hasBrokenPair(line))
        }
    }

    // ---- the assistant

    private class FakeGemini(vararg replies: String) {
        val bodies = mutableListOf<String>()
        private val queue = replies.toMutableList()
        val engine = MockEngine { request ->
            bodies += request.body.toByteArray().decodeToString()
            val text = queue.removeAt(0)
            respond(
                buildJsonObject {
                    put("candidates", buildJsonArray {
                        add(buildJsonObject {
                            put("content", buildJsonObject {
                                put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
                            })
                        })
                    })
                }.toString(),
                HttpStatusCode.OK, Headers.build { append("Content-Type", "application/json") },
            )
        }

        fun ai(houses: List<AiHouse>) = OnDeviceAi(GeminiClient(ApiHttp.client(engine), "AIzaTestKey", timeoutMs = null), { houses })
    }

    private fun fortyFiveHouses() = (0 until 45).map { i ->
        AiHouse(uuid(44 - i), "House ${44 - i}", locality = "Indiranagar", lat = startLat + (44 - i) * 0.001, lon = startLon, status = "SHORTLISTED")
    }

    @Test
    fun planOffersOnlyTheFortyNearestOfFortyFiveHousesAndSaysAtMostEightStops() = runTest {
        val stops = (0 until 12).joinToString(",") { """{"houseId":"${uuid(it)}","reason":"near"}""" }
        val gemini = FakeGemini("""{"summary":"s","stops":[$stops]}""")
        val plan = gemini.ai(fortyFiveHouses()).planVisits(PlanRequest("A walk", startLat, startLon, maxStops = 25))
        val sent = gemini.bodies.single()
        val offered = Regex("label: (House \\d+) \\|").findAll(sent).map { it.groupValues[1] }.toList()
        assertEquals((0 until 40).map { "House $it" }, offered)
        assertTrue("Plan at most 8 stops." in sent)
        assertEquals((0 until 8).map { "House $it" }, plan.stops.map { it.label })
    }

    @Test
    fun aRequestMayAskForFewerStopsNeverForNoneNeverForMoreThanEight() = runTest {
        for ((asked, said) in listOf(3 to 3, 0 to 1, -4 to 1, 9 to 8)) {
            val gemini = FakeGemini("""{"summary":"s","stops":[]}""")
            gemini.ai(fortyFiveHouses()).planVisits(PlanRequest("A walk", startLat, startLon, maxStops = asked))
            assertTrue("Plan at most $said stops." in gemini.bodies.single(), "asked for $asked")
        }
    }

    @Test
    fun aListingOfEightThousandCharactersIsAcceptedAndEightThousandAndOneRefusedBeforeAnyRequest() = runTest {
        assertEquals(8000, OnDeviceAi.MAX_INPUT_CHARS)
        val refused = FakeGemini()
        assertFailsWith<IllegalArgumentException> { refused.ai(emptyList()).extractListing("a".repeat(8001)) }
        assertTrue(refused.bodies.isEmpty())
        val gemini = FakeGemini("""{"label":"x"}""")
        gemini.ai(emptyList()).extractListing("a".repeat(8000))
        assertTrue("a".repeat(8000) in gemini.bodies.single())
    }

    @Test
    fun aListingOfSpacesOnlyIsRefused() = runTest {
        val gemini = FakeGemini()
        assertFailsWith<IllegalArgumentException> { gemini.ai(emptyList()).extractListing(" ".repeat(10)) }
        assertTrue(gemini.bodies.isEmpty())
    }

    @Test
    fun aStartPointThatIsNotOnEarthIsRefusedBeforeAnyRequest() = runTest {
        val bad = listOf(
            90.0001 to 77.0, -90.0001 to 77.0, 12.0 to 180.0001, 12.0 to -180.0001,
            Double.NaN to 77.0, 12.0 to Double.NaN, Double.POSITIVE_INFINITY to 77.0, 12.0 to Double.NEGATIVE_INFINITY,
        )
        val gemini = FakeGemini()
        for ((lat, lon) in bad) {
            assertFailsWith<IllegalArgumentException>("$lat, $lon") { gemini.ai(fortyFiveHouses()).planVisits(PlanRequest("A walk", lat, lon)) }
        }
        assertTrue(gemini.bodies.isEmpty())
    }

    @Test
    fun thePolesAndTheDateLineAreAccepted() = runTest {
        for ((lat, lon) in listOf(90.0 to 180.0, -90.0 to -180.0)) {
            val gemini = FakeGemini("""{"summary":"s","stops":[]}""")
            gemini.ai(fortyFiveHouses()).planVisits(PlanRequest("A walk", lat, lon))
            assertEquals(1, gemini.bodies.size)
        }
    }
}
