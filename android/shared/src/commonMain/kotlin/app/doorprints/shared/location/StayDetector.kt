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
 * Turns a stream of GPS fixes into "stays": staying within [radiusM] of where you stopped for at
 * least [minStayMs] counts as being at a place (e.g. inside a house you're viewing).
 *
 * Not thread-safe: feed it from one thread (Android: the HuntService location callback).
 */
class StayDetector(
    private val radiusM: Double = 40.0,
    var minStayMs: Long = 4 * 60_000L,
) {
    sealed interface Event {
        /** You've been here long enough — fired once, while you're still there. */
        data class Started(val lat: Double, val lon: Double, val since: Long) : Event

        /** You left a place you had stayed at. */
        data class Ended(val lat: Double, val lon: Double, val arrivedAt: Long, val leftAt: Long) : Event
    }

    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorTime = -1L
    private var lastInside = 0L
    private var sumLat = 0.0
    private var sumLon = 0.0
    private var n = 0
    private var started = false

    val isStaying get() = started

    fun onLocation(lat: Double, lon: Double, time: Long): Event? {
        if (anchorTime < 0) {
            reset(lat, lon, time)
            return null
        }
        if (Geo.distanceM(anchorLat, anchorLon, lat, lon) <= radiusM) {
            sumLat += lat; sumLon += lon; n++
            lastInside = time
            if (!started && time - anchorTime >= minStayMs) {
                started = true
                return Event.Started(sumLat / n, sumLon / n, anchorTime)
            }
            return null
        }
        val ended = if (started) Event.Ended(sumLat / n, sumLon / n, anchorTime, lastInside) else null
        reset(lat, lon, time)
        return ended
    }

    private fun reset(lat: Double, lon: Double, time: Long) {
        anchorLat = lat; anchorLon = lon; anchorTime = time; lastInside = time
        sumLat = lat; sumLon = lon; n = 1
        started = false
    }
}
