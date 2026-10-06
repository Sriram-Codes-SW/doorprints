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
import app.doorprints.shared.model.LocationSource
import app.doorprints.shared.trace.TracePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The *Save this walk?* sheet's logic (docs/11 5.27.6; TC-U-149 UI parts): the summary and the house picker's rows. */
class WalkEndLogicTest {
    private val m = 0.000008993216 // one metre of latitude, in degrees

    private fun walk(northM: List<Double>, startMs: Long = 1_000_000L) =
        northM.mapIndexed { i, n -> TracePoint(12.97 + n * m, 77.6, startMs + i * 60_000L, startMs) }

    private fun house(id: String, northM: Double, eastM: Double = 0.0, label: String = id, source: String? = null, notes: String? = null) =
        HouseEntity(
            id = id, label = label, lat = 12.97 + northM * m, lon = 77.6 + eastM * m / 0.97, locationSource = source, notes = notes,
            createdAt = 1, updatedAt = 1,
        )

    @Test fun theSummaryHasTheDistanceInWholeMetresAndTheMinutes() {
        val s = WalkSummary(7, walk(listOf(0.0, 100.0, 200.0, 300.0)))
        assertEquals(300, s.distanceM)
        assertEquals(3, s.minutes)
    }

    @Test fun theNearestToWhereTheWalkStoppedIsPreselectedWithinTheAlertRadius() {
        val points = walk(listOf(0.0, 100.0, 200.0, 300.0))
        val houses = listOf(house("a", 310.0), house("b", 120.0), house("far", 900.0))
        val p = WalkPicker.of(points, houses, alertRadiusM = 30)
        assertEquals("a", p.nearest!!.house.id)
        assertEquals(10, p.nearest!!.distanceM)
        // b is within 150 m of the walk (it passes it), by its smallest distance; a is not repeated.
        assertEquals(listOf("b"), p.near.map { it.house.id })
        assertEquals(20, p.near.single().distanceM)
    }

    @Test fun noHouseWithinTheRadiusOfTheStopMeansNothingIsPreselected() {
        val p = WalkPicker.of(walk(listOf(0.0, 100.0, 200.0, 300.0)), listOf(house("a", 340.0)), alertRadiusM = 30)
        assertNull(p.nearest)
        assertEquals(listOf("a"), p.near.map { it.house.id }, "40 m from the stop is still within 150 m of the walk")
    }

    @Test fun anApproximateHouseIsNeverTheNearestToTheStop() {
        val p = WalkPicker.of(walk(listOf(0.0, 100.0, 200.0, 300.0)), listOf(house("area", 305.0, source = LocationSource.APPROX)), alertRadiusM = 30)
        assertNull(p.nearest)
        assertEquals(listOf("area"), p.near.map { it.house.id }, "it can still be chosen from the near list")
    }

    @Test fun theNearListIsByDistanceAndStopsAt150Metres() {
        val points = walk(listOf(0.0, 100.0, 200.0, 300.0))
        val houses = listOf(house("p", 100.0, 120.0), house("q", 100.0, 40.0), house("r", 100.0, 151.0), house("s", 50.0, 140.0))
        val near = WalkPicker.of(points, houses, alertRadiusM = 30).near
        assertEquals(listOf("q", "p", "s"), near.map { it.house.id })
        assertTrue(near.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })
    }

    @Test fun noPointsGivesAnEmptyPicker() {
        val p = WalkPicker.of(emptyList(), listOf(house("a", 0.0)), 30)
        assertNull(p.nearest)
        assertEquals(emptyList(), p.near)
    }

    @Test fun theSearchBoxUsesTheListsRuleOverAllHousesAndANotesMatchCounts() {
        val houses = listOf(house("a", 0.0, label = "Green View"), house("b", 5.0, label = "Lake Road", notes = "near the GREEN gate"), house("c", 9.0, label = "Blue"))
        assertEquals(listOf("a", "b"), WalkPicker.search("green", houses, "en").map { it.id })
        assertEquals(emptyList(), WalkPicker.search("  ", houses, "en"), "a blank query lists nothing")
        assertNotNull(WalkPicker.search("lake", houses, "en").singleOrNull())
    }

    @Test fun distancesReadInMetresBelowAKilometreAndKilometresAbove() {
        assertEquals("350 m", formatDistanceM(350, "%1\$d m", "%1\$s km"))
        assertEquals("999 m", formatDistanceM(999, "%1\$d m", "%1\$s km"))
        assertEquals("1.0 km", formatDistanceM(1000, "%1\$d m", "%1\$s km"))
        assertEquals("1.3 km", formatDistanceM(1250, "%1\$d m", "%1\$s km"))
        assertEquals("350 मी", formatDistanceM(350, "%1\$d मी", "%1\$s कि.मी."))
    }
}
