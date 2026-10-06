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

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The local flat plane both trace algorithms use (docs/11 5.27.3 step 2): `x = (lon - q.lon) * cos(rad(q.lat)) * K`,
 * `y = (lat - q.lat) * K` around the query point `q`, `K = R * pi / 180`; between two points of a walk the latitude
 * used is the mean of the two. Pure and deterministic (no platform call).
 */
internal object TraceGeo {
    const val K = TraceConstants.EARTH_RADIUS_M * PI / 180.0

    fun rad(degrees: Double): Double = degrees * (PI / 180.0)

    /** The length of the segment a to b in metres, on the plane at the mean latitude. */
    fun segmentLengthM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dx = (bLon - aLon) * cos(rad((aLat + bLat) / 2)) * K
        val dy = (bLat - aLat) * K
        return sqrt(dx * dx + dy * dy)
    }

    /** The clamped fraction (0..1) of the point of segment a to b nearest to q; a segment of length 0 gives 0. */
    fun fractionOnSegment(qLat: Double, qLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val c = cos(rad(qLat)) * K
        val ax = (aLon - qLon) * c
        val ay = (aLat - qLat) * K
        val bx = (bLon - qLon) * c
        val by = (bLat - qLat) * K
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 == 0.0) return 0.0
        val t = (-ax * dx - ay * dy) / l2
        return if (t < 0.0) 0.0 else if (t > 1.0) 1.0 else t
    }

    /** The distance in metres from q to segment a to b, the segment clamped at its ends (a round cap at each). */
    fun distanceToSegmentM(qLat: Double, qLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val c = cos(rad(qLat)) * K
        val ax = (aLon - qLon) * c
        val ay = (aLat - qLat) * K
        val bx = (bLon - qLon) * c
        val by = (bLat - qLat) * K
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else {
            val raw = (-ax * dx - ay * dy) / l2
            if (raw < 0.0) 0.0 else if (raw > 1.0) 1.0 else raw
        }
        val px = ax + t * dx
        val py = ay + t * dy
        return sqrt(px * px + py * py)
    }

    /** The time at fraction [t] of a segment: `a + floor(t * (b - a) + 0.5)` (docs/11 5.27.13 step 2). */
    fun interpolatedAtMs(aAtMs: Long, bAtMs: Long, t: Double): Long =
        aAtMs + floor(t * (bAtMs - aAtMs) + 0.5).toLong()

    fun isValid(p: TracePoint): Boolean =
        p.lat.isFinite() && p.lon.isFinite() && p.lat in -90.0..90.0 && p.lon in -180.0..180.0
}
