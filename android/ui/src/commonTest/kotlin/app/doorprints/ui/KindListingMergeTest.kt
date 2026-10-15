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
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.model.BuiltInKind
import app.doorprints.shared.model.DevKinds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * *Paste listing text* for a school (kinds PR 7, S4b-BL-211): the listing's extras go into the form by the house's rule,
 * only into **empty** ones; a set one keeps its value and is named. A house's draft has no extras, so its fill is the
 * house's merge alone.
 */
class KindListingMergeTest {
    private val school = DevKinds.of("school", on = true).fields
    private fun candidate(extras: JsonObject?) = HouseEntity(
        id = "s1", label = "New school", lat = 26.14, lon = 91.79, priceType = null, createdAt = 1L, updatedAt = 1L,
        kind = "school", extrasOther = extras,
    )
    private val fees = buildJsonObject { put("amount", 96000); put("cadence", "year") }
    private val draft = HouseDraftDto(label = "Pine Hill School", extras = buildJsonObject {
        put("board", "CBSE"); put("medium", "REGIONAL"); put("busService", true); put("fees", fees); put("unknownThing", "x")
    })

    @Test
    fun emptyExtrasAreFilledASetOneIsKeptAndNamedAndNothingTheKindDoesNotHave() {
        val form = candidate(buildJsonObject { put("board", "STATE") })
        val merged = mergeListingExtras(mergeListing(form, draft, labelIsPlaceholder = true), draft, school)
        assertEquals(
            buildJsonObject { put("board", "STATE"); put("medium", "REGIONAL"); put("busService", true); put("fees", fees) },
            merged.house.extrasOther,
        )
        assertEquals(listOf("medium", "busService", "fees"), merged.extrasFilled)
        assertEquals(listOf("board"), merged.extrasKept)
        assertEquals("Pine Hill School", merged.house.label)
        assertEquals(JsonPrimitive("REGIONAL"), merged.house.extras!!["medium"])
    }

    @Test
    fun aHousesDraftHasNoExtrasSoItsFillIsTheHousesAlone() {
        val house = HouseEntity(id = "h1", label = "New house", lat = 12.97, lon = 77.64, createdAt = 1L, updatedAt = 1L)
        val m = mergeListing(house, HouseDraftDto(label = "2BHK", price = 25000), labelIsPlaceholder = true)
        assertSame(m, mergeListingExtras(m, HouseDraftDto(label = "2BHK", price = 25000), BuiltInKind.HOUSE.fields))
    }

    /** S4b-BL-272: the extras a fill wrote carry the "from the listing" mark until their value changes; a kept one has none. */
    @Test
    fun anExtraTheFillWroteKeepsItsMarkUntilItIsEditedAndAKeptOneHasNone() {
        val form = candidate(buildJsonObject { put("board", "STATE") })
        val merged = mergeListingExtras(mergeListing(form, draft, labelIsPlaceholder = true), draft, school)
        assertEquals(listOf("medium", "busService", "fees"), remainingExtraMarks(merged, merged.house), "board was kept: no mark")
        val edited = merged.house.extrasOther!!.toMutableMap()
        edited["medium"] = JsonPrimitive("HINDI")
        edited["fees"] = buildJsonObject { put("amount", 90000); put("cadence", "year") }
        assertEquals(listOf("busService"), remainingExtraMarks(merged, merged.house.copy(extrasOther = JsonObject(edited))), "a changed value, a money object too, loses it")
        edited.remove("busService")
        assertEquals(emptyList(), remainingExtraMarks(merged, merged.house.copy(extrasOther = JsonObject(edited))), "a cleared value has none")
        val house = HouseEntity(id = "h1", label = "New house", lat = 12.97, lon = 77.64, createdAt = 1L, updatedAt = 1L)
        val plain = mergeListing(house, HouseDraftDto(label = "2BHK"), labelIsPlaceholder = true)
        assertEquals(emptyList(), remainingExtraMarks(plain, plain.house), "a house has no extras to mark")
    }

    /** S4b-BL-273: the paste dialog asks the extract for the school's kind, and a house's call stays the house's own. */
    @Test
    fun theDialogAsksForTheSchoolsKindAndAHouseKeepsItsOwnCall() {
        assertEquals("school", pasteKind(DevKinds.of("school", on = true)))
        assertEquals(null, pasteKind(BuiltInKind.HOUSE))
    }
}
