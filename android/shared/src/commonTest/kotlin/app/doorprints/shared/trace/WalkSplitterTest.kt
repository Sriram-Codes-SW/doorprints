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

class WalkSplitterTest {
    @Test
    fun invalidPointsAreDroppedAndALonePointIsNoWalk() {
        val points = listOf(
            pt(0.0, 0.0, 0), TracePoint(91.0, 0.0, 10_000), TracePoint(Double.NaN, 0.0, 20_000),
            TracePoint(0.0, 181.0, 25_000), pt(10.0, 0.0, 30_000),
            pt(0.0, 0.0, 10 * 3_600_000L),
        )
        assertEquals(listOf(listOf(0, 4)), WalkSplitter.splitIndexes(points))
    }

    @Test
    fun theLatitudeAndLongitudeLimitsThemselvesAreValid() {
        val points = listOf(TracePoint(90.0, 180.0, 0), TracePoint(-90.0, -180.0, 1000))
        assertEquals(listOf(listOf(0, 1)), WalkSplitter.splitIndexes(points))
    }

    @Test
    fun aTieInTimeKeepsTheInputOrderAndADuplicateTimeIsDropped() {
        val a = pt(0.0, 0.0, 5000); val b = pt(1.0, 0.0, 1000); val c = pt(2.0, 0.0, 5000); val d = pt(3.0, 0.0, 9000)
        // sorted stably: b(1), a(0) , c(2) (same time as a: dropped), d
        assertEquals(listOf(listOf(1, 0, 3)), WalkSplitter.splitIndexes(listOf(a, b, c, d)))
    }

    @Test
    fun theGapRuleIsInclusiveAndAZeroWalkIdIsNoId() {
        val gap = TraceConstants.WALK_GAP_MS
        assertEquals(2, WalkSplitter.splitIndexes(listOf(pt(0.0, 0.0, 0), pt(1.0, 0.0, 1), pt(2.0, 0.0, 1 + gap), pt(3.0, 0.0, 2 + gap))).size)
        assertEquals(1, WalkSplitter.splitIndexes(listOf(pt(0.0, 0.0, 0), pt(1.0, 0.0, 1), pt(2.0, 0.0, gap), pt(3.0, 0.0, gap + 1))).size)
        val ids = listOf(pt(0.0, 0.0, 0, 5), pt(1.0, 0.0, 60_000, 0), pt(2.0, 0.0, 120_000, 6))
        assertEquals(listOf(listOf(0, 1, 2)), WalkSplitter.splitIndexes(ids))
        val differing = listOf(pt(0.0, 0.0, 0, 5), pt(1.0, 0.0, 60_000, 5), pt(2.0, 0.0, 120_000, 6), pt(3.0, 0.0, 180_000, 6))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), WalkSplitter.splitIndexes(differing))
    }
}
