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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What every reader keeps of a house's rooms ([HouseRooms.coerced], docs/11 5.6, slice 1c), as the web's reader does. */
class HouseRoomsTest {
    @Test fun anUnknownTypeIsOther() {
        val rooms = HouseRooms.coerced(listOf(HouseRoom("r1", type = "GARAGE"), HouseRoom("r2", type = "KITCHEN", sort = 1)))!!
        assertEquals(listOf("OTHER", "KITCHEN"), rooms.map { it.type })
        assertEquals(RoomType.OTHER, HouseRoom("x", type = "").roomType)
    }

    @Test fun aValueOutOfRangeIsUnknownAndBlankTextIsNone() {
        val room = HouseRooms.coerced(
            listOf(HouseRoom("r1", "BEDROOM", name = "  ", lengthCm = 5001, widthCm = -1, condition = 6, notes = " ", sort = -3)),
        )!!.single()
        assertEquals(HouseRoom("r1", "BEDROOM", sort = 0), room)
        assertEquals(
            HouseRoom("r1", "BEDROOM", name = "Hall", lengthCm = 5000, widthCm = 0, condition = 1),
            HouseRooms.coerced(listOf(HouseRoom("r1", "BEDROOM", name = " Hall ", lengthCm = 5000, widthCm = 0, condition = 1)))!!.single(),
        )
        assertNull(HouseRooms.coerced(listOf(HouseRoom("r1", condition = 0)))!!.single().condition)
        assertNull(HouseRooms.coerced(listOf(HouseRoom("r1", name = "n".repeat(61))))!!.single().name)
    }

    @Test fun moreThanThirtyKeepTheFirstThirtyBySort() {
        val rooms = (0 until 35).map { HouseRoom("r$it", sort = 34 - it) }
        val kept = HouseRooms.coerced(rooms)!!
        assertEquals(30, kept.size)
        assertEquals((0 until 30).toList(), kept.map { it.sort })
        assertFalse(kept.any { it.id == "r0" }, "the room sorted last is the one left out")
    }

    @Test fun aDuplicateIdIsDroppedAndABadIdToo() {
        val kept = HouseRooms.coerced(
            listOf(HouseRoom("a", name = "First"), HouseRoom("a", name = "Second", sort = 1), HouseRoom("..", sort = 2), HouseRoom("a/b")),
        )!!
        assertEquals(listOf("First"), kept.map { it.name })
    }

    @Test fun theOrderIsSortThenIdAndNoRoomsIsNull() {
        val kept = HouseRooms.coerced(listOf(HouseRoom("b", sort = 1), HouseRoom("c"), HouseRoom("a", sort = 1)))!!
        assertEquals(listOf("c", "a", "b"), kept.map { it.id })
        assertNull(HouseRooms.coerced(emptyList()))
        assertNull(HouseRooms.coerced(null))
        assertNull(HouseRooms.coerced(listOf(HouseRoom("."))))
    }

    @Test fun theAreaNeedsBothSizesAndTheTotalCountsTheSizedRooms() {
        assertEquals(396L * 366, HouseRooms.areaSqCm(HouseRoom("r", lengthCm = 396, widthCm = 366)))
        assertNull(HouseRooms.areaSqCm(HouseRoom("r", lengthCm = 396)))
        val rooms = listOf(HouseRoom("a", lengthCm = 396, widthCm = 366), HouseRoom("b", lengthCm = 300, widthCm = 244), HouseRoom("c"))
        assertEquals((396L * 366 + 300L * 244) to 2, HouseRooms.totalAreaSqCm(rooms))
        assertEquals(0, HouseRooms.nextSort(null))
        assertEquals(3, HouseRooms.nextSort(listOf(HouseRoom("a", sort = 2), HouseRoom("b"))))
    }

    @Test fun validityIsWhatABackupsCheckDemands() {
        assertTrue(HouseRoom("c1111111-1111-4111-8111-111111111111", "BEDROOM", "Master", 396, 366, 4, "n", 0).isValid)
        assertTrue(HouseRoom("r", type = "GARAGE").isValid, "an unknown type reads as OTHER, it is not refused")
        for (bad in listOf(
            HouseRoom("r", lengthCm = 5001), HouseRoom("r", widthCm = -1), HouseRoom("r", condition = 6), HouseRoom("r", condition = 0),
            HouseRoom("r", name = "n".repeat(61)), HouseRoom("r", notes = "n".repeat(2001)), HouseRoom("r", sort = -1),
            HouseRoom(".."), HouseRoom("a/b"), HouseRoom(""),
        )) assertFalse(bad.isValid, bad.toString())
    }
}
