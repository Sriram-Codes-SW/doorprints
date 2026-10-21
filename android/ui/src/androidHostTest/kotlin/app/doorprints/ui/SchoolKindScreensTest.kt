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
import app.doorprints.shared.model.BuiltInKind
import app.doorprints.shared.model.DevKinds
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.allStringResources
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The hidden schools kind's form on the phone and the iPhone (kinds PR 5, S4b-BL-209; docs/06 TC-U-211), against
 * `docs/schemas/kinds/school-vectors.json`: every Android key of the school is a Compose string of `:ui`; the extras are
 * laid out in the vectors' rows with the vectors' digits and option labels; a value (an option, a text, a fee with its
 * cadence) lands as one of the candidate's extras the house model does not know; and the kind picker's change makes the
 * draft a school with no price type, or a house with its rent back. `:shared`'s `SchoolKindVectorsTest` checks the words.
 */
class SchoolKindScreensTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val vectors = Json.parseToJsonElement(repoFile("docs/schemas/kinds/school-vectors.json").readText()).jsonObject
    private val school: BuiltInKind = DevKinds.of("school", on = true)

    @Test
    fun everyAndroidKeyOfTheSchoolIsAComposeString() {
        val en = vectors.getValue("kind").jsonObject.getValue("labels").jsonObject.getValue("android").jsonObject.getValue("en").jsonObject
        for ((ref, text) in en) {
            if (text is JsonNull) continue
            val key = if (ref.startsWith("option.")) null else school.labels.resolve(ref)?.key
            if (key != null) assertNotNull(Res.allStringResources[key], ref)
        }
        for (f in school.fields.extras) {
            assertNotNull(Res.allStringResources[school.fields.label(f)], f.id)
            for (o in f.options) assertNotNull(Res.allStringResources[o.label], "${f.id} ${o.id}")
        }
        assertNotNull(Res.allStringResources["kind_option_not_set"])
        assertNotNull(Res.allStringResources["kind_picker"])
        assertEquals<UiWord?>(Res.allStringResources["school_name"]?.let(UiWord::Res), school.labels.fieldText("label"))
        assertFailsWith<IllegalStateException> { school.labels.fieldText("money") }
        assertFailsWith<IllegalStateException> { school.labels.sectionText("rooms") }
    }

    @Test
    fun theExtrasAreLaidOutInTheVectorsRowsWithTheirDigitsAndOptions() {
        for (r in vectors.getValue("rows").jsonArray.map { it.jsonObject }.filter { it.getValue("stack").jsonPrimitive.content == "android" }) {
            val section = r.getValue("section").jsonPrimitive.content
            val want = r.getValue("rows").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }
            assertEquals(want, phoneRows(school.fields.shown(section, null)).map { row -> row.map { it.id } }, section)
        }
        for (v in vectors.getValue("fields").jsonArray.map { it.jsonObject }) {
            val android = v.getValue("android").jsonObject
            val extra = school.fields.extras.single { it.id == v.getValue("id").jsonPrimitive.content }
            assertEquals(android["digits"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int, maxDigits(extra), extra.id)
            assertEquals(android["options"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(), extra.options.map { it.label }, extra.id)
        }
    }

    @Test
    fun eachValueLandsAsOneOfTheCandidatesOtherExtras() {
        val h = HouseEntity(id = "s", label = "Pine Hill School", lat = 26.1433, lon = 91.7898, createdAt = 1, updatedAt = 1).forKind(school)
        assertEquals("school", h.kind)
        assertNull(h.priceType)
        fun extra(id: String) = school.fields.extras.single { it.id == id }
        val set = h
            .withExtra(extra("board"), JsonPrimitive("ICSE"))
            .withExtra(extra("grades"), JsonPrimitive("LKG to 10"))
            .withExtra(extra("fees"), JsonObject(mapOf("amount" to JsonPrimitive(84000), "cadence" to JsonPrimitive("year"))))
            .withExtra(extra("busService"), JsonPrimitive(true))
        assertEquals(listOf("board", "busService", "fees", "grades"), set.extrasOther!!.keys.toList())
        assertEquals(JsonPrimitive("ICSE"), set.extraValue(extra("board")))
        assertEquals(listOf(null, null, null), listOf(set.bedrooms, set.cost, set.floor))
        assertEquals(listOf("busService", "fees", "grades"), set.withExtra(extra("board"), null).extrasOther!!.keys.toList())
        val house = set.forKind(BuiltInKind.HOUSE)
        assertEquals(listOf("house", "RENT"), listOf(house.kind, house.priceType))
    }
}
