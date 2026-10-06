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

import kotlin.math.floor
import kotlin.math.max

/** Growable primitive arrays, so a month of trace is not boxed. */
internal class DoubleList(capacity: Int = 16) {
    var data = DoubleArray(capacity)
    var size = 0
        private set

    fun add(v: Double) {
        if (size == data.size) data = data.copyOf(max(16, size * 2))
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
}

internal class IntList(capacity: Int = 16) {
    var data = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(max(16, size * 2))
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
}

/** The samples of one walk (docs/11 5.27.3 step 3): position, arc length from the first point, and the part. */
internal class Samples {
    val lat = DoubleList()
    val lon = DoubleList()
    val arc = DoubleList()
    val part = IntList()
    val size get() = lat.size

    fun add(la: Double, lo: Double, a: Double, p: Int) {
        lat.add(la); lon.add(lo); arc.add(a); part.add(p)
    }
}

/**
 * Builds the samples one point at a time, so the alert (a point at a time) and the detector (a whole walk) share the
 * definition. A segment of length `L` is cut into `n = max(1, floor(L / 10 + 0.5))` equal parts; the samples are the
 * segment's start and the points at `i / n` for `i` in `1 until n`, and the end is the next segment's start (the last
 * point of the walk is a sample too). A segment of length 0 gives no extra sample. A [resumed] point begins a new
 * part: no segment, no length (the arc does not grow across it), and it is a sample like the walk's first.
 */
internal class SampleBuilder {
    val samples = Samples()
    private var has = false
    private var prevLat = 0.0
    private var prevLon = 0.0
    private var arc = 0.0
    private var part = 0

    /** Samples added by the last [add] start at this index. */
    var lastAddedFrom = 0
        private set

    fun add(lat: Double, lon: Double, resumed: Boolean) {
        lastAddedFrom = samples.size
        if (!has) {
            has = true
            samples.add(lat, lon, 0.0, 0)
        } else if (resumed) {
            part++
            samples.add(lat, lon, arc, part)
        } else {
            val len = TraceGeo.segmentLengthM(prevLat, prevLon, lat, lon)
            val n = max(1, floor(len / TraceConstants.DENSIFY_M + 0.5).toInt())
            for (i in 1 until n) {
                val f = i.toDouble() / n
                samples.add(prevLat + (lat - prevLat) * f, prevLon + (lon - prevLon) * f, arc + f * len, part)
            }
            arc += len
            samples.add(lat, lon, arc, part)
        }
        prevLat = lat
        prevLon = lon
    }
}

/** The segments of many walks, with a grid over them (docs/11 5.27.3 "Cost and limits"); the answer never depends on the grid. */
internal class SegmentIndex(walks: List<List<TracePoint>>, private val useGrid: Boolean = true) {
    private val aLat = DoubleList()
    private val aLon = DoubleList()
    private val bLat = DoubleList()
    private val bLon = DoubleList()
    private val arcA = DoubleList()
    private val segLen = DoubleList()
    private val walkOf = IntList()
    private val grid = HashMap<Long, IntList>()
    private var stamp = IntArray(0)
    private var query = 0

    init {
        for ((w, points) in walks.withIndex()) {
            var arc = 0.0
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                if (b.resumed) continue // no segment into a resumed point; the arc does not grow
                val len = TraceGeo.segmentLengthM(a.lat, a.lon, b.lat, b.lon)
                val id = walkOf.size
                aLat.add(a.lat); aLon.add(a.lon); bLat.add(b.lat); bLon.add(b.lon)
                arcA.add(arc); segLen.add(len); walkOf.add(w)
                if (useGrid) insert(id, a, b)
                arc += len
            }
        }
        stamp = IntArray(walkOf.size)
    }

    private fun cell(v: Double): Int = floor(v / CELL_DEG).toInt()

    private fun key(la: Int, lo: Int): Long = (la.toLong() shl 32) xor (lo.toLong() and 0xffffffffL)

    private fun insert(id: Int, a: TracePoint, b: TracePoint) {
        val la0 = cell(minOf(a.lat, b.lat)); val la1 = cell(maxOf(a.lat, b.lat))
        val lo0 = cell(minOf(a.lon, b.lon)); val lo1 = cell(maxOf(a.lon, b.lon))
        for (la in la0..la1) for (lo in lo0..lo1) grid.getOrPut(key(la, lo)) { IntList(4) }.add(id)
    }

    /**
     * Calls [visit] once for each segment of a walk other than [excludeWalk] whose distance from (lat, lon) is at most
     * [maxM]: `visit(segmentId, walk, distanceM)`. Exact: the grid only chooses which segments to measure.
     */
    fun forEachWithin(lat: Double, lon: Double, maxM: Double, excludeWalk: Int, visit: (Int, Int, Double) -> Unit) {
        forEachCandidate(lat, lon, maxM) { id ->
            val w = walkOf[id]
            if (w != excludeWalk) {
                val d = TraceGeo.distanceToSegmentM(lat, lon, aLat[id], aLon[id], bLat[id], bLon[id])
                if (d <= maxM) visit(id, w, d)
            }
        }
    }

    /** True when some segment of a walk other than [excludeWalk] is within [maxM] of (lat, lon). */
    fun anyWithin(lat: Double, lon: Double, maxM: Double, excludeWalk: Int): Boolean {
        var found = false
        forEachCandidate(lat, lon, maxM) { id ->
            if (!found && walkOf[id] != excludeWalk &&
                TraceGeo.distanceToSegmentM(lat, lon, aLat[id], aLon[id], bLat[id], bLon[id]) <= maxM
            ) found = true
        }
        return found
    }

    /** The arc length of the walk at the point of segment [id] nearest to (lat, lon). */
    fun arcAtNearest(id: Int, lat: Double, lon: Double): Double =
        arcA[id] + TraceGeo.fractionOnSegment(lat, lon, aLat[id], aLon[id], bLat[id], bLon[id]) * segLen[id]

    private fun forEachCandidate(lat: Double, lon: Double, maxM: Double, visit: (Int) -> Unit) {
        val c = kotlin.math.cos(TraceGeo.rad(lat))
        val dLat = maxM * 1.0001 / TraceGeo.K + 1e-12
        val dLon = if (c > 1e-6) maxM * 1.0001 / (TraceGeo.K * c) + 1e-12 else Double.POSITIVE_INFINITY
        if (!useGrid || dLon.isInfinite()) {
            for (id in 0 until walkOf.size) visit(id)
            return
        }
        val la0 = cell(lat - dLat); val la1 = cell(lat + dLat)
        val lo0 = cell(lon - dLon); val lo1 = cell(lon + dLon)
        if ((la1 - la0 + 1).toLong() * (lo1 - lo0 + 1) > MAX_CELLS) {
            for (id in 0 until walkOf.size) visit(id)
            return
        }
        query++
        for (la in la0..la1) for (lo in lo0..lo1) {
            val ids = grid[key(la, lo)] ?: continue
            for (k in 0 until ids.size) {
                val id = ids[k]
                if (stamp[id] == query) continue
                stamp[id] = query
                visit(id)
            }
        }
    }

    companion object {
        const val CELL_DEG = 0.001
        const val MAX_CELLS = 400L
    }
}
