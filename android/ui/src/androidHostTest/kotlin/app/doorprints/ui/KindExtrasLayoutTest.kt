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
import app.doorprints.shared.model.HouseCost
import app.doorprints.shared.model.KindExtra
import app.doorprints.shared.model.KindFields
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

/**
 * The phone's renderer of the typed extras (kinds PR 4, S4b-BL-208, docs/06 TC-U-210) against
 * `docs/schemas/kinds/kind-field-vectors.json`: the rows each section's extras are drawn in per money cadence
 * ([phoneRows]), the digits each field keeps ([maxDigits]), every label a Compose string, and each value written
 * where the house keeps it ([withExtra]). `HouseFormFieldsTest` (`:app`) checks the drawn form itself. Not in
 * commonTest: it reads a repository file.
 */
class KindExtrasLayoutTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val kind = Json.parseToJsonElement(repoFile("docs/schemas/kinds/kind-field-vectors.json").readText())
        .jsonObject.getValue("kinds").jsonArray.first().jsonObject
    private val fields = kind.getValue("fields").jsonArray.map { it.jsonObject }
    private val house = KindFields.HOUSE

    @Test
    fun theExtrasAreLaidOutInTheVectorsRows() {
        val rows = kind.getValue("rows").jsonArray.map { it.jsonObject }.filter { it.getValue("stack").jsonPrimitive.content == "android" }
        for (r in rows) {
            val section = r.getValue("section").jsonPrimitive.content
            val cadence = (r["cadence"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val want = r.getValue("rows").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }
            assertEquals(want, phoneRows(house.shown(section, cadence)).map { row -> row.map { it.id } }, "$section $cadence")
        }
    }

    @Test
    fun eachFieldKeepsTheVectorsDigitsAndHasItsComposeLabel() {
        for (v in fields) {
            val id = v.getValue("id").jsonPrimitive.content
            val android = v.getValue("android").jsonObject
            val extra = house.extras.single { it.id == id }
            val digits = android["digits"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
            assertEquals(digits, maxDigits(extra), id)
            assertNotNull(Res.allStringResources[house.label(extra)], id)
            android["switch"]?.let { assertNotNull(Res.allStringResources[it.jsonPrimitive.content], "$id switch") }
            for (key in listOf("hint", "pick")) android[key]?.let { assertNotNull(Res.allStringResources[it.jsonPrimitive.content], "$id $key") }
        }
    }

    @Test
    fun aPairBreaksBeforeASwitchADateAndALevel() {
        fun f(id: String, type: String, unit: String? = null) = KindExtra(id, type, "s", 1, unit = unit, was = id)
        val rows = phoneRows(listOf(f("a", "number"), f("b", "bool"), f("c", "money"), f("d", "number"), f("e", "number"), f("g", "date"), f("h", "number", "level"), f("i", "number")))
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c", "d"), listOf("e"), listOf("g"), listOf("h"), listOf("i")), rows.map { r -> r.map { it.id } })
        assertEquals(null, maxDigits(f("x", "bool")))
        assertEquals(2, maxDigits(f("x", "number")))
    }

    @Test
    fun eachValueLandsWhereTheHouseKeepsIt() {
        val h = HouseEntity(id = "k", label = "Kochi flat", lat = 9.9312, lon = 76.2673, priceType = "RENT", createdAt = 1, updatedAt = 1)
        fun extra(id: String) = house.extras.single { it.id == id }
        val set = h
            .withExtra(extra("bedrooms"), JsonPrimitive(3))
            .withExtra(extra("areaSqft"), JsonPrimitive(1150))
            .withExtra(extra("floor"), JsonPrimitive(-2))
            .withExtra(extra("deposit"), JsonObject(mapOf("amount" to JsonPrimitive(64000), "cadence" to JsonPrimitive("once"))))
            .withExtra(extra("maintenanceIncluded"), JsonPrimitive(true))
            .withExtra(extra("availableFrom"), JsonPrimitive("2026-11-01"))
            .withExtra(extra("noticeMonths"), JsonPrimitive(2))
        assertEquals(listOf(3, 1150, -2), listOf(set.bedrooms, set.areaSqft, set.floor))
        assertEquals(HouseCost(deposit = 64000, maintenanceIncluded = true, noticeMonths = 2, availableFrom = "2026-11-01"), set.cost)
        assertEquals(JsonPrimitive(3), set.extraValue(extra("bedrooms")))
        val cleared = set.withExtra(extra("deposit"), null).withExtra(extra("maintenanceIncluded"), null)
            .withExtra(extra("availableFrom"), null).withExtra(extra("noticeMonths"), null)
        assertEquals(null, cleared.cost)
        assertEquals(h.copy(bedrooms = 3, areaSqft = 1150, floor = -2), cleared)
        assertFailsWith<IllegalStateException> { kindField("schoolBoard") }
        assertFailsWith<IllegalStateException> { kindSection("details") }
        assertEquals(Res.allStringResources["house_price_rent"], kindField("money", "month"))
        assertEquals(Res.allStringResources["house_buy"], kindRef("cadence.once"))
    }
}
