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

package app.doorprints.shared.location

/**
 * Whether a point is in India as the Government of India depicts it (ADR-22, S4b-BL-174c): the test that tells a place of
 * India from one of a neighbour (Dhaka, Kathmandu, Colombo, Thimphu are inside India's bounding box but not India).
 * The rings are [INDIA_OUTLINE_RINGS], built by `web/scripts/geo/build_in_outline.py` from the repository's own boundary
 * data `in-boundaries.geojson`, and the website holds the same ring (`india-outline.geojson`); a point within a few hundred
 * metres of the line may fall on either side.
 */
object IndiaOutline {
    private class Ring(val points: DoubleArray, val south: Double, val west: Double, val north: Double, val east: Double)

    private val rings: List<Ring> by lazy { INDIA_OUTLINE_RINGS.map(::parse) }

    /** The rings as latitude and longitude pairs, for the tests that compare them with the website's file. */
    internal fun points(): List<List<Pair<Double, Double>>> =
        rings.map { r -> List(r.points.size / 2) { r.points[2 * it] to r.points[2 * it + 1] } }

    private fun parse(text: String): Ring {
        val pairs = text.split(';')
        val points = DoubleArray(pairs.size * 2)
        for ((i, pair) in pairs.withIndex()) {
            val comma = pair.indexOf(',')
            points[2 * i] = pair.substring(0, comma).toDouble()
            points[2 * i + 1] = pair.substring(comma + 1).toDouble()
        }
        var south = Double.MAX_VALUE
        var west = Double.MAX_VALUE
        var north = -Double.MAX_VALUE
        var east = -Double.MAX_VALUE
        for (i in pairs.indices) {
            south = minOf(south, points[2 * i])
            north = maxOf(north, points[2 * i])
            west = minOf(west, points[2 * i + 1])
            east = maxOf(east, points[2 * i + 1])
        }
        return Ring(points, south, west, north, east)
    }

    /** True when ([lat], [lon]) is inside the mainland ring, the Andaman and Nicobar Islands or Lakshadweep. */
    fun contains(lat: Double, lon: Double): Boolean = rings.any { r ->
        lat in r.south..r.north && lon in r.west..r.east && inside(r.points, lat, lon)
    }

    /** The even-odd rule: a ray towards east from the point crosses the ring an odd number of times. */
    private fun inside(p: DoubleArray, lat: Double, lon: Double): Boolean {
        var hit = false
        var j = p.size / 2 - 1
        for (i in 0 until p.size / 2) {
            val yi = p[2 * i]
            val xi = p[2 * i + 1]
            val yj = p[2 * j]
            val xj = p[2 * j + 1]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) hit = !hit
            j = i
        }
        return hit
    }
}
