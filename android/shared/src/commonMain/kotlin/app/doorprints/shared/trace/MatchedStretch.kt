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
 * The matched stretch of a *Have I been here?* answer (docs/11 5.27.13): the part of a walk's polyline from
 * [TraceConstants.CHECK_STRETCH_M] before to that far after its nearest point to the place, along the walk, within one
 * part (never across a resumed point; shorter at a walk's end). Drawing only, so it is not in the vector file.
 */
object MatchedStretch {
    /** The polyline as `(lat, lon)` pairs, or empty when [walk] has no segment. */
    fun of(
        placeLat: Double,
        placeLon: Double,
        walk: List<TracePoint>,
        halfM: Double = TraceConstants.CHECK_STRETCH_M,
    ): List<Pair<Double, Double>> {
        // The arc length at each point, counting no segment into a resumed point (the same arcs as RepeatDetector.pieces).
        val arc = DoubleArray(walk.size)
        var bestD = Double.POSITIVE_INFINITY
        var bestSeg = -1
        for (i in 1 until walk.size) {
            val a = walk[i - 1]
            val b = walk[i]
            if (b.resumed) {
                arc[i] = arc[i - 1]
                continue
            }
            arc[i] = arc[i - 1] + TraceGeo.segmentLengthM(a.lat, a.lon, b.lat, b.lon)
            val d = TraceGeo.distanceToSegmentM(placeLat, placeLon, a.lat, a.lon, b.lat, b.lon)
            if (d < bestD) {
                bestD = d; bestSeg = i
            }
        }
        if (bestSeg < 0) return emptyList()
        val a = walk[bestSeg - 1]
        val b = walk[bestSeg]
        val t = TraceGeo.fractionOnSegment(placeLat, placeLon, a.lat, a.lon, b.lat, b.lon)
        val at = arc[bestSeg - 1] + t * (arc[bestSeg] - arc[bestSeg - 1])
        // The part this segment is in: from the last resumed point (or the start) to just before the next one.
        var first = bestSeg - 1
        while (first > 0 && !walk[first].resumed) first--
        var last = bestSeg
        while (last + 1 < walk.size && !walk[last + 1].resumed) last++
        val from = maxOf(arc[first], at - halfM)
        val to = minOf(arc[last], at + halfM)
        return RepeatDetector.pieces(walk, listOf(Stretch(from, to))).firstOrNull().orEmpty()
    }
}
