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
package app.doorprints.shared.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [KindFilter] (S4b-BL-265; docs/03 §18.16, docs/06) against `docs/schemas/kinds/kind-filter-vectors.json`, the table the
 * website's `extra-filter.spec.ts` reads too: the rows a kind offers and which candidates a set of rows keeps. The file's
 * `address` part is the website's (the phone has no address); the rest is held here. Not in commonTest: it reads repository files.
 */
class KindFilterVectorsTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val vectors = Json.parseToJsonElement(repoFile("docs/schemas/kinds/kind-filter-vectors.json").readText()).jsonObject
    private val candidates = vectors.getValue("candidates").jsonObject
    private val school = SchoolKind.FIELDS

    private fun filterOf(o: JsonObject): Map<String, String?> = o.mapValues { (_, v) -> if (v is JsonNull) null else v.jsonPrimitive.content }

    @Test
    fun theFormatIsTheOneThePhoneReads() {
        assertEquals("doorprints-kind-filter-vectors/1", vectors.getValue("format").jsonPrimitive.content)
    }

    @Test
    fun theRowsAKindOffersAreItsEnumsAndSwitchesAndAHouseHasNone() {
        val rows = vectors.getValue("rows").jsonObject
        assertEquals(emptyList<Any>(), rows.getValue("house").jsonArray.toList())
        assertEquals(emptyList<KindExtra>(), KindFilter.rows(KindFields.HOUSE))
        val want = rows.getValue("school").jsonArray.map { it.jsonObject }
        assertEquals(want.map { it.getValue("id").jsonPrimitive.content }, KindFilter.rows(school).map { it.id })
        for ((w, r) in want.zip(KindFilter.rows(school))) {
            assertEquals(w.getValue("type").jsonPrimitive.content, r.type)
            assertEquals(r.id, w.getValue("choices").jsonArray.map { it.jsonPrimitive.content }, KindFilter.choices(r))
        }
    }

    @Test
    fun everyFilterKeepsExactlyTheCandidatesTheVectorsSay() {
        val matches = vectors.getValue("matches").jsonArray.map { it.jsonObject }
        assertEquals(10, matches.size)
        for (m in matches) {
            val filter = filterOf(m.getValue("filter").jsonObject)
            val kept = candidates.filter { (_, c) -> KindFilter.matches(filter, school, c.jsonObject) }.keys.toList()
            assertEquals("$filter", m.getValue("keeps").jsonArray.map { it.jsonPrimitive.content }, kept)
        }
    }

    @Test
    fun aHousesListKeepsEveryCandidateWhateverTheFilterSays() {
        val house = vectors.getValue("houseKeeps").jsonObject
        val filter = filterOf(house.getValue("filter").jsonObject)
        val kept = candidates.filter { (_, c) -> KindFilter.matches(filter, KindFields.HOUSE, c.jsonObject) }.keys.toList()
        assertEquals(house.getValue("keeps").jsonArray.map { it.jsonPrimitive.content }, kept)
        assertEquals(0, KindFilter.active(filter, KindFields.HOUSE))
    }

    @Test
    fun onlyASetRowTheKindOffersCountsAsActive() {
        assertEquals(0, KindFilter.active(emptyMap(), school))
        assertEquals(0, KindFilter.active(mapOf("board" to null, "medium" to "AEBC", "nope" to "x"), school))
        assertEquals(2, KindFilter.active(mapOf("board" to "CBSE", "busService" to "no"), school))
    }
}
