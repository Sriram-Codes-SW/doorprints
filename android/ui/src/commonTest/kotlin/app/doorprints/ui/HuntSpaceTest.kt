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

import app.doorprints.shared.model.KindSetting
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // docs/03 §18.18: Hunt mode needs the house on; a Hunt alert's new entry is never made in a space that is off.
    private val both = KindSetting(listOf("house", "school"), experimental = "1", enabledText = "[\"house\",\"school\"]")

    @Test
    fun huntModeStartsOnlyWhileTheHouseIsOn() {
        assertTrue(HuntSpace.mayStart(KindSetting())) // the shipped app: the house alone
        assertTrue(HuntSpace.mayStart(both))
        assertTrue(HuntSpace.mayStart(both.withDefault("school"))) // the school the default, the house on
        assertFalse(HuntSpace.mayStart(both.withDefault("school").withKindEnabled("house", false)))
    }

    @Test
    fun aHuntAlertMakesAHouseWhileTheHouseIsOnAndTheDefaultKindsEntryWhenItIsOff() {
        assertEquals("house", HuntSpace.alertKind(KindSetting()))
        assertEquals("house", HuntSpace.alertKind(both))
        assertEquals("house", HuntSpace.alertKind(both.withDefault("school")))
        assertEquals("school", HuntSpace.alertKind(both.withDefault("school").withKindEnabled("house", false)))
    }
}
