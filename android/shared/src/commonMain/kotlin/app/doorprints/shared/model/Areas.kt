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
import app.doorprints.shared.records.RecordRules
import app.doorprints.shared.records.RecordType
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.random.Random

// Hunting areas, my places and area notes (docs/11 "Design of slice 4a", 5.17, 5.22, 5.23): three record types, each
// keyed by its own id (`a_`, `p_`, `n_` and 8 lowercase hex when the app makes it; any `RecordRules.isValidId` id is
// read). The TypeScript twin is `web/src/app/shared/area.ts`; the server stores the same keys in its `record` table.

/** True for a latitude and a longitude a reader keeps: finite and in range. */
internal fun validPoint(lat: Double, lon: Double): Boolean =
    lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

/** A fresh id: [prefix] and 8 random lowercase hex digits, drawn again while [taken] says it is used. */
internal fun newRecordId(prefix: String, taken: (String) -> Boolean, random: Random): String {
    while (true) {
        val id = prefix + (1..8).joinToString("") { random.nextInt(16).toString(16) }
        if (!taken(id)) return id
    }
}

/**
 * A hunting area (5.17): a name and a circle. The payload keys are, in this order, [name], [lat], [lon], [radiusM] and
 * [enabled] (written only when false; the wake-up of slice 4b reads it).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Area(
    /** The record id, not part of the payload. */
    @Transient val id: String = "",
    /** 1..[MAX_NAME] characters. */
    val name: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    /** [MIN_RADIUS]..[MAX_RADIUS] metres; any other value reads as [DEFAULT_RADIUS]. */
    val radiusM: Int = DEFAULT_RADIUS,
    /** *Wake me here*: stored and shown only in slice 4a. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val enabled: Boolean = true,
) {
    /**
     * What a reader keeps (the records table, a sync), as the web's `areaFromPayload`: null for an untrusted row (a bad
     * id, a blank or over-long name, a point out of range), skipped; a radius out of range is [DEFAULT_RADIUS].
     */
    fun coerced(): Area? {
        if (!RecordRules.isValidId(id) || name.isBlank() || name.length > MAX_NAME || !validPoint(lat, lon)) return null
        return if (radiusM in MIN_RADIUS..MAX_RADIUS) this else copy(radiusM = DEFAULT_RADIUS)
    }

    /** True when every value is in range: what a backup's check demands (a bad file is refused whole). */
    val isValid: Boolean
        get() = RecordRules.isValidId(id) && name.isNotBlank() && name.length <= MAX_NAME && validPoint(lat, lon) &&
            radiusM in MIN_RADIUS..MAX_RADIUS

    companion object {
        const val MAX_NAME = 100
        const val MIN_RADIUS = 200
        const val MAX_RADIUS = 2000
        const val DEFAULT_RADIUS = 500
        /** The slider's step on the *My areas* form. */
        const val RADIUS_STEP = 100
        /** At most this many live areas. */
        const val MAX_AREAS = 20
        const val ID_PREFIX = "a_"

        fun newId(taken: (String) -> Boolean, random: Random = Random.Default): String = newRecordId(ID_PREFIX, taken, random)

        /** The screens' order: by name ignoring case, then the id. */
        val BY_NAME: Comparator<Area> = compareBy<Area> { it.name.lowercase() }.thenBy { it.id }
    }
}

/** A place that matters (5.22): work, school, parents' home. The payload keys are [name], [lat], [lon]. */
@Serializable
data class Place(
    @Transient val id: String = "",
    /** 1..[MAX_NAME] characters. */
    val name: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
) {
    /** As [Area.coerced]: null for a bad id, a blank or over-long name or a point out of range. */
    fun coerced(): Place? =
        takeIf { RecordRules.isValidId(id) && name.isNotBlank() && name.length <= MAX_NAME && validPoint(lat, lon) }

    val isValid: Boolean get() = coerced() != null

    companion object {
        const val MAX_NAME = 60
        const val MAX_PLACES = 10
        const val ID_PREFIX = "p_"

        fun newId(taken: (String) -> Boolean, random: Random = Random.Default): String = newRecordId(ID_PREFIX, taken, random)

        val BY_NAME: Comparator<Place> = compareBy<Place> { it.name.lowercase() }.thenBy { it.id }
    }
}

/**
 * A note on a hunting area or a street (5.23). The payload keys are exactly one of [areaId] (an area's id; it may name
 * an area that is gone) or [street], then [text].
 */
