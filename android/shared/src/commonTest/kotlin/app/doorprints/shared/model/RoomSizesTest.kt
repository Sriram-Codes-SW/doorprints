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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Room sizes (docs/11 5.6, slice 1c): the four shared vectors, in the same order as the web's `room-sizes.spec.ts`,
 * then what the form, Compare and the copies write.
 */
class RoomSizesTest {
    @Test fun thirteenFeetIs396Centimetres() {
        assertEquals(396, RoomSizes.feetInchesToCm(13, 0))
        assertEquals(RoomSizes.FeetInches(13, 0), RoomSizes.cmToFeetInches(396))
    }

    @Test fun twelveFeetIs366Centimetres() {
        assertEquals(366, RoomSizes.feetInchesToCm(12, 0))
        assertEquals(RoomSizes.FeetInches(12, 0), RoomSizes.cmToFeetInches(366))
    }

    @Test fun a396By366RoomIs156SquareFeet() = assertEquals(156L, RoomSizes.areaSqFt(396, 366)) // 155.99 rounds up

    @Test fun a396By366RoomIs14Point5SquareMetres() = assertEquals(14.5, RoomSizes.areaSqM(396, 366))

    @Test fun inchesCarryAndRoundTrip() {
        assertEquals(RoomSizes.FeetInches(9, 10), RoomSizes.cmToFeetInches(300))
        assertEquals(RoomSizes.FeetInches(8, 0), RoomSizes.cmToFeetInches(244))
        // Every whole inch up to 164 ft comes back as itself: a form in feet never changes what was typed.
        for (inches in 0..(164 * 12)) {
            val cm = RoomSizes.feetInchesToCm(inches / 12, inches % 12)
            assertEquals(RoomSizes.FeetInches(inches / 12, inches % 12), RoomSizes.cmToFeetInches(cm))
        }
    }

    @Test fun theTextsThePeopleRead() {
        assertEquals("13 ft 0 in", RoomSizes.feetInchesText(396))
        assertEquals("3.96", RoomSizes.metresText(396))
        assertEquals("0.05", RoomSizes.metresText(5))
        assertEquals("13 ft 0 in × 12 ft 0 in", RoomSizes.sizeText(HouseRoom("r", lengthCm = 396, widthCm = 366), LengthUnit.FT))
        assertEquals("3.96 m × 3.66 m", RoomSizes.sizeText(HouseRoom("r", lengthCm = 396, widthCm = 366), LengthUnit.M))
        assertNull(RoomSizes.sizeText(HouseRoom("r", lengthCm = 396), LengthUnit.FT))
        assertEquals("156 sq ft", RoomSizes.areaText(396L * 366, LengthUnit.FT))
        assertEquals("14.5 m²", RoomSizes.areaText(396L * 366, LengthUnit.M))
    }

    @Test fun theFormParsesMetresAndFeet() {
        assertEquals(396, RoomSizes.parseMetres("3.96"))
        assertEquals(396, RoomSizes.parseMetres(" 3,96 "))
        assertEquals(400, RoomSizes.parseMetres("4"))
        assertEquals(350, RoomSizes.parseMetres("3.5"))
        assertNull(RoomSizes.parseMetres(""))
        assertNull(RoomSizes.parseMetres("3.965"))
        assertNull(RoomSizes.parseMetres("50.01"), "over 5000 cm")
        assertNull(RoomSizes.parseMetres("abc"))
        assertEquals(396, RoomSizes.parseFeetInches("13", ""))
        assertEquals(399, RoomSizes.parseFeetInches("13", "1"))
        assertEquals(10, RoomSizes.parseFeetInches("", "4"))
        assertNull(RoomSizes.parseFeetInches("13", "12"))
        assertNull(RoomSizes.parseFeetInches("x", "1"))
        assertNull(RoomSizes.parseFeetInches("165", "0"), "over 5000 cm")
    }
}
