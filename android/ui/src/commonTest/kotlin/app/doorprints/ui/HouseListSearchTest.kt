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

package app.doorprints.ui

import app.doorprints.data.HouseEntity
import app.doorprints.data.toDto
import app.doorprints.data.toEntity
import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.HouseSearch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The house list's search after kinds step 4 (S4b-BL-206, TC-U-204; CLAUDE.md "search grows with the house values"):
 * a house read from its candidate (the server, a Drive file, the Room row) is found by the same values as the house
 * it was, the floor included (now an extra), and never by an extra this app does not know. The twin of the website's
 * `map-list.spec.ts` case of TC-U-202.
 */
class HouseListSearchTest {
    private val house = HouseEntity(
        id = "h1", label = "Gulmohar Residency", address = "Plot 7, Sector 62", locality = "Noida", lat = 28.62, lon = 77.36,
        notes = "Corner flat", contactName = "Anita", price = 28_000, priceType = "RENT", bedrooms = 2, floor = 4,
        rooms = listOf(HouseRoom("r1", "BEDROOM", "Balcony room", sort = 0)), createdAt = 1, updatedAt = 2,
    )

    private fun found(query: String, h: HouseEntity) = HouseSearch.matches(query, h.searchFields(null, "en", emptyList()))

    @Test
    fun aHouseReadFromItsCandidateIsFoundByTheSameValues() {
        val candidate = house.toDto()
        val read = candidate.toEntity()
        assertEquals(house.searchFields("Ravi, Ravi Estates", "hi", listOf("Water cuts")), read.searchFields("Ravi, Ravi Estates", "hi", listOf("Water cuts")))
        for (q in listOf("gulmohar", "sector 62", "noida", "corner", "anita", "balcony", "floor 4")) assertTrue(found(q, read), q)
        assertFalse(found("floor 5", read))
    }

    /**
     * The safety test of kinds PR 6 (S4b-BL-210), written before typed extras reached search: a house's search values,
     * in order and in every language, are exactly main's (the twin of the website's `house-pins.spec.ts`).
     */
    @Test
    fun aHouseIsSearchedByWhatMainSearchedInEveryLanguage() {
        val words = listOf("Gulmohar Residency", "Plot 7, Sector 62", "Noida", "Corner flat", "Anita", "Ravi, Ravi Estates", "Balcony room", "Water cuts", "floor 4")
        val local = mapOf("en" to emptyList(), "hi" to listOf("मंज़िल 4"), "ta" to listOf("தளம் 4"), "te" to listOf("అంతస్తు 4"))
        for ((language, floor) in local) assertEquals(words + floor, house.searchFields("Ravi, Ravi Estates", language, listOf("Water cuts")), language)
    }

    /**
     * Kinds PR 6 (S4b-BL-210): a school's extras come last, in English and the app's language, only while the developer
     * flag lets the list draw it as a school (`kind-search-vectors.json`, `KindSearchVectorsTest`); off, as a house.
     */
    @Test
    fun aSchoolIsFoundByItsExtrasOnlyWhileTheFlagAllowsTheKind() {
        val school = house.copy(kind = "school", extrasOther = buildJsonObject { put("board", "CBSE"); put("busService", true) })
        val before = school.searchFields(null, "hi", emptyList())
        assertEquals(before + listOf("CBSE", "School bus", "स्कूल बस"), school.searchFields(null, "hi", emptyList(), devKinds = true))
        assertTrue(HouseSearch.matches("school bus", school.searchFields(null, "en", emptyList(), devKinds = true)))
        assertFalse(HouseSearch.matches("cbse", before))
        assertEquals(house.searchFields(null, "ta", emptyList()), house.searchFields(null, "ta", emptyList(), devKinds = true))
    }

    @Test
    fun anExtraThisAppDoesNotKnowIsNeverSearched() {
        val dto: HouseDto = house.toDto()
        val withOther = dto.copy(extras = buildJsonObject { dto.extras!!.forEach { (k, v) -> put(k, v) }; put("gateName", "Peacock Gate") })
        val read = withOther.toEntity()
        assertEquals("Peacock Gate", read.extrasOther!!["gateName"]!!.toString().trim('"'))
        assertFalse(found("peacock", read))
        assertTrue(found("floor 4", read))
    }
}