@Serializable
data class AreaNote(
    @Transient val id: String = "",
    /** At most [MAX_REF] characters. */
    val areaId: String? = null,
    /** 1..[MAX_STREET] characters, compared with a house's street trimmed and ignoring case. */
    val street: String? = null,
    /** 1..[MAX_TEXT] characters. */
    val text: String = "",
    /** The record's last edit (epoch ms), not part of the payload: the house page's newest-first order. */
    @Transient val updatedAt: Long = 0L,
) {
    /**
     * As the web's `areaNoteFromPayload`: a blank or over-long target counts as absent, and a row without exactly one
     * target, or with a blank or over-long text, or a bad id, is null (skipped).
     */
    fun coerced(): AreaNote? {
        if (!RecordRules.isValidId(id)) return null
        val area = areaId?.takeIf { it.isNotBlank() && it.length <= MAX_REF }
        val road = street?.takeIf { it.isNotBlank() && it.length <= MAX_STREET }
        if (text.isBlank() || text.length > MAX_TEXT || (area == null) == (road == null)) return null
        return copy(areaId = area, street = road)
    }

    /** What a backup's check demands: a good id, exactly one non-blank target within its limit, a text of 1..1000. */
    val isValid: Boolean
        get() = RecordRules.isValidId(id) && (areaId == null) != (street == null) &&
            (areaId == null || (areaId.isNotBlank() && areaId.length <= MAX_REF)) &&
            (street == null || (street.isNotBlank() && street.length <= MAX_STREET)) &&
            text.isNotBlank() && text.length <= MAX_TEXT

    companion object {
        const val MAX_REF = 64
        const val MAX_STREET = 100
        const val MAX_TEXT = 1000
        const val MAX_NOTES = 200
        const val ID_PREFIX = "n_"

        fun newId(taken: (String) -> Boolean, random: Random = Random.Default): String = newRecordId(ID_PREFIX, taken, random)

        /** Newest [updatedAt] first, ties by id: the house page, the AI lines and the copies. */
        val NEWEST_FIRST: Comparator<AreaNote> = compareByDescending<AreaNote> { it.updatedAt }.thenBy { it.id }
    }
}

/** The `area`, `place` and `areanote` record types of the `records` table (slice 4a). */
val AreaType: RecordType<Area> = RecordType("area", Area.serializer())
val PlaceType: RecordType<Place> = RecordType("place", Place.serializer())
val AreaNoteType: RecordType<AreaNote> = RecordType("areanote", AreaNote.serializer())

/** The house values the derived rules read. */
data class HousePoint(val lat: Double, val lon: Double, val street: String? = null, val locationSource: String? = null) {
    /** The point is set: the apps keep a house without one at (0, 0). */
    val hasPoint: Boolean get() = !(lat == 0.0 && lon == 0.0)

    /** Placed for an area: a point that is not APPROX (an approximate spot is a ring, not a place). */
    val placed: Boolean get() = hasPoint && locationSource != LocationSource.APPROX
}

/** Which notes reach a house (docs/11 "Design of slice 4a"); the web's `notesReaching`, the server's `areaNotesReaching`. */
object AreaNotes {
    /** Street names match after trimming, ignoring case; a blank one matches nothing. */
    fun sameStreet(a: String?, b: String?): Boolean {
        val x = a?.trim().orEmpty()
        return x.isNotEmpty() && x.equals(b?.trim().orEmpty(), ignoreCase = true)
    }

    /** True when [house] is placed within [area]'s circle (haversine, [RouteOptimizer.haversineMeters]). */
    fun inside(house: HousePoint, area: Area): Boolean =
        house.placed && RouteOptimizer.haversineMeters(house.lat, house.lon, area.lat, area.lon) <= area.radiusM

    /**
     * The notes that reach [house], newest first (ties by id): an area note when its area is among the live [areas]
     * and the house is inside it; a street note when the house's street matches. A note whose area is gone reaches
     * nothing (vectors N1..N5).
     */
    fun reaching(house: HousePoint, areas: List<Area>, notes: List<AreaNote>): List<AreaNote> {
        val byId = areas.associateBy { it.id }
        return notes.filter { n ->
            when {
                n.areaId != null -> byId[n.areaId]?.let { inside(house, it) } == true
                else -> sameStreet(house.street, n.street)
            }
        }.sortedWith(AreaNote.NEWEST_FIRST)
    }

    /** The areas whose circle holds [house]: *Add a note for an area* offers these first. */
    fun areasReaching(house: HousePoint, areas: List<Area>): List<Area> = areas.filter { inside(house, it) }
}

/** A house's distance to one place: straight-line metres, and the Plan estimate for the screens. */
data class PlaceDistance(val place: Place, val meters: Double) {
    /** Kilometres with one decimal, half up ("8.6"). */
    val km: String get() = Distances.km(meters)

    /** Plan's walking estimate: the road distance (metres x 1.3) at Plan's walking speed, whole minutes. */
    val minutes: Int get() = RouteOptimizer.estimateWalkMinutes(meters)
}

/** Distances from a house to my places (5.22); the web's `distancesToPlaces`, the server's `distanceLines`. */
object Distances {
    /** Per place in the order given; a house without a point gets none (vectors D1..D3). */
    fun toPlaces(house: HousePoint, places: List<Place>): List<PlaceDistance> {
        if (!house.hasPoint) return emptyList()
        return places.map { PlaceDistance(it, RouteOptimizer.haversineMeters(house.lat, house.lon, it.lat, it.lon)) }
    }

    /** Nearest first, ties by name then id: the AI lines and the copies. */
    fun nearestFirst(list: List<PlaceDistance>): List<PlaceDistance> =
        list.sortedWith(compareBy<PlaceDistance> { it.meters }.thenBy { it.place.name }.thenBy { it.place.id })

    /** Metres as kilometres with one decimal, half up: 8572.7 m is "8.6", 0 is "0.0", 3211.7 m is "3.2". */
    fun km(meters: Double): String {
        val tenths = RouteOptimizer.roundHalfUp(meters / 100)
        return "${tenths / 10}.${tenths % 10}"
    }
}
