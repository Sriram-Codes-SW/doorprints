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
import kotlinx.serialization.json.JsonElement
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

    /**
     * A number and an amount are range rows (docs/03 section 18.27) of the synthetic kind of the conformance suite, registered for the
     * run: the rows, which candidates a set of ranges keeps, how a range text is read and what two typed ends make of it.
     */
    @Test
    @OptIn(TestOnlyKinds::class)
    fun aNumberAndAnAmountAreRangeRowsAndKeepWhatTheVectorsKeep() {
        val ranges = vectors.getValue("ranges").jsonObject
        val undo = KindFileKinds.register(repoFile("docs/schemas/kinds/test-all.json").readText())
        try {
            val fields = DevKinds.of(ranges.getValue("kind").jsonPrimitive.content, on = true).fields
            assertEquals("zz_all", fields.kind)
            val want = ranges.getValue("rows").jsonArray.map { it.jsonObject }
            assertEquals(want.map { it.getValue("id").jsonPrimitive.content }, KindFilter.rows(fields).map { it.id })
            for ((w, r) in want.zip(KindFilter.rows(fields))) {
                assertEquals(w.getValue("type").jsonPrimitive.content, r.type)
                assertEquals(r.id, w.getValue("choices").jsonArray.map { it.jsonPrimitive.content }, KindFilter.choices(r))
                assertEquals(r.id, r.type == "number" || r.type == "money", KindFilter.isRange(r))
            }
            val people = ranges.getValue("candidates").jsonObject
            for (m in ranges.getValue("matches").jsonArray.map { it.jsonObject }) {
                val filter = filterOf(m.getValue("filter").jsonObject)
                val kept = people.filter { (_, c) -> KindFilter.matches(filter, fields, c.jsonObject) }.keys.toList()
                assertEquals("$filter", m.getValue("keeps").jsonArray.map { it.jsonPrimitive.content }, kept)
            }
            assertEquals(2, KindFilter.active(mapOf("seats" to "1:", "fees" to ":5", "board" to "NOPE", "x" to "1:2"), fields))
            assertEquals(0, KindFilter.active(mapOf("seats" to "5:2", "fees" to ":"), fields))
        } finally {
            undo()
        }
        assertEquals("the kind is gone again", BuiltInKind.HOUSE, DevKinds.of("zz_all", on = true))
    }

    @Test
    fun aRangeTextIsReadAndMadeAsTheVectorsSay() {
        val ranges = vectors.getValue("ranges").jsonObject
        for (p in ranges.getValue("parse").jsonArray.map { it.jsonObject }) {
            val text = p.getValue("text").jsonPrimitive.content
            val valid = p["valid"]?.jsonPrimitive?.content != "false"
            val got = KindFilter.parseRange(text)
            if (!valid) assertEquals(text, null, got)
            else assertEquals(text, KindFilter.Range(p["min"].number(), p["max"].number()), got)
        }
        for (e in ranges.getValue("ends").jsonArray.map { it.jsonObject }) {
            val expected = e.getValue("text").let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals("$e", expected, KindFilter.rangeText(e.getValue("min").jsonPrimitive.content, e.getValue("max").jsonPrimitive.content))
        }
    }

    private fun JsonElement?.number(): Double? = if (this == null || this is JsonNull) null else jsonPrimitive.content.toDouble()

    @Test
    fun onlyASetRowTheKindOffersCountsAsActive() {
        assertEquals(0, KindFilter.active(emptyMap(), school))
        assertEquals(0, KindFilter.active(mapOf("board" to null, "medium" to "AEBC", "nope" to "x"), school))
        assertEquals(2, KindFilter.active(mapOf("board" to "CBSE", "busService" to "no"), school))
    }
}
