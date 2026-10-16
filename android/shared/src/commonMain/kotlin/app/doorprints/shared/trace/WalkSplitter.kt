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

/**
 * Step 1 of docs/11 5.27.3: clean a flat trace and split it into walks. Pure: no clock, no I/O.
 */
object WalkSplitter {
    /**
     * The walks of [points] as lists of indexes into [points]: points that are not finite or outside the valid
     * latitude and longitude are dropped; the rest are sorted by `atMs`, **stable** (the input order breaks a tie);
     * a point whose `atMs` equals the one before it is dropped (the first stays); a new walk starts at a gap of
     * [TraceConstants.WALK_GAP_MS] or more (exactly 1 800 000 splits, 1 799 999 does not) or when both points carry a
     * non-zero walk id and the ids differ (**0 is no id**); walks of fewer than two points are dropped.
     */
    fun splitIndexes(points: List<TracePoint>): List<List<Int>> {
        val order = points.indices.filter { TraceGeo.isValid(points[it]) }.sortedBy { points[it].atMs } // stable
        val walks = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var previous: TracePoint? = null
        for (i in order) {
            val p = points[i]
            val before = previous
            if (before != null) {
                if (p.atMs == before.atMs) continue
                val split = p.atMs - before.atMs >= TraceConstants.WALK_GAP_MS ||
                    (p.walkId != 0L && before.walkId != 0L && p.walkId != before.walkId)
                if (split) {
                    if (current.size >= 2) walks.add(current)
                    current = ArrayList()
                }
            }
            current.add(i)
            previous = p
        }
        if (current.size >= 2) walks.add(current)
        return walks
    }

    /** [splitIndexes] as lists of points. */
    fun splitWalks(points: List<TracePoint>): List<List<TracePoint>> =
        splitIndexes(points).map { walk -> walk.map { points[it] } }
}

/** `splitWalks(points)` of docs/11 5.27.3 step 1. */
fun splitWalks(points: List<TracePoint>): List<List<TracePoint>> = WalkSplitter.splitWalks(points)
