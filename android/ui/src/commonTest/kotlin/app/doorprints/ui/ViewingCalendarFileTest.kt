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
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.ViewingIcs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The iPhone's *Add to calendar* file (S4b-BL-92a): named after the viewing as the website names it, the shared
 * [ViewingIcs] text with the house's label, its address or else its street, trimmed notes and never `withWhom`.
 */
class ViewingCalendarFileTest {
    private val viewing = Viewing(
        id = "v_a1b2c3d4", houseId = "h1", startsAt = 1_790_501_400_000, durationMin = 45, remindMin = 60,
        withWhom = "Ravi 98450 12345", notes = "  Gate 2  ",
    )
    private val house = HouseEntity(id = "h1", label = "Flat 4", street = "5th Cross", lat = 12.9, lon = 77.6, createdAt = 0, updatedAt = 0)

    @Test
    fun theFileIsTheSharedIcsOfTheViewing() {
        val (name, ics) = viewingCalendarFile(viewing, house, "House gone", "Viewing", 1_790_072_130_000)
        assertEquals("v_a1b2c3d4.ics", name)
        assertEquals(ViewingIcs.build(viewing.copy(notes = "Gate 2"), "Flat 4", "5th Cross", "Viewing", 1_790_072_130_000), ics)
        assertTrue(ics.contains("\r\nTRIGGER:-PT60M\r\n"))
        assertFalse(ics.contains("Ravi") || ics.contains("98450"))
    }

    @Test
    fun theAddressWinsAndAGoneHouseIsNamedSo() {
        val withAddress = viewingCalendarFile(viewing, house.copy(address = "12, 5th Cross"), "House gone", "Viewing", 0).second
        assertTrue(withAddress.contains("\r\nLOCATION:12\\, 5th Cross\r\n"))
        val gone = viewingCalendarFile(viewing.copy(notes = " ", remindMin = 0), null, "House gone", "मकान देखना", 0).second
        assertTrue(gone.contains("\r\nSUMMARY:मकान देखना: House gone\r\n"))
        assertFalse(gone.contains("LOCATION") || gone.contains("DESCRIPTION:"))
    }
}
