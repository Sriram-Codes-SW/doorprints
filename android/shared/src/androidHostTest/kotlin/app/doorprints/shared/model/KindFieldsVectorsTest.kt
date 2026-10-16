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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/**
 * [KindFields] (kinds PR 4, S4b-BL-208, docs/06 TC-U-210) against the kind file `docs/schemas/kinds/house.json`, whose
 * `extras` it copies, and the shared renderer vectors `docs/schemas/kinds/kind-field-vectors.json`: each extra's
 * control, label key and `forCadences` on the phone, and which extras a section shows per money cadence. The website's
 * twin is `kind-fields.spec.ts`; `:ui`'s `KindExtrasLayoutTest` checks the rows and the digits. Not in commonTest: it
 * reads repository files.
 */
class KindFieldsVectorsTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private fun json(relative: String) = Json.parseToJsonElement(repoFile(relative).readText()).jsonObject
    private val houseExtras = json("docs/schemas/kinds/house.json").getValue("extras").jsonArray.map { it.jsonObject }
    private val vectors = json("docs/schemas/kinds/kind-field-vectors.json")
    private val kind = vectors.getValue("kinds").jsonArray.first().jsonObject
    private val fields = kind.getValue("fields").jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.list(key: String) = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }

    @Test
    fun theCopyIsTheKindFilesExtras() {
        val copy = KindFields.HOUSE.extras
        assertEquals(houseExtras.map { it.str("id") }, copy.map { it.id })
        for ((f, e) in houseExtras.zip(copy)) {
            val id = e.id
            assertEquals(id, f.str("type"), e.type)
            assertEquals(id, f.str("section"), e.section)
            assertEquals(id, f.long("order")?.toInt(), e.order)
            assertEquals(id, f["integer"]?.jsonPrimitive?.booleanOrNull ?: false, e.integer)
            assertEquals(id, f.long("min"), e.min)
            assertEquals(id, f.long("max"), e.max)
            assertEquals(id, f.str("unit"), e.unit)
            assertEquals(id, f.list("cadences"), e.cadences)
            assertEquals(id, f.str("cadenceFrom"), e.cadenceFrom)
            assertEquals(id, f.list("forCadences"), e.forCadences)
            assertEquals(id, f.str("was"), e.was)
        }
        assertEquals(KindFields.HOUSE.extras.map { it.id }, Candidates.HOUSE_EXTRAS)
    }

    @Test
    fun eachExtraHasTheVectorsControlLabelAndCadences() {
        assertEquals("doorprints-kind-field-vectors/1", vectors.str("format"))
        assertEquals("house", kind.str("id"))
        assertEquals(fields.map { it.str("id") }, KindFields.HOUSE.extras.map { it.id })
        for ((v, e) in fields.zip(KindFields.HOUSE.extras)) {
            assertEquals(e.id, v.str("control")!!.uppercase(), e.control.name)
            assertEquals(e.id, v.getValue("android").jsonObject.str("label"), KindFields.HOUSE.label(e))
            assertEquals(e.id, v.list("forCadences"), e.forCadences)
            assertEquals(e.id, v.str("section"), e.section)
        }
    }

    @Test
    fun aSectionShowsItsExtrasPerCadenceAsTheVectorsLayThemOut() {
        val rows = kind.getValue("rows").jsonArray.map { it.jsonObject }.filter { it.str("stack") == "android" }
        assertEquals(6, rows.size)
        for (r in rows) {
            val cadence = r.str("cadence")
            val want = r.getValue("rows").jsonArray.flatMap { row -> row.jsonArray.map { it.jsonPrimitive.content } }
            assertEquals("${r.str("section")} $cadence", want, KindFields.HOUSE.shown(r.str("section")!!, cadence).map { it.id })
        }
        assertEquals(emptyList<KindExtra>(), KindFields.HOUSE.shown("rooms", "month"))
    }

    @Test
    fun aHiddenExtraOrSectionIsNotShownAndTextHasNoControl() {
        val labels = KindLabels(
            kind = "test", keys = mapOf("field.a" to "house_bhk", "field.b" to "house_floor", "section.s" to "house_cost"),
            byCadence = emptyMap(), sections = mapOf("a" to "s", "b" to "s"), hidden = setOf("field.b"),
        )
        val extras = listOf(KindExtra("b", "bool", "s", 2, was = "b"), KindExtra("a", "bool", "s", 1, was = "a"))
        assertEquals(listOf("a"), KindFields(labels, extras).shown("s", null).map { it.id })
        val hiddenSection = KindLabels("test", mapOf("field.a" to "house_bhk", "section.s" to "house_cost"), emptyMap(), mapOf("a" to "s"), setOf("section.s"))
        assertEquals(emptyList<KindExtra>(), KindFields(hiddenSection, extras).shown("s", null))
        assertThrows(IllegalStateException::class.java) { KindFields(labels, extras).label(KindExtra("zz", "bool", "s", 1, was = "zz")) }
        // Text and enum have a control since the schools kind (S4b-BL-209); an unknown type still has none.
        assertEquals(ExtraControl.TEXT, KindExtra("x", "text", "s", 1, was = "x").control)
        assertEquals(ExtraControl.ENUM, KindExtra("x", "enum", "s", 1, was = "x").control)
        assertThrows(IllegalStateException::class.java) { KindExtra("x", "colour", "s", 1, was = "x").control }
        assertEquals(ExtraControl.LEVEL, KindExtra("x", "number", "s", 1, unit = "level", was = "x").control)
        assertEquals(KindFields.HOUSE, KindFields.of("house"))
        assertNull(KindFields.of("school"))
    }
}
