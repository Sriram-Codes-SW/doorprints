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

import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The inclusive boundaries that no vector may sit on (the file's hygiene keeps 0.01 m clear of every constant): each is
 * pinned here with an exact value, so a `<=` turned into `<` fails a named test (TC-U-147 mutations).
 */
class TraceBoundaryTest {
    private fun samples(vararg points: Triple<Double, Int, Boolean>): Pair<Samples, BooleanArray> {
        val s = Samples()
        for ((arc, part, _) in points) s.add(0.0, 0.0, arc, part)
        return s to BooleanArray(points.size) { points[it].third }
    }

    @Test
    fun aSampleExactlyAtTheToleranceIsNear() {
        val index = SegmentIndex(listOf(listOf(pt(0.0, 0.0, 0), pt(300.0, 0.0, 1))))
        val qLat = 150.0 * M
        val qLon = 25.0 * M
        val d = TraceGeo.distanceToSegmentM(qLat, qLon, 0.0, 0.0, 300.0 * M, 0.0)
        assertTrue(index.anyWithin(qLat, qLon, d, -1), "distance $d with the tolerance $d is near")
        assertTrue(!index.anyWithin(qLat, qLon, d.nextDown(), -1))
    }

    @Test
    fun aBridgeOfExactlyThirtyMetresIsFilledAndOneMoreIsNot() {
        val (s, near) = samples(Triple(0.0, 0, true), Triple(10.0, 0, false), Triple(30.0, 0, true))
        RepeatDetector.bridge(s, near)
        assertEquals(listOf(true, true, true), near.toList())
        val (s2, near2) = samples(Triple(0.0, 0, true), Triple(10.0, 0, false), Triple(30.0.nextUp(), 0, true))
        RepeatDetector.bridge(s2, near2)
        assertEquals(listOf(true, false, true), near2.toList())
    }

    @Test
    fun aBridgeNeverCrossesAPartBoundaryOrFillsTheEnds() {
        val (s, near) = samples(Triple(0.0, 0, true), Triple(10.0, 1, false), Triple(10.0, 1, true))
        RepeatDetector.bridge(s, near)
        assertEquals(listOf(true, false, true), near.toList())
        val (s2, near2) = samples(Triple(0.0, 0, false), Triple(10.0, 0, true), Triple(20.0, 0, false))
        RepeatDetector.bridge(s2, near2)
        assertEquals(listOf(false, true, false), near2.toList())
    }

    @Test
    fun aRunOfExactlyEightyMetresIsARepeatedStretchAndARunInTwoPartsIsTwoRuns() {
        val (s, near) = samples(Triple(0.0, 0, true), Triple(40.0, 0, true), Triple(80.0, 0, true))
        assertEquals(listOf(0..2), RepeatDetector.repeatedRuns(s, near))
        val (s2, near2) = samples(Triple(0.0, 0, true), Triple(80.0.nextDown(), 0, true))
        assertEquals(emptyList(), RepeatDetector.repeatedRuns(s2, near2))
        val (s3, near3) = samples(Triple(0.0, 0, true), Triple(40.0, 0, true), Triple(40.0, 1, true), Triple(80.0, 1, true))
        assertEquals(emptyList(), RepeatDetector.repeatedRuns(s3, near3), "40 m and 40 m in two parts are no 80 m run")
    }

    @Test
    fun theAlertRingsAtExactly100MetresAndNotAtJustUnder() {
        val other = listOf(TracePoint(0.0, 0.0, -86_400_000), TracePoint(1000.0 * M, 0.0, -86_340_000))
        val exact = latOfExactly(100.0)
        val alert = RepeatAlert(listOf(other))
        assertNull(alert.onPoint(TracePoint(0.0, 0.0, 0)))
        assertEquals(100.0, alert.onPoint(TracePoint(exact, 0.0, 1000)))
        val shorter = RepeatAlert(listOf(other))
        shorter.onPoint(TracePoint(0.0, 0.0, 0))
        assertNull(shorter.onPoint(TracePoint(latOfExactly(100.0).nextDown().nextDown().nextDown().nextDown(), 0.0, 1000)))
    }

    @Test
    fun aSecondAlertIsAllowedAtExactlyTenMinutesAndNotOneMillisecondEarlier() {
        val other = listOf(TracePoint(0.0, 0.0, -86_400_000), TracePoint(3000.0 * M, 0.0, -86_340_000))
        fun run(secondAt: Long): Int {
            val alert = RepeatAlert(listOf(other))
            var rings = 0
            val live = listOf(
                pt(0.0, 0.0, 0), pt(60.0, 0.0, 10_000), pt(120.0, 0.0, 20_000), // rings at 20 000
                pt(120.0, 300.0, 30_000), pt(120.0, 600.0, 40_000), // away, unblocks
                pt(1000.0, 0.0, 50_000), // back on the street far along (a leap: run starts here)
                pt(1060.0, 0.0, 60_000), pt(1120.0, 0.0, secondAt),
            )
            for (p in live) if (alert.onPoint(p) != null) rings++
            return rings
        }
        assertEquals(2, run(20_000 + TraceConstants.ALERT_COOLDOWN_MS))
        assertEquals(1, run(20_000 + TraceConstants.ALERT_COOLDOWN_MS - 1))
    }

    @Test
    fun aBadFixDoesNotUnblockButAFarOneDoes() {
        val other = listOf(TracePoint(0.0, 0.0, -86_400_000), TracePoint(3000.0 * M, 0.0, -86_340_000))
        val alert = RepeatAlert(listOf(other))
        val live = listOf(pt(0.0, 0.0, 0), pt(60.0, 0.0, 10_000), pt(120.0, 0.0, 20_000))
        assertEquals(listOf(false, false, true), live.map { alert.onPoint(it) != null })
        // far away (more than the bridge from the run): unblocked, so coming back after the cooldown rings again
        alert.onPoint(pt(120.0, 300.0, 30_000))
        assertEquals(null, alert.onPoint(pt(130.0, 0.0, 700_000)))
        assertTrue(alert.onPoint(pt(250.0, 0.0, 710_000)) != null)
    }
}
