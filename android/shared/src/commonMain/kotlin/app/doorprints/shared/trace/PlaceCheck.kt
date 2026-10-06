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

import kotlin.math.cos

/** The answer of the on-demand *Have I been here?* check (docs/11 5.27.13 step 5). */
enum class PlaceCheckStatus { WALKED, CLOSE, NONE, EMPTY, IMPRECISE, INVALID_PLACE }

/** The band of one row: within the tolerance (inclusive) is WALKED, up to the near band is CLOSE. */
enum class PlaceBand { WALKED, CLOSE }

/** One walk's row: the walk's index in the input, its nearest distance, the interpolated time there, its band and source. */
data class PlaceRow(
    val walkIndex: Int,
    val distanceM: Double,
    val atMs: Long,
    val band: PlaceBand,
    val source: WalkSource,
)

/** [fuzzy]: an accepted fix looser than the tolerance (the accuracy is never added to the tolerance, it only warns). */
data class PlaceCheckResult(
    val status: PlaceCheckStatus,
    val fuzzy: Boolean,
    val nearestM: Double?,
    val rows: List<PlaceRow>,
)

/**
 * *Have I been here?* (docs/11 5.27.13): the distance from a place to each walk's polyline, on the local plane
 * centred on the place. Pure: no clock, no I/O, no network, nothing stored.
 */
object PlaceCheck {
    fun check(
        placeLat: Double,
        placeLon: Double,
        walks: List<TraceWalk>,
        fixAccuracyM: Double? = null,
    ): PlaceCheckResult = check(placeLat, placeLon, walks, fixAccuracyM, boxRejection = true)

    internal fun check(
        placeLat: Double,
        placeLon: Double,
        walks: List<TraceWalk>,
        fixAccuracyM: Double?,
        boxRejection: Boolean,
        toleranceM: Double = TraceConstants.TOLERANCE_M,
    ): PlaceCheckResult {
        // 1. Gate.
        if (!placeLat.isFinite() || !placeLon.isFinite() || placeLat !in -90.0..90.0 || placeLon !in -180.0..180.0) {
            return PlaceCheckResult(PlaceCheckStatus.INVALID_PLACE, false, null, emptyList())
        }
        if (fixAccuracyM != null &&
            (!fixAccuracyM.isFinite() || fixAccuracyM < 0 || fixAccuracyM > TraceConstants.MAX_FIX_ACCURACY_M)
        ) return PlaceCheckResult(PlaceCheckStatus.IMPRECISE, false, null, emptyList())
        val fuzzy = fixAccuracyM != null && fixAccuracyM > toleranceM // the tolerance the walked test uses (an override exists in tests only)

        // The place's box grown by the near band, in degrees: a segment wholly outside it cannot be within 50 m.
        val c = cos(TraceGeo.rad(placeLat))
        val dLat = TraceConstants.NEAR_BAND_M * 1.0001 / TraceGeo.K + 1e-12
        val dLon = if (c > 1e-6) TraceConstants.NEAR_BAND_M * 1.0001 / (TraceGeo.K * c) + 1e-12 else Double.POSITIVE_INFINITY

        var anyWalk = false
        val rows = ArrayList<PlaceRow>()
        for ((wi, walk) in walks.withIndex()) {
            val pts = walk.points
            var hasSegment = false
            var bestD = Double.POSITIVE_INFINITY
            var bestT = 0.0
            var bestSeg = -1
            for (i in 1 until pts.size) {
                val b = pts[i]
                if (b.resumed) continue // no segment into a resumed point
                hasSegment = true
                val a = pts[i - 1]
                if (boxRejection &&
                    (maxOf(a.lat, b.lat) < placeLat - dLat || minOf(a.lat, b.lat) > placeLat + dLat ||
                        maxOf(a.lon, b.lon) < placeLon - dLon || minOf(a.lon, b.lon) > placeLon + dLon)
                ) continue
                val d = TraceGeo.distanceToSegmentM(placeLat, placeLon, a.lat, a.lon, b.lat, b.lon)
                if (d < bestD) { // ties keep the first segment found
                    bestD = d; bestSeg = i
                }
            }
            if (!hasSegment) continue // a fragment is no walk
            anyWalk = true
            if (bestSeg < 0 || bestD > TraceConstants.NEAR_BAND_M) continue
            val a = pts[bestSeg - 1]
            val b = pts[bestSeg]
            val t = TraceGeo.fractionOnSegment(placeLat, placeLon, a.lat, a.lon, b.lat, b.lon)
            rows.add(
                PlaceRow(
                    wi, bestD, TraceGeo.interpolatedAtMs(a.atMs, b.atMs, t),
                    if (bestD <= toleranceM) PlaceBand.WALKED else PlaceBand.CLOSE, walk.source,
                ),
            )
        }
        // 6. Newest first by time; a tie by the later input index. Never by distance.
        val ordered = rows.sortedWith(compareByDescending<PlaceRow> { it.atMs }.thenByDescending { it.walkIndex })
        val status = when {
            !anyWalk -> PlaceCheckStatus.EMPTY
            ordered.any { it.band == PlaceBand.WALKED } -> PlaceCheckStatus.WALKED
            ordered.isNotEmpty() -> PlaceCheckStatus.CLOSE
            else -> PlaceCheckStatus.NONE
        }
        return PlaceCheckResult(status, fuzzy, ordered.minOfOrNull { it.distanceM }, ordered)
    }
}
