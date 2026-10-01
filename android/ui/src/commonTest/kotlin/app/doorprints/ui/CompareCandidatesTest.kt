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
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.Scoring
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compare's houses (S4b-BL-99 a): only those in the running, so neither a Rejected nor a Not chosen house is offered,
 * the same rule as the website's Compare and the Plan on every stack; the Taken house stays, shortlisted ones first.
 */
class CompareCandidatesTest {
    private fun house(id: String, status: HouseStatus) =
        HouseEntity(id = id, label = id, lat = 12.9, lon = 77.6, status = status, createdAt = 0, updatedAt = 0)

    @Test
    fun rejectedAndNotChosenHousesAreLeftOut() {
        val houses = listOf(
            house("new", HouseStatus.NEW),
            house("rejected", HouseStatus.REJECTED),
            house("taken", HouseStatus.TAKEN),
            house("notChosen", HouseStatus.NOT_CHOSEN),
            house("shortlisted", HouseStatus.SHORTLISTED),
        )
        val ids = compareCandidates(houses, Scoring.DEFAULT).map { it.id }
        assertEquals(setOf("new", "taken", "shortlisted"), ids.toSet())
        assertEquals("shortlisted", ids.first())
    }
}
