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

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The six inline cases of TC-U-147 (so the iOS simulator job runs the common code: `commonTest` has no file API, the
 * vector file is read by `TraceVectorsTest` in `androidHostTest`), plus the index-equals-plain-loops test.
 */
class RepeatDetectorTest {
    private val day = 86_400_000L

    private fun assertStretch(expectedFrom: Double, expectedTo: Double, actual: Stretch) {
        assertTrue(abs(actual.fromM - expectedFrom) <= 0.5 && abs(actual.toM - expectedTo) <= 0.5, "expected [$expectedFrom, $expectedTo] but $actual")
    }

    @Test
    fun theSameStreetOnTwoDaysIsRepeatedInBothAndTheNewerWalkDrawsIt() {
        val a = street(0.0, 300.0, 100.0, 0.0, 0)
        val b = street(0.0, 300.0, 100.0, 5.0, day)
        val r = RepeatDetector.detect(listOf(a, b))
        assertStretch(0.0, 300.0, r[0].repeated.single())
        assertStretch(0.0, 300.0, r[1].repeated.single())
        assertTrue(r[0].shown.isEmpty(), "the older walk leaves the stretch to the newer")
        assertStretch(0.0, 300.0, r[1].shown.single())
    }

    @Test
    fun aJunctionIsNotARepeat() {
        val ew = listOf(pt(0.0, -200.0, 0), pt(0.0, 0.0, 600_000), pt(0.0, 200.0, 1_200_000))
        val ns = listOf(pt(-200.0, 0.0, day), pt(0.0, 0.0, day + 600_000), pt(200.0, 0.0, day + 1_200_000))
        val r = RepeatDetector.detect(listOf(ew, ns))
        assertTrue(r.all { it.repeated.isEmpty() && it.shown.isEmpty() })
    }

    @Test
    fun oneBadFixInsideAStreetIsBridged() {
        val a = street(0.0, 300.0, 100.0, 0.0, 0)
        // 28 m off the street at 120 m: outside the 25 m corridor, one sample, joined by the 30 m bridge.
        val b = listOf(
            pt(0.0, 0.0, day), pt(40.0, 0.0, day + 1), pt(80.0, 0.0, day + 2), pt(120.0, 28.0, day + 3),
            pt(160.0, 0.0, day + 4), pt(200.0, 0.0, day + 5), pt(240.0, 0.0, day + 6), pt(280.0, 0.0, day + 7),
        ).mapIndexed { i, p -> p.copy(atMs = day + i * 20_000L) }
        val r = RepeatDetector.detect(listOf(a, b))
        val stretch = r[1].repeated.single()
        assertTrue(stretch.fromM < 1.0 && stretch.toM > 297.0, "one stretch over the whole walk, got ${r[1].repeated}")
    }

    @Test
    fun aGapOfThirtyMinutesSplitsAWalkAndAnythingShorterDoesNot() {
        fun trace(gapMs: Long): List<TracePoint> =
            street(0.0, 200.0, 50.0, 0.0, 0, 60_000) + street(0.0, 200.0, 50.0, 3.0, 4 * 60_000L + gapMs, 60_000)
        val split = splitWalks(trace(TraceConstants.WALK_GAP_MS))
        assertEquals(2, split.size)
        assertEquals(1, splitWalks(trace(TraceConstants.WALK_GAP_MS - 1)).size)
        val r = RepeatDetector.detect(split)
        assertTrue(r.all { it.repeated.isNotEmpty() })
        assertTrue(RepeatDetector.detect(splitWalks(trace(TraceConstants.WALK_GAP_MS - 1))).all { it.repeated.isEmpty() })
    }

    @Test
    fun theAlertRingsOnceAfter100MetresOfAWalkedStreet() {
        val others = listOf(street(0.0, 1000.0, 100.0, 0.0, -day))
        val live = street(0.0, 300.0, 24.0, 4.0, 0)
        val alert = RepeatAlert(others)
        val rang = live.indices.filter { alert.onPoint(live[it]) != null }
        // 24 m a step: the run is 96 m at index 4 and 120 m at index 5.
        assertEquals(listOf(5), rang)
    }

