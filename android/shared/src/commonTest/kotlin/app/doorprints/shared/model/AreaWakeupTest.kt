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
import kotlin.test.assertTrue

/** The area wake-up's rules (docs/11 "Design of slice 4b"), the vectors C1..C7. */
class AreaWakeupTest {
    private val now = 1_790_569_800_000L
    private val min = 60_000L
    private fun area(i: Int, name: String = "Area $i", enabled: Boolean = true) =
        Area("a_" + i.toString(16).padStart(8, '0'), name, 13.0, 80.2, 500, enabled)

    @Test
    fun c1_neverNotifiedNotifies() {
        assertTrue(AreaCooldown.shouldNotify(null, now, huntRunning = false, wakeupOn = true))
    }

    @Test
    fun c2_notifiedFiveHoursFiftyNineMinutesAgoDoesNot() {
        assertFalse(AreaCooldown.shouldNotify(now - (5 * 60 + 59) * min, now, huntRunning = false, wakeupOn = true))
        assertFalse(AreaCooldown.shouldNotify(now - AreaCooldown.COOLDOWN_MS + 1, now, huntRunning = false, wakeupOn = true))
    }

    @Test
    fun c3_notifiedExactlySixHoursAgoNotifies() {
        assertEquals(21_600_000L, AreaCooldown.COOLDOWN_MS)
        assertTrue(AreaCooldown.shouldNotify(now - 21_600_000L, now, huntRunning = false, wakeupOn = true))
    }

    @Test
    fun c4_huntModeRunningDoesNot() {
        assertFalse(AreaCooldown.shouldNotify(null, now, huntRunning = true, wakeupOn = true))
    }

    @Test
    fun c5_theSettingOffDoesNotAndRegistersNothing() {
        assertFalse(AreaCooldown.shouldNotify(null, now, huntRunning = false, wakeupOn = false))
        assertEquals(emptyList(), AreaWakeup.geofencesFor(listOf(area(1)), wakeupOn = false, backgroundGranted = true))
        assertEquals(emptyList(), AreaWakeup.geofencesFor(listOf(area(1)), wakeupOn = true, backgroundGranted = false))
    }

    @Test
    fun c6_aDisabledOrDeletedAreaGetsNoGeofence() {
        val kept = area(1)
        val disabled = area(2, enabled = false)
        // A deleted area is not in the live list; an invalid row (a bad id) is not an area either.
        val broken = area(3).copy(id = "bad id")
        assertEquals(listOf(kept), AreaWakeup.geofencesFor(listOf(kept, disabled, broken), wakeupOn = true, backgroundGranted = true))
    }

    @Test
    fun c7_theRegisteredSetIsTheEnabledAreasAtMostTwentyInAStableOrder() {
        val areas = (0 until 25).map { area(it, name = "Z" + (24 - it).toString().padStart(2, '0'), enabled = it != 3) }
        val set = AreaWakeup.geofencesFor(areas.shuffled(kotlin.random.Random(7)), wakeupOn = true, backgroundGranted = true)
        assertEquals(20, set.size)
        assertTrue(set.all { it.enabled })
        assertEquals(areas.filter { it.enabled }.sortedWith(Area.BY_NAME).take(20), set)
        assertEquals(set, AreaWakeup.geofencesFor(areas, wakeupOn = true, backgroundGranted = true))
        assertEquals(2, AreaWakeup.geofencesFor(areas, wakeupOn = true, backgroundGranted = true, limit = 2).size)
    }

    @Test
    fun aStampFarInTheFutureDoesNotSilenceTheAreaForEver() {
        assertFalse(AreaCooldown.shouldNotify(now + 60 * min, now, huntRunning = false, wakeupOn = true))
        assertTrue(AreaCooldown.shouldNotify(now + AreaCooldown.COOLDOWN_MS + 1, now, huntRunning = false, wakeupOn = true))
    }
}
