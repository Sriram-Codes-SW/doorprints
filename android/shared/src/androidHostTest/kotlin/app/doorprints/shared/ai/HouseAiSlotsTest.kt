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

import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.HouseCost
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.MoveIn
import app.doorprints.shared.model.MoveInItem
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The slots of `docs/schemas/kinds/house.json` against the house's hand-written AI code (S4b-BL-271, docs/ai/ai-design.md
 * 16.8). A house is not read through its slots: its Ask document ([HouseDocuments]), Plan candidate ([PlanCandidate]) and
 * Extract draft ([HouseDraftDto]) are pinned byte for byte ([HouseAiPinsTest]) and stay as they are. This holds the slots to
 * what that code carries, with the shared `house-ai-slot-vectors.json`: a line or field the code writes that no slot names,
 * or a slot that nothing carries, fails. The twins: the website's `house-ai-slots.spec.ts` and the server's `HouseAiSlotsTest`.
 */
class HouseAiSlotsTest {
    private fun repoFile(relative: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, relative) }.first { it.exists() }

    private fun json(name: String): JsonObject = Json.parseToJsonElement(repoFile("docs/schemas/kinds/$name").readText()).jsonObject
    private fun ids(o: JsonObject, key: String): List<String> = o[key]!!.jsonArray.map { it.jsonPrimitive.content }
    private fun idsOf(map: JsonObject, keys: Collection<String>): Set<String> = keys.flatMap { ids(map, it) }.toSet()

    private val ai = json("house.json")["ai"]!!.jsonObject
    private val vectors = json("house-ai-slot-vectors.json")

    /** A house with every field the AI reads (the pins' rich house) and a visit, so every line of a document is written. */
    private val full = AiHouse(
        id = "99999999-9999-4999-8999-999999999999", label = "Ramesh's 2BHK, Indiranagar", address = "C/o Ramesh Kumar, 12 MG Road",
        street = "MG Road", locality = "Indiranagar", lat = 12.9731, lon = 77.6405, status = "TAKEN", price = 28000, priceType = "RENT",
        bedrooms = 2, rating = 4, contactName = "Ramesh Kumar", contactPhone = "+91 98450 12345",
        listingUrl = "https://example.com/l/9845012345", notes = "Quiet lane.", areaSqft = 1150, floor = -1,
        cost = HouseCost(64000, 3, 2500, false, 30000, 1, 11, 2, "2026-10-15", 33333, 31000),
        rooms = listOf(HouseRoom("k", "KITCHEN", "Kitchen", 300, 244, null, "x", 1)),
        answers = listOf(
            HouseAnswer("a1", null, "Any pets rule?", "No dogs", "ANSWERED", 0),
            HouseAnswer("a2", null, "Is there a lift?", null, "OPEN", 1),
        ),
        moveIn = MoveIn(1790812800000, "Keys on Friday", listOf(MoveInItem("a", "Agreement signed", true), MoveInItem("b", "Keys"))),
        checklist = sortedMapOf("water" to 5),
        viewings = listOf(AiViewing("v1", Instant.parse("2026-10-02T05:00:00Z").toEpochMilli(), "SECOND", "PLANNED", "Water bill")),
        areaNotes = listOf(AiAreaNote("n1", "Quiet lanes", 5)),
        distances = listOf(AiDistance("Office", 589.4)),
        visits = listOf(AiVisit(Instant.parse("2026-09-14T10:00:00Z").toEpochMilli(), Instant.parse("2026-09-14T10:35:00Z").toEpochMilli())),
    )

    /** The label of an Ask line: the text before the first colon, `Distance to <place>` read as `Distance to`. */
    private fun labelOf(line: String): String = line.substringBefore(':').let { if (it.startsWith("Distance to ")) "Distance to" else it }

    @Test
    fun askEveryLineOfAHouseDocumentIsInTheVectorsNamesOnlyAskSlotIdsAndTheSlotHasNoIdNoLineCarries() {
        val askVectors = vectors["ask"]!!.jsonObject
        val labels = HouseDocuments.text(full).lines().filter { Regex("[A-Z][A-Za-z -]*[^:]*: .*").matches(it) }.map(::labelOf).toSet()
        assertEquals(askVectors.keys.sorted(), labels.sorted())
        val carried = idsOf(askVectors, labels)
        val slot = ids(ai, "ask")
        assertTrue(slot.containsAll(carried), "an id of a line is not in the ask slot: ${carried - slot.toSet()}")
        assertEquals(slot.sorted(), carried.sorted())
        assertTrue(carried.none { it in ids(ai, "never") })
    }

    @Test
    fun askNoLineCarriesTheContactOrThePersonsOwnOffer() {
        val text = HouseDocuments.text(full)
        for (secret in listOf("Ramesh Kumar", "98450", "33333", "example.com")) assertFalse(secret in text, secret)
    }

    @Test
    fun planEveryFieldOfACandidateIsInTheVectorsNamesOnlyPlanSlotIdsAndTheSlotHasNoIdNoFieldCarries() {
        val structural = ids(vectors, "planStructural")
        val fields = PlanCandidate::class.java.declaredFields.filter { !it.isSynthetic }.map { it.name }.filter { it !in structural }
        assertEquals(vectors["plan"]!!.jsonObject.keys.sorted(), fields.sorted())
        val carried = idsOf(vectors["plan"]!!.jsonObject, fields)
        val slot = ids(ai, "plan")
        assertTrue(slot.containsAll(carried))
        assertEquals(slot.sorted(), carried.sorted())
        assertTrue(carried.none { it in ids(ai, "never") })
        // The candidate the phone builds fills every one of those fields from the house.
        val candidate = OnDeviceSelection.forPlan(listOf(full), 12.97, 77.59).single()
        assertEquals("MG Road", candidate.street)
        assertEquals("Indiranagar", candidate.locality)
    }

    @Test
    fun extractEveryFieldOfADraftIsInTheVectorsNamesOnlyExtractSlotIdsAndWhatNoDraftCarriesIsTheNoAiParsersAlone() {
        val structural = ids(vectors, "extractStructural")
        val fields = HouseDraftDto.serializer().descriptor.elementNames.filter { it !in structural }
        assertEquals(vectors["extract"]!!.jsonObject.keys.sorted(), fields.sorted())
        val carried = idsOf(vectors["extract"]!!.jsonObject, fields)
        val slot = ids(ai, "extract")
        assertTrue(slot.containsAll(carried))
        assertEquals(emptyList(), slot.filter { it !in carried })
        assertEquals(listOf("areaSqft"), ids(vectors, "noAiOnly"))
    }
}
