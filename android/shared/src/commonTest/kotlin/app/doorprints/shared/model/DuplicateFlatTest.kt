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

/**
 * The duplicate-flat warning's rule ([DuplicateFlat], S4b-BL-85): the same vectors, in the same order, as the web's
 * `duplicate-flat.spec.ts`. House `a` is at 13.0, 80.0, placed on the map, 2 BHK on the third floor.
 */
class DuplicateFlatTest {
    private val a = FlatFacts("a", 13.0, 80.0, "MAP", bedrooms = 2, floor = 3)
    private fun bedrooms(n: Int) = (0 until n).map { HouseRoom("b$it", "BEDROOM", sort = it) }

    private val vectors: List<Pair<FlatFacts, Boolean>> = listOf(
        // 10 m north, the same BHK and floor: the same flat.
        FlatFacts("b", 13.00009, 80.0, "GPS", bedrooms = 2, floor = 3) to true,
        // 27 m east: still within 30 m.
        FlatFacts("c", 13.0, 80.00025, null, bedrooms = 2, floor = 3) to true,
        // 30.3 m east and 40 m north: too far.
        FlatFacts("d", 13.0, 80.00028, "MAP", bedrooms = 2, floor = 3) to false,
        FlatFacts("e", 13.00036, 80.0, "MAP", bedrooms = 2, floor = 3) to false,
        // Another floor, another BHK, no floor known, or no BHK known: not the same flat.
        FlatFacts("f", 13.0, 80.0, "MAP", bedrooms = 2, floor = 4) to false,
        FlatFacts("g", 13.0, 80.0, "MAP", bedrooms = 3, floor = 3) to false,
        FlatFacts("h", 13.0, 80.0, "MAP", bedrooms = 2) to false,
        FlatFacts("i", 13.0, 80.0, "MAP", floor = 3) to false,
        // An approximate point, or none yet: not compared.
        FlatFacts("j", 13.0, 80.0, "APPROX", bedrooms = 2, floor = 3) to false,
        FlatFacts("k", 0.0, 0.0, null, bedrooms = 2, floor = 3) to false,
        // The bedrooms come from the rooms when the house lists any: two bedroom rooms and no BHK match ...
        FlatFacts("l", 13.0, 80.0, "MAP", rooms = bedrooms(2) + HouseRoom("h", "HALL", sort = 9), floor = 3) to true,
        // ... and one bedroom room outweighs a BHK of 2.
        FlatFacts("m", 13.0, 80.0, "MAP", bedrooms = 2, rooms = bedrooms(1), floor = 3) to false,
        // Rooms without a bedroom leave the BHK in charge.
        FlatFacts("n", 13.0, 80.0, "MAP", bedrooms = 2, rooms = listOf(HouseRoom("k", "KITCHEN")), floor = 3) to true,
        // A house is never its own duplicate.
        FlatFacts("a", 13.0, 80.0, "MAP", bedrooms = 2, floor = 3) to false,
    )

    @Test fun eachVectorIsOrIsNotTheSameFlat() {
        for ((other, same) in vectors) assertEquals(same, DuplicateFlat.isSameFlat(a, other), other.id)
        for ((other, same) in vectors) assertEquals(same, DuplicateFlat.isSameFlat(other, a), "${other.id}, the other way round")
    }

    @Test fun ofListsTheMatchesInTheOrderGiven() {
        assertEquals(listOf("b", "c", "l", "n"), DuplicateFlat.of(a, vectors.map { it.first }))
        // The ground floor (0) is a floor like the others, and a basement level (-1) another one.
        val ground = FlatFacts("g0", 13.0, 80.0, "GPS", bedrooms = 1, floor = 0)
        assertEquals(listOf("g1"), DuplicateFlat.of(ground, listOf(ground.copy(id = "g1"), ground.copy(id = "g2", floor = -1))))
    }

    @Test fun theBedroomsAreTheRoomsWhenThereAreAny() {
        assertEquals(listOf(2, 3, 2, null), listOf(
            DuplicateFlat.bedrooms(2, null), DuplicateFlat.bedrooms(1, bedrooms(3)),
            DuplicateFlat.bedrooms(2, listOf(HouseRoom("k", "KITCHEN"))), DuplicateFlat.bedrooms(null, emptyList()),
        ))
    }
}
