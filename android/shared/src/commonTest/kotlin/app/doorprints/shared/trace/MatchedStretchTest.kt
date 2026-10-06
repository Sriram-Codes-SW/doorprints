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

/** The matched stretch of the place check (docs/11 5.27.13, CHECK_STRETCH_M = 60): drawing only, TC-U-154. */
class MatchedStretchTest {
    private fun lengthM(line: List<Pair<Double, Double>>): Double =
        (1 until line.size).sumOf { TraceGeo.segmentLengthM(line[it - 1].first, line[it - 1].second, line[it].first, line[it].second) }

    private val street = listOf(pt(0.0, 0.0, 0), pt(100.0, 0.0, 60_000), pt(200.0, 0.0, 120_000), pt(300.0, 0.0, 180_000))

    @Test
    fun sixtyMetresEachSideOfTheNearestPoint() {
        val line = MatchedStretch.of(150.0 * M, 5.0 * M, street)
        assertEquals(120.0, lengthM(line), 0.5)
        assertEquals(90.0 * M, line.first().first, 1e-7)
        assertEquals(210.0 * M, line.last().first, 1e-7)
    }

    @Test
    fun clippedAtTheEndOfTheWalk() {
        val line = MatchedStretch.of(20.0 * M, 5.0 * M, street)
        assertEquals(80.0, lengthM(line), 0.5)
        assertEquals(0.0, line.first().first, 1e-9)
    }

    @Test
    fun neverAcrossAResumedPoint() {
        // 0..100 m, then a pause: the next point (resumed) is 100 m further north, then it goes on to 300 m.
        val walk = listOf(pt(0.0, 0.0, 0), pt(100.0, 0.0, 60_000), pt(200.0, 0.0, 600_000, resumed = true), pt(300.0, 0.0, 660_000))
        val line = MatchedStretch.of(90.0 * M, 3.0 * M, walk)
        assertTrue(line.all { it.first <= 100.0 * M + 1e-9 }, "the stretch stays in the first part")
        assertEquals(70.0, lengthM(line), 0.5) // 30 m to the part's end at 100 m
        val after = MatchedStretch.of(250.0 * M, 3.0 * M, walk)
        assertTrue(after.all { it.first >= 200.0 * M - 1e-9 }, "and the other part keeps to its own side")
    }

    @Test
    fun aWalkWithNoSegmentHasNoStretch() {
        assertEquals(emptyList(), MatchedStretch.of(0.0, 0.0, listOf(pt(0.0, 0.0, 0))))
    }
}
