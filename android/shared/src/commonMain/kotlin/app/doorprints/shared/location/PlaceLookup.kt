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
 * Looking up a locality by name for a shared listing's "Where is it?" (S4b-BL-83, docs/11 5.29 item 4): the parts that
 * need no geocoder. The platform asks its own, on the person's tap only (Android `Geocoder.getFromLocationName`, the
 * iPhone `CLGeocoder.geocodeAddressString`, the website Nominatim `/search`), with [query], inside [INDIA]; [pick] reads
 * what came back. The point found is only a start: the person moves the map to the house.
 */
object PlaceLookup {
    /** The longest name sent: a locality, or at most an address line (the website's `MAX_QUERY`). */
    const val MAX_QUERY = 200

    /**
     * India's box (south-west, north-east) as the Government of India depicts the country (ADR-22: the north reaches
     * 37.1° in Ladakh), what a lookup is limited to: no listing of ours is elsewhere.
     */
    val INDIA = Box(south = 6.5, west = 68.1, north = 37.1, east = 97.4)

    data class Box(val south: Double, val west: Double, val north: Double, val east: Double) {
        fun contains(lat: Double, lon: Double): Boolean = lat in south..north && lon in west..east
    }

    /** A point a lookup found. */
    data class Found(val lat: Double, val lon: Double)

    /**
     * The name to look up: the locality, else the address, its white space collapsed, at most [MAX_QUERY] characters, with
     * ", India" after it unless it says India already (a bare "Indiranagar" otherwise finds places abroad first). Null when
     * there is nothing to look up.
     */
    fun query(locality: String?, address: String? = null): String? {
        val name = (locality?.takeIf { it.isNotBlank() } ?: address?.takeIf { it.isNotBlank() } ?: return null)
            .replace(Regex("\\s+"), " ").trim().let { cutOnCharacters(it, MAX_QUERY) }
        return if (name.contains("india", ignoreCase = true)) name else "$name, India"
    }

    /** [text] cut to [max] characters (code points), never inside a surrogate pair: half an emoji would reach the geocoder as a lone surrogate. */
    private fun cutOnCharacters(text: String, max: Int): String {
        var chars = 0
        var i = 0
        while (i < text.length && chars < max) {
            i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
            chars++
        }
        return text.substring(0, i).trim()
    }

    /** The first of [candidates] (latitude, longitude) inside [INDIA], or null; "no location" (0, 0) is never one. */
    fun pick(candidates: List<Pair<Double, Double>>): Found? = candidates.firstOrNull { (lat, lon) ->
        lat.isFinite() && lon.isFinite() && !(lat == 0.0 && lon == 0.0) && INDIA.contains(lat, lon)
    }?.let { Found(it.first, it.second) }
}
