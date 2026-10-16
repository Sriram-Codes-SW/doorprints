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

import app.doorprints.shared.trace.TracePoint
import app.doorprints.shared.trace.TraceWalk
import app.doorprints.shared.trace.WalkSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The Map's redraw throttle and cache (docs/11 5.27.3 *Cost and limits*, TC-U-147): pure, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class TraceRedrawTest {
    private fun walk(id: Long, n: Int, source: WalkSource = WalkSource.TRACE) =
        TraceWalk(List(n) { TracePoint(12.9 + it * 0.0002, 77.5, id + it * 15_000L, id) }, source)

    @Test
    fun theFirstValueComesAtOnceAndTheNextOnesAtMostEveryFiveSeconds() = runTest {
        val seen = mutableListOf<Pair<Long, Int>>()
        val source = flow { for (i in 1..10) { emit(i); delay(1_000) } } // a change every second
        launch { source.throttleRedraw().collect { seen += currentTime to it } }
        testScheduler.advanceUntilIdle()
        assertEquals(0L, seen.first().first)
        assertEquals(1, seen.first().second)
        for (i in 1 until seen.size) assertEquals(true, seen[i].first - seen[i - 1].first >= TRACE_REDRAW_MS, "two redraws at least 5 s apart")
        assertEquals(10, seen.last().second, "the newest change is never lost")
        assertEquals(true, seen.size <= 3, "ten changes in ten seconds draw at most three times")
    }

    @Test
    fun twoChangesInsideOneWindowDrawOnceWithTheNewest() = runTest {
        val seen = mutableListOf<Int>()
        val source = flow { emit(1); emit(2); emit(3) }
        launch { source.throttleRedraw().collect { seen += it } }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(1, 3), seen)
    }

    @Test
    fun anUnchangedSetIsNotDetectedAgainButAKeptPointOrANewWalkIs() {
        var built = 0
        val cache = TraceDrawingCache { built++; TraceDrawing.of(it) }
        val a = listOf(walk(1_000, 5), walk(900_000_000, 4, WalkSource.SAVED))
        val first = cache.of(a)
        assertSame(first, cache.of(listOf(walk(1_000, 5), walk(900_000_000, 4, WalkSource.SAVED))), "same ids and counts: the same drawing")
        assertEquals(1, built)
        cache.of(listOf(walk(1_000, 6), walk(900_000_000, 4, WalkSource.SAVED))) // a kept point
        assertEquals(2, built)
        cache.of(listOf(walk(1_000, 6)))                                         // a saved walk gone
        assertEquals(3, built)
        cache.of(listOf(walk(1_000, 6), walk(5_000_000, 3)))                     // a new walk
        assertEquals(4, built)
        cache.of(listOf(walk(1_000, 6), walk(5_000_000, 3, WalkSource.SAVED)))   // same id, now saved
        assertEquals(5, built)
    }
}
