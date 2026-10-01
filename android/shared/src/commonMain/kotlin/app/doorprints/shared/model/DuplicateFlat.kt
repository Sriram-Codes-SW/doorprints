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

package app.doorprints.shared.model

import app.doorprints.shared.ai.RouteOptimizer

/**
 * The facts the duplicate-flat warning compares (docs/11 5.25, S4b-BL-85): the point and how it was set, the BHK, the
 * rooms (whose bedrooms win over the BHK) and the floor.
 */
data class FlatFacts(
    val id: String,
    val lat: Double,
    val lon: Double,
    val locationSource: String? = null,
    val bedrooms: Int? = null,
    val rooms: List<HouseRoom>? = null,
    val floor: Int? = null,
)

/**
 * "Two brokers show the same flat" (docs/11 5.25, S4b-BL-85): another house within [RADIUS_M] of this one, with the
 * same bedrooms and the same floor. A warning only, never a refusal. Both houses need a placed point (not "no
 * location yet", not approximate: [HousePoint.placed]), a known number of bedrooms ([bedrooms]) and a known floor. The
 * web's `duplicateFlats` (`shared/duplicate-flat.ts`) keeps the same vectors (`DuplicateFlatTest`, `duplicate-flat.spec.ts`).
 */
object DuplicateFlat {
    /** "Within about 30 m": a GPS fix at a door is good to some metres, and two flats of a building share it. */
    const val RADIUS_M = 30.0

    /** The bedrooms the check compares: the rooms of type BEDROOM when the house lists any, else its BHK. */
    fun bedrooms(bedrooms: Int?, rooms: List<HouseRoom>?): Int? =
        rooms?.count { it.roomType == RoomType.BEDROOM }?.takeIf { it > 0 } ?: bedrooms

    /** True when [a] and [b] are two houses that look like one flat. */
    fun isSameFlat(a: FlatFacts, b: FlatFacts): Boolean {
        if (a.id == b.id || a.floor == null || a.floor != b.floor) return false
        val beds = bedrooms(a.bedrooms, a.rooms) ?: return false
        if (beds != bedrooms(b.bedrooms, b.rooms)) return false
        if (!HousePoint(a.lat, a.lon, locationSource = a.locationSource).placed) return false
        if (!HousePoint(b.lat, b.lon, locationSource = b.locationSource).placed) return false
        return RouteOptimizer.haversineMeters(a.lat, a.lon, b.lat, b.lon) <= RADIUS_M
    }

    /** The ids of [others] that look like the same flat as [house], in the order given. */
    fun of(house: FlatFacts, others: List<FlatFacts>): List<String> = others.filter { isSameFlat(house, it) }.map { it.id }
}
