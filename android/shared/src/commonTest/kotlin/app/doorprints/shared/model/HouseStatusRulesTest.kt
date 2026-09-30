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

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The end of a hunt (docs/11 5.24, slice 5): the vectors M1..M3, by the same names as the web's `house-status.spec.ts`
 * and the server's test.
 */
class HouseStatusRulesTest {
    private fun h(id: String, s: HouseStatus) = StatusHouse(id, s)

    @Test fun m1_choosingTakenReturnsThePreviousTakenHouseToShortlisted() {
        val houses = listOf(h("a", HouseStatus.TAKEN), h("b", HouseStatus.NEW), h("c", HouseStatus.REJECTED))
        assertEquals(
            listOf(h("a", HouseStatus.SHORTLISTED), h("b", HouseStatus.TAKEN), h("c", HouseStatus.REJECTED)),
            HouseStatusRules.choose(houses, "b", HouseStatus.TAKEN),
        )
        // Choosing another status touches only that house; an unknown id changes nothing.
        assertEquals(
            listOf(h("a", HouseStatus.TAKEN), h("b", HouseStatus.NOT_CHOSEN), h("c", HouseStatus.REJECTED)),
            HouseStatusRules.choose(houses, "b", HouseStatus.NOT_CHOSEN),
        )
        assertEquals(houses, HouseStatusRules.choose(houses, "zz", HouseStatus.TAKEN))
        // Taken again for the Taken house: nothing moves.
        assertEquals(houses, HouseStatusRules.choose(houses, "a", HouseStatus.TAKEN))
    }

    @Test fun m2_closeTargetsLeaveOutTheTakenRejectedAndNotChosenHouses() {
        val houses = listOf(
            h("t", HouseStatus.TAKEN), h("n", HouseStatus.NEW), h("s", HouseStatus.SHORTLISTED),
            h("r", HouseStatus.REJECTED), h("x", HouseStatus.NOT_CHOSEN), h("n2", HouseStatus.NEW),
        )
        assertEquals(listOf("n", "s", "n2"), HouseStatusRules.closeTargets(houses, "t"))
        assertEquals(emptyList(), HouseStatusRules.closeTargets(listOf(h("t", HouseStatus.TAKEN)), "t"))
    }

    @Test fun m3_afterAnySequenceOfChoicesAtMostOneHouseIsTaken() {
        val random = Random(5)
        var houses = (1..8).map { h("h$it", HouseStatus.entries[random.nextInt(3)]) }
        repeat(500) {
            val id = "h${random.nextInt(1, 10)}"
            val status = HouseStatus.entries[random.nextInt(HouseStatus.entries.size)]
            houses = HouseStatusRules.choose(houses, id, status)
            assertTrue(houses.count { it.status == HouseStatus.TAKEN } <= 1, houses.toString())
        }
    }
}
