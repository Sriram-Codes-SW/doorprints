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

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The *Here* fix of the place check (docs/11 5.27.13, TC-U-154 gate): first of 50 m or better, else the best at 15 s. */
class BestFixTest {
    private fun fix(accuracy: Double, lat: Double = 12.0) = PlaceFix(lat, 77.0, accuracy)

    private fun timeline(vararg steps: Pair<Long, PlaceFix>) = flow {
        var now = 0L
        for ((at, f) in steps) { delay(at - now); now = at; emit(f) }
        delay(1_000_000) // nothing more comes
    }

    @Test fun theFirstFixOfFiftyMetresOrBetterIsUsedAtOnce() = runTest {
        val r = bestOf(timeline(1_000L to fix(30.0, 1.0), 2_000L to fix(5.0, 2.0)), 15_000, 50.0)
        assertEquals(1.0, r!!.lat)
        assertEquals(1_000, currentTime)
    }

    @Test fun exactlyFiftyPasses() = runTest {
        assertEquals(50.0, bestOf(timeline(100L to fix(50.0)), 15_000, 50.0)!!.accuracyM)
        assertEquals(100, currentTime)
    }

    @Test fun aWorseFirstFixIsIgnoredWhileABetterOneComes() = runTest {
        val r = bestOf(timeline(1_000L to fix(80.0, 1.0), 3_000L to fix(40.0, 2.0)), 15_000, 50.0)
        assertEquals(2.0, r!!.lat)
        assertEquals(3_000, currentTime)
    }

    @Test fun atFifteenSecondsTheBestSoFarDecidesEvenWhenWorseThanTheGate() = runTest {
        val r = bestOf(timeline(1_000L to fix(90.0, 1.0), 5_000L to fix(70.0, 2.0), 9_000L to fix(120.0, 3.0)), 15_000, 50.0)
        assertEquals(2.0, r!!.lat)
        assertEquals(70.0, r.accuracyM)
        assertEquals(15_000, currentTime)
    }

    @Test fun noFixInTimeIsNull() = runTest {
        assertNull(bestOf(timeline(), 15_000, 50.0))
        assertEquals(15_000, currentTime)
    }

    @Test fun aReadingWithNoUsableAccuracyIsNoReading() = runTest {
        assertNull(bestOf(timeline(100L to fix(-1.0), 200L to fix(Double.NaN)), 15_000, 50.0))
    }
}
