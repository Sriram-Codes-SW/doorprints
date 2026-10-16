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
 * Repeat detection (docs/11 5.27.3 steps 3 to 7): where a walk followed the path of at least one other, different
 * walk. Pure functions: no clock, no I/O, deterministic; the website's `trace-repeats.ts` is its twin and both are held
 * to `docs/schemas/trace-repeat-vectors.json`.
 */
object RepeatDetector {
    /**
     * One [WalkRepeats] per walk of [walks], in input order. A walk of fewer than two points, and every walk past
     * [TraceConstants.MAX_DETECTION_POINTS] (newest first, whole walks only), is not compared: it has none.
     */
    fun detect(walks: List<List<TracePoint>>): List<WalkRepeats> = detect(walks, useIndex = true)

    /**
      * The detection with a switch for the segment index; the index only speeds the search, so both settings give the
      * same answer.
     */
    internal fun detect(walks: List<List<TracePoint>>, useIndex: Boolean): List<WalkRepeats> {
        val empty = WalkRepeats(emptyList(), emptyList())
        val result = MutableList(walks.size) { empty }
        // The walks that are read: newest first by walk id (the first point's time), whole walks, up to the limit.
        val candidates = walks.indices.filter { walks[it].size >= 2 }
            .sortedWith(compareByDescending<Int> { walkKey(walks[it]) }.thenByDescending { it })
        val used = ArrayList<Int>()
        var total = 0
        for (i in candidates) {
            if (total + walks[i].size > TraceConstants.MAX_DETECTION_POINTS) break
            total += walks[i].size
            used.add(i)
        }
        used.sort()
        if (used.size < 2) return result
        val local = used.map { walks[it] }
        val index = SegmentIndex(local, useIndex)

        val samples = local.map { buildSamples(it) }
        val runs = ArrayList<List<IntRange>>(local.size) // the repeated runs, as sample index ranges
        val repeated = ArrayList<List<Stretch>>(local.size)
        for ((w, s) in samples.withIndex()) {
            val near = nearFlags(s, index, w)
            bridge(s, near)
            val found = repeatedRuns(s, near)
            runs.add(found)
            repeated.add(found.map { Stretch(s.arc[it.first], s.arc[it.last]) })
        }
        for ((w, s) in samples.withIndex()) {
            result[used[w]] = WalkRepeats(repeated[w], shown(w, s, runs[w], local, index, repeated))
        }
        return result
    }

    private fun walkKey(points: List<TracePoint>): Long = points.first().atMs

    /** The densified samples of one walk (see [SampleBuilder]). */
    internal fun buildSamples(points: List<TracePoint>): Samples {
        val b = SampleBuilder()
        for (p in points) b.add(p.lat, p.lon, p.resumed)
        return b.samples
    }

    /** Step 4: a sample is near when some other walk's polyline is within the tolerance (inclusive). */
    private fun nearFlags(s: Samples, index: SegmentIndex, walk: Int): BooleanArray =
        BooleanArray(s.size) { index.anyWithin(s.lat[it], s.lon[it], TraceConstants.TOLERANCE_M, walk) }

    /**
     * Step 5: a series of non-near samples with a near sample on both sides, in one part, is filled when the arc length
     * between those two near samples is at most the bridge. One pass over the original flags.
     */
    internal fun bridge(s: Samples, near: BooleanArray) {
        val original = near.copyOf()
        var i = 0
        while (i < s.size) {
            if (original[i]) { i++; continue }
            var j = i
            while (j < s.size && !original[j]) j++
            // the series is i until j; its neighbours are i - 1 and j
            if (i > 0 && j < s.size && s.part[i - 1] == s.part[j] &&
                s.arc[j] - s.arc[i - 1] <= TraceConstants.BRIDGE_M
            ) for (k in i until j) near[k] = true
            i = j
        }
    }

