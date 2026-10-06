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

package app.doorprints.shared.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The inline cases of TC-U-153 (the 21 vectors are in `TraceVectorsTest`), the inclusive 25 m and the plain loop. */
class PlaceCheckTest {
    private val street = TraceWalk(listOf(pt(0.0, 0.0, 0), pt(100.0, 0.0, 60_000), pt(200.0, 0.0, 120_000), pt(300.0, 0.0, 180_000)))

    private fun check(north: Double, east: Double, walks: List<TraceWalk> = listOf(street), accuracy: Double? = null) =
        PlaceCheck.check(north * M, east * M, walks, accuracy)

    @Test
    fun aPlaceOnTheLineIsWalkedAtTheInterpolatedTime() {
        val r = check(150.0, 0.0)
        assertEquals(PlaceCheckStatus.WALKED, r.status)
        assertEquals(90_000L, r.rows.single().atMs)
        assertEquals(0.0, r.nearestM!!, 0.01)
    }

    @Test
    fun theBandsAreWalkedUpTo25CloseUpTo50AndNoneBeyond() {
        assertEquals(PlaceCheckStatus.WALKED, check(150.0, 24.9).status)
        assertEquals(PlaceCheckStatus.CLOSE, check(150.0, 25.1).status)
        assertEquals(PlaceCheckStatus.CLOSE, check(150.0, 49.9).status)
        assertEquals(PlaceCheckStatus.NONE, check(150.0, 50.1).status)
        assertEquals(PlaceBand.CLOSE, check(150.0, 30.0).rows.single().band)
    }

    @Test
    fun exactlyTheToleranceCountsAsWalkedBecauseTheComparisonIsInclusive() {
        val lat = 150.0 * M
        val lon = 30.0 * M
        val a = street.points[1]; val b = street.points[2]
        val d = TraceGeo.distanceToSegmentM(lat, lon, a.lat, a.lon, b.lat, b.lon)
        val atTolerance = PlaceCheck.check(lat, lon, listOf(street), null, boxRejection = true, toleranceM = d)
        assertEquals(PlaceBand.WALKED, atTolerance.rows.single().band, "d = $d must count as walked when the tolerance is d")
        val justUnder = PlaceCheck.check(lat, lon, listOf(street), null, boxRejection = true, toleranceM = d - 1e-9)
        assertEquals(PlaceBand.CLOSE, justUnder.rows.single().band)
        // and the near band itself: a walk exactly NEAR_BAND_M away is still a row
        val e = TraceGeo.distanceToSegmentM(lat, 45.0 * M, a.lat, a.lon, b.lat, b.lon)
        assertEquals(1, PlaceCheck.check(lat, 45.0 * M, listOf(street), null, true, 25.0).rows.size)
        assertTrue(e < 50.0)
    }

    @Test
    fun emptyImpreciseAndInvalidAnswerBeforeAnyComparison() {
        assertEquals(PlaceCheckStatus.EMPTY, check(150.0, 0.0, emptyList()).status)
        assertEquals(PlaceCheckStatus.EMPTY, check(150.0, 0.0, listOf(TraceWalk(listOf(pt(150.0, 0.0, 0))))).status)
        assertEquals(PlaceCheckStatus.IMPRECISE, check(150.0, 0.0, accuracy = 50.01).status)
        assertEquals(PlaceCheckStatus.IMPRECISE, check(150.0, 0.0, accuracy = Double.NaN).status)
        assertEquals(PlaceCheckStatus.IMPRECISE, check(150.0, 0.0, accuracy = -1.0).status)
        assertEquals(PlaceCheckStatus.WALKED, check(150.0, 0.0, accuracy = 50.0).status)
        assertEquals(PlaceCheckStatus.INVALID_PLACE, PlaceCheck.check(91.0, 0.0, listOf(street)).status)
        assertEquals(PlaceCheckStatus.INVALID_PLACE, PlaceCheck.check(0.0, Double.NaN, listOf(street)).status)
    }

    @Test
    fun aLooseButAcceptedFixIsFuzzyAndNeverWidensTheTolerance() {
        assertTrue(check(150.0, 0.0, accuracy = 25.1).fuzzy)
        assertTrue(!check(150.0, 0.0, accuracy = 25.0).fuzzy)
        // 30 m away with an accuracy of 50 m is still only close: the accuracy is not added to the tolerance
        val r = check(150.0, 30.0, accuracy = 50.0)
        assertEquals(PlaceCheckStatus.CLOSE, r.status)
        assertTrue(r.fuzzy)
    }

    @Test
    fun aStayHasNoLengthSoTheNearestPointIsItsStart() {
        val stay = TraceWalk(listOf(pt(10.0, 0.0, 1000), pt(10.0, 0.0, 9000)))
        val r = check(10.0, 10.0, listOf(stay))
        assertEquals(PlaceCheckStatus.WALKED, r.status)
        assertEquals(10.0, r.rows.single().distanceM, 0.01)
        assertEquals(1000L, r.rows.single().atMs)
    }

    @Test
    fun theBoxRejectionNeverChangesTheRows() {
        val rnd = Lcg(77)
        val walks = List(60) { w ->
            var lat = 13.0 + (rnd.next() - 0.5) * 0.004
            var lon = 77.6 + (rnd.next() - 0.5) * 0.004
            val points = List(10 + (rnd.next() * 30).toInt()) { i ->
                lat += (rnd.next() - 0.5) * 60 * M
                lon += (rnd.next() - 0.5) * 60 * M
                TracePoint(lat, lon, w * 100_000L + i * 1000L, resumed = rnd.next() < 0.05 && i > 0)
            }
            TraceWalk(points, if (w % 5 == 0) WalkSource.SAVED else WalkSource.TRACE)
        }
        var nonEmpty = 0
        repeat(200) {
            val la = 13.0 + (rnd.next() - 0.5) * 0.005
            val lo = 77.6 + (rnd.next() - 0.5) * 0.005
            val withBox = PlaceCheck.check(la, lo, walks, null, boxRejection = true)
            val plain = PlaceCheck.check(la, lo, walks, null, boxRejection = false)
            assertEquals(plain, withBox)
            if (withBox.rows.isNotEmpty()) nonEmpty++
        }
        assertTrue(nonEmpty > 20, "the random city must answer rows to prove anything ($nonEmpty)")
    }

    @Test
    fun aWalkExactlyOnTheNearBandIsStillARowAndATieInTimeListsTheLaterWalkFirst() {
        val fifty = latOfExactly(50.0)
        val start = TraceWalk(listOf(TracePoint(0.0, 0.0, 0), TracePoint(300.0 * M, 0.0, 1000)))
        val r = PlaceCheck.check(-fifty, 0.0, listOf(start))
        assertEquals(PlaceCheckStatus.CLOSE, r.status, "exactly 50.0 m is inside the band: ${r.rows}")
        val same = TraceWalk(listOf(pt(0.0, 3.0, 0), pt(300.0, 3.0, 60_000)))
        val twice = check(0.0, 0.0, listOf(same, same))
        assertEquals(listOf(1, 0), twice.rows.map { it.walkIndex }, "a tie in time: the later input index first")
    }
}