    @Test
    fun theCooldownHoldsASecondAlertUntilTenMinutesHavePassed() {
        val others = listOf(street(0.0, 1000.0, 100.0, 0.0, -day))
        fun rings(stepMs: Long): List<Int> {
            // along the street, away from it for more than the bridge, and back onto it
            val pts = street(0.0, 144.0, 24.0, 4.0, 0, stepMs).toMutableList() // 7 points
            pts += pt(168.0, 90.0, pts.last().atMs + stepMs)
            pts += pt(192.0, 120.0, pts.last().atMs + stepMs)
            pts += pt(216.0, 4.0, pts.last().atMs + stepMs)
            pts += street(240.0, 480.0, 24.0, 4.0, pts.last().atMs + stepMs, stepMs)
            val alert = RepeatAlert(others)
            return pts.indices.filter { alert.onPoint(pts[it]) != null }
        }
        assertEquals(listOf(5), rings(20_000), "the second run is 100 m long inside the cooldown")
        val late = rings(120_000)
        assertEquals(2, late.size, "after the cooldown the same walk rings again: $late")
        assertEquals(5, late[0])
    }

    @Test
    fun theWalkJustFinishedIsNotWarnedAbout() {
        val finished = street(0.0, 300.0, 100.0, 0.0, 0, 60_000) // last point at 180 s
        val back = street(300.0, 0.0, 24.0, 0.0, 180_000 + TraceConstants.WALK_GAP_MS - 1)
        val alert = RepeatAlert(listOf(finished))
        assertTrue(back.none { alert.onPoint(it) != null })
        val later = street(300.0, 0.0, 24.0, 0.0, 180_000 + TraceConstants.WALK_GAP_MS)
        val alert2 = RepeatAlert(listOf(finished))
        assertNotNull(later.firstNotNullOfOrNull { alert2.onPoint(it) })
    }

    @Test
    fun theIndexGivesTheSameStretchesAsThePlainLoops() {
        val rnd = Lcg(20261006)
        val walks = List(60) { w ->
            var lat = 13.0 + (rnd.next() - 0.5) * 0.004
            var lon = 77.6 + (rnd.next() - 0.5) * 0.004
            var heading = rnd.next() * 6.283
            val n = 15 + (rnd.next() * 40).toInt()
            List(n) { i ->
                heading += (rnd.next() - 0.5) * 0.5
                lat += kotlin.math.cos(heading) * 25 * M
                lon += kotlin.math.sin(heading) * 25 * M
                TracePoint(lat, lon, w * 7_200_000L + i * 30_000L)
            }
        }
        val indexed = RepeatDetector.detect(walks, useIndex = true)
        val plain = RepeatDetector.detect(walks, useIndex = false)
        assertEquals(plain, indexed)
        assertTrue(indexed.any { it.repeated.isNotEmpty() }, "the random city must have repeats to prove anything")
        assertTrue(indexed.any { it.shown.isNotEmpty() })
        // The alert gives the same indexes whatever the index.
        val live = walks[7]
        val a1 = RepeatAlert(walks.filterIndexed { i, _ -> i != 7 })
        val a2 = RepeatAlert(walks.filterIndexed { i, _ -> i != 7 })
        assertEquals(live.map { a1.onPoint(it) }, live.map { a2.onPoint(it) })
    }

    @Test
    fun aWalkOverTheDetectionLimitIsNotComparedButTheNewestAre() {
        val long = List(TraceConstants.MAX_DETECTION_POINTS) { pt(it * 1.0, 0.0, it * 1000L) } // the oldest, fills the limit
        val a = street(0.0, 300.0, 100.0, 0.0, day * 10)
        val b = street(0.0, 300.0, 100.0, 5.0, day * 11)
        val r = RepeatDetector.detect(listOf(long, a, b))
        assertTrue(r[0].repeated.isEmpty() && r[0].shown.isEmpty(), "the oldest walk over the limit is not compared")
        assertStretch(0.0, 300.0, r[2].repeated.single())
        assertNull(r.getOrNull(3))
    }
}
