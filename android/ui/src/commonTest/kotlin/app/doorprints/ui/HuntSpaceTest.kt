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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S4b-BL-304: only the move out of the house space, with Hunt mode running, stops the walk. */
class HuntSpaceTest {
    @Test
    fun leavingTheHouseSpaceWhileTheWalkRunsStopsIt() = assertTrue(HuntSpace.stopsOnLeaving(wasHere = true, isHere = false, active = true))

    @Test
    fun staysInTheHouseSpaceTheWalkGoesOn() = assertFalse(HuntSpace.stopsOnLeaving(wasHere = true, isHere = true, active = true))

    @Test
    fun noWalkNothingToStop() = assertFalse(HuntSpace.stopsOnLeaving(wasHere = true, isHere = false, active = false))

    @Test
    fun aWalkStartedWhileAnotherSpaceIsOnScreenIsNotStoppedByBeingThere() {
        assertFalse(HuntSpace.stopsOnLeaving(wasHere = false, isHere = false, active = true))
    }

    @Test
    fun comingBackToTheHouseSpaceStopsNothing() = assertFalse(HuntSpace.stopsOnLeaving(wasHere = false, isHere = true, active = true))
}