    /** Step 6: the maximal runs of near samples of one part, kept when their length is at least the minimum. */
    internal fun repeatedRuns(s: Samples, near: BooleanArray): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < s.size) {
            if (!near[i]) { i++; continue }
            var j = i
            while (j + 1 < s.size && near[j + 1] && s.part[j + 1] == s.part[i]) j++
            if (s.arc[j] - s.arc[i] >= TraceConstants.MIN_RUN_M) out.add(i..j)
            i = j + 1
        }
        return out
    }

    /**
     * Step 7: each repeated stretch is drawn by one walk. A repeated sample of W is left to a newer walk V when V is
     * within the tolerance of it and the point of V nearest to it lies inside one of V's own repeated stretches (a
     * margin of 0.5 m). The samples that remain, as maximal series of at least two (of one part), are the shown stretches.
     */
    private fun shown(
        w: Int, s: Samples, runs: List<IntRange>, walks: List<List<TracePoint>>, index: SegmentIndex,
        repeated: List<List<Stretch>>,
    ): List<Stretch> {
        if (runs.isEmpty()) return emptyList()
        val keep = BooleanArray(s.size)
        for (run in runs) for (k in run) keep[k] = true
        val myKey = walkKey(walks[w])
        // per walk: the best (distance, segment) of the current sample
        val bestD = HashMap<Int, Double>()
        val bestSeg = HashMap<Int, Int>()
        for (run in runs) for (k in run) {
            bestD.clear(); bestSeg.clear()
            index.forEachWithin(s.lat[k], s.lon[k], TraceConstants.TOLERANCE_M, w) { seg, v, d ->
                val vKey = walkKey(walks[v])
                if (vKey > myKey || (vKey == myKey && v > w)) {
                    val old = bestD[v]
                    if (old == null || d < old || (d == old && seg < bestSeg.getValue(v))) {
                        bestD[v] = d; bestSeg[v] = seg
                    }
                }
            }
            for ((v, seg) in bestSeg) {
                val arc = index.arcAtNearest(seg, s.lat[k], s.lon[k])
                if (repeated[v].any { arc >= it.fromM - TraceConstants.SHOWN_MARGIN_M && arc <= it.toM + TraceConstants.SHOWN_MARGIN_M }) {
                    keep[k] = false
                    break
                }
            }
        }
        val out = ArrayList<Stretch>()
        var i = 0
        while (i < s.size) {
            if (!keep[i]) { i++; continue }
            var j = i
            while (j + 1 < s.size && keep[j + 1] && s.part[j + 1] == s.part[i]) j++
            if (j > i) out.add(Stretch(s.arc[i], s.arc[j]))
            i = j + 1
        }
        return out
    }

    /**
     * The polylines of [stretches] on [walk] for GeoJSON: each stretch's part of the walk as `[lat, lon]` pairs, its
     * ends cut exactly at the stretch's arc lengths (so an overlay meets the base line without a gap). Never across a
     * resumed point.
     */
    fun pieces(walk: List<TracePoint>, stretches: List<Stretch>): List<List<Pair<Double, Double>>> {
        val out = ArrayList<List<Pair<Double, Double>>>()
        for (st in stretches) {
            val line = ArrayList<Pair<Double, Double>>()
            var arc = 0.0
            for (i in 1 until walk.size) {
                val a = walk[i - 1]
                val b = walk[i]
                if (b.resumed) continue
                val len = TraceGeo.segmentLengthM(a.lat, a.lon, b.lat, b.lon)
                val from = arc
                val to = arc + len
                arc = to
                if (to <= st.fromM || from >= st.toM || len == 0.0) continue
                val t0 = maxOf(0.0, (st.fromM - from) / len)
                val t1 = minOf(1.0, (st.toM - from) / len)
                val p0 = (a.lat + (b.lat - a.lat) * t0) to (a.lon + (b.lon - a.lon) * t0)
                val p1 = (a.lat + (b.lat - a.lat) * t1) to (a.lon + (b.lon - a.lon) * t1)
                if (line.isEmpty() || line.last() != p0) line.add(p0)
                line.add(p1)
            }
            if (line.size >= 2) out.add(line)
        }
        return out
    }
}
