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
 * A number extra outside the kind file's range is not saved (S4b-BL-261 item 2; docs/03 §18.16), the twin of the website's
 * `storedValue` (`school-form.spec.ts`): a school's commute is kept from 0 to 300 minutes, and 301 or -1, which the form never
 * types but a listing fill or another device can bring, is dropped when the form saves. Everything else is left as it is.
 */
class KindExtrasRangeTest {
    private val school = DevKinds.of("school", on = true).fields
    private fun candidate(extras: JsonObject?) = HouseEntity(
        id = "s1", label = "Pine Hill", lat = 26.14, lon = 91.79, priceType = null, createdAt = 1L, updatedAt = 1L,
        kind = "school", extrasOther = extras,
    )

    @Test
    fun theEdgesOfTheRangeAreKeptAndOutsideIsDropped() {
        for (kept in listOf(0, 25, 300)) {
            val h = candidate(buildJsonObject { put("commuteMinutes", kept) })
            assertSame(h, h.withInRangeExtras(school), "$kept is within 0 to 300")
        }
        for (out in listOf(301, -1, 1_000_000_000)) {
            val h = candidate(buildJsonObject { put("commuteMinutes", out); put("board", "CBSE") })
            assertEquals(buildJsonObject { put("board", "CBSE") }, h.withInRangeExtras(school).extrasOther, "$out is outside it; the other extras stay")
        }
        assertEquals(null, candidate(buildJsonObject { put("commuteMinutes", 301) }).withInRangeExtras(school).extrasOther, "nothing left is no extras")
    }

    @Test
    fun aListingFillsOutOfRangeNumberIsNotSavedButTheFeesAndTheTextAre() {
        val draft = HouseDraftDto(label = "Pine Hill", extras = buildJsonObject { put("commuteMinutes", 500); put("grades", "1 to 8"); put("busService", true) })
        val filled = mergeListingExtras(mergeListing(candidate(null), draft, labelIsPlaceholder = true), draft, school)
        assertEquals(JsonPrimitive(500), filled.house.extrasOther!!["commuteMinutes"], "the fill itself does not judge")
        val saved = filled.house.withInRangeExtras(school)
        assertEquals(buildJsonObject { put("grades", "1 to 8"); put("busService", true) }, saved.extrasOther)
    }

    @Test
    fun aHouseOrAValueThatIsNotANumberIsUntouched() {
        val house = HouseEntity(id = "h1", label = "Flat", lat = 12.97, lon = 77.64, createdAt = 1L, updatedAt = 1L)
        assertSame(house, house.withInRangeExtras(BuiltInKind.HOUSE.fields))
        val h = candidate(buildJsonObject { put("commuteMinutes", "far"); put("unknownThing", 99999) })
        assertSame(h, h.withInRangeExtras(school), "a text in a number's place, and an extra the kind does not have, stay as they are")
    }
}
