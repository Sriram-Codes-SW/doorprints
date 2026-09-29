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

package app.doorprints.shared.ai

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Walking routes for Plan: the server's `RouteOptimizer`, ported (docs/03 §13.1). */
object RouteOptimizer {
    private const val EARTH_RADIUS_M = 6_371_008.8
    /** Average walking speed, metres per minute (about 4.8 km/h). */
    const val WALK_M_PER_MIN = 80.0
    /** Streets are not straight lines: a typical urban detour over the straight-line distance. */
    const val DETOUR_FACTOR = 1.3

    data class Point(val id: String, val lat: Double, val lon: Double)
    data class Leg(val to: Point, val meters: Double, val walkMinutes: Int)

    private fun rad(d: Double) = d * PI / 180

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun estimateWalkMinutes(meters: Double): Int = if (meters <= 0) 0 else ceil(meters * DETOUR_FACTOR / WALK_M_PER_MIN).toInt()

    /** Greedy nearest neighbour from the start; ties go to the earlier point, so the result is deterministic. */
    fun nearestNeighbour(startLat: Double, startLon: Double, stops: List<Point>): List<Leg> {
        val remaining = stops.toMutableList()
        val legs = mutableListOf<Leg>()
        var lat = startLat
        var lon = startLon
        while (remaining.isNotEmpty()) {
            var bestIdx = 0
            var best = Double.MAX_VALUE
            remaining.forEachIndexed { i, p ->
                val d = haversineMeters(lat, lon, p.lat, p.lon)
                if (d < best) {
                    best = d
                    bestIdx = i
                }
            }
            val next = remaining.removeAt(bestIdx)
            legs += Leg(next, best, estimateWalkMinutes(best))
            lat = next.lat
            lon = next.lon
        }
        return legs
    }

    /** Legs for a route in the given order. */
    fun legsInOrder(startLat: Double, startLon: Double, stops: List<Point>): List<Leg> {
        var lat = startLat
        var lon = startLon
        return stops.map { p ->
            val d = haversineMeters(lat, lon, p.lat, p.lon)
            lat = p.lat
            lon = p.lon
            Leg(p, d, estimateWalkMinutes(d))
        }
    }

    /** Java's `Math.round` (half up), not Kotlin's `round` (half even), so metres match the server's. */
    fun roundHalfUp(v: Double): Long = floor(v + 0.5).toLong()
}
