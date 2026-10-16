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

import org.junit.Assert.assertEquals
import org.junit.Test

/** The list of viewings is the space's (kinds PR 29, docs/03 section 18.23, TC-U-278), the twin of `viewings-page-spaces.spec.ts`. */
class ViewingSpaceTest {
    private val kinds = mapOf("h1" to "house", "s1" to "school")
    private val both = { kind: String -> SpaceView(kind, multi = true, default = "house", available = listOf("house", "school")) }
    private fun viewing(houseId: String) = Viewing("v_00000001", houseId, 1_760_000_000_000)
    private fun listed(view: SpaceView) = listOf("h1", "s1", "gone").filter { Viewings.inSpace(view, viewing(it)) { id -> kinds[id] } }

    @Test fun aSchoolsVisitsAreListedInTheSchoolSpaceAndNotTheHousesViewing() = assertEquals(listOf("s1"), listed(both("school")))

    @Test fun theHousesViewingsAndOneWhoseCandidateIsGoneAreListedInTheHouseSpace() = assertEquals(listOf("h1", "gone"), listed(both("house")))

    @Test fun withOneSpaceEveryViewingIsListedAsItAlwaysWas() = assertEquals(listOf("h1", "s1", "gone"), listed(SpaceView.ONE))

    @Test fun aKindThisBuildDoesNotHaveIsInTheDefaultSpace() {
        val view = both("house")
        assertEquals(true, Viewings.inSpace(view, viewing("x1")) { "clinic" })
        assertEquals(false, Viewings.inSpace(both("school"), viewing("x1")) { "clinic" })
    }
}
