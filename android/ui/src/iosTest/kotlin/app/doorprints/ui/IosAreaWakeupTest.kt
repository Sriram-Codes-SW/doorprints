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

import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaRegions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The area wake-up's iPhone registration (S4b-BL-96) on a fake Core Location, and its notification's deep link. */
class IosAreaWakeupTest {
    private fun area(i: Int, radius: Int = 500) = Area("a_" + i.toString(16).padStart(8, '0'), "Area $i", 13.0 + i * 0.001, 80.2, radius)

    @Test
    fun theRegionsFollowTheAreasWithTheFewestCalls() {
        val monitor = RecordingRegionMonitor()
        val sync = AreaRegionSync(monitor)
        sync.apply(listOf(area(1), area(2)), wakeupOn = true, alwaysGranted = true)
        assertEquals(2, monitor.calls)
        // One area's radius changes and another is added: one stop and two starts; the unchanged one stays.
        val plan = sync.apply(listOf(area(1), area(2, radius = 900), area(3)), wakeupOn = true, alwaysGranted = true)
        assertEquals(listOf(AreaRegions.identifier(area(2).id)), plan.stop)
        assertEquals(5, monitor.calls)
        assertEquals(900.0, monitor.monitored().single { it.identifier == AreaRegions.identifier(area(2).id) }.radiusM)
        // "Always" gone: every region stops.
        sync.apply(listOf(area(1)), wakeupOn = true, alwaysGranted = false)
        assertEquals(emptyList(), monitor.monitored())
    }

    @Test
    fun whereRegionsCannotBeMonitoredNothingIsRegisteredAndTheRadiusIsCapped() {
        val none = RecordingRegionMonitor(available = false)
        AreaRegionSync(none).apply(listOf(area(1)), wakeupOn = true, alwaysGranted = true)
        assertEquals(emptyList(), none.monitored())
        val capped = RecordingRegionMonitor(maxRadiusM = 400.0)
        AreaRegionSync(capped).apply(listOf(area(1)), wakeupOn = true, alwaysGranted = true)
        assertEquals(400.0, capped.monitored().single().radiusM)
    }

    @Test
    fun aTappedWakeUpOpensTheMapForHuntModeOnlyForAValidId() {
        assertEquals(DeepLink.OfferHunt, notificationDeepLink(mapOf(IosAreaWakeup.KEY_START_HUNT_AREA to "a_00000001")))
        assertNull(notificationDeepLink(mapOf(IosAreaWakeup.KEY_START_HUNT_AREA to "..")))
    }
}
