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

import app.doorprints.shared.records.RecordRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One room of a house (docs/11 5.6, slice 1c): nested in the house as `rooms`, right after `cost`, on the wire, in
 * `data.json` and (as JSON text in `houses.rooms`) in Room. The keys and their order are the format's
 * (`docs/schemas/backup-sample.json`, `BackupFieldsTest`); an absent value is unknown and never written as `null`. The
 * TypeScript twin is `HouseRoom` in `web/src/app/core/models.ts`; the server's is `HouseRoom` in its `house` package.
 */
@Serializable
data class HouseRoom(
    /** `[A-Za-z0-9._-]{1,64}` but not `.` or `..` ([RecordRules.isValidId]), unique within the house. */
    val id: String,
    /** A [RoomType] name, kept as text so a type from a newer app reads as [RoomType.OTHER] instead of failing. */
    val type: String = RoomType.OTHER.name,
    /** Up to [HouseRooms.MAX_NAME] characters; absent when blank, and then the type's translated name is shown. */
    val name: String? = null,
    /** Whole centimetres, 0..[HouseRooms.MAX_CM]; absent when unknown. */
    val lengthCm: Int? = null,
    val widthCm: Int? = null,
    /** 1..5; absent while the room has not been checked. */
    val condition: Int? = null,
    /** Up to [HouseRooms.MAX_NOTES] characters. Never sent to AI: a note may hold a contact's name or number. */
    val notes: String? = null,
    /** The order shown (0 upwards); readers order by it, then by [id]. */
    val sort: Int = 0,
) {
    val roomType: RoomType get() = RoomType.fromWire(type)

    /** True when every value is within its range: what a backup's check demands (a bad file is refused whole). */
    val isValid: Boolean
        get() = RecordRules.isValidId(id) && (name?.length ?: 0) <= HouseRooms.MAX_NAME &&
            HouseRooms.cm(lengthCm) == lengthCm && HouseRooms.cm(widthCm) == widthCm &&
            (condition == null || condition in 1..5) && (notes?.length ?: 0) <= HouseRooms.MAX_NOTES && sort >= 0
}

/** The ten room types, in the spec's order (the form's menu order). Stored and sent by [name]. */
enum class RoomType {
    BEDROOM, HALL, KITCHEN, BATHROOM, BALCONY, POOJA, STUDY, UTILITY, STORE, OTHER;

    companion object {
        /** The type of a stored name; anything unknown or missing is [OTHER]. */
        fun fromWire(value: String?): RoomType = entries.firstOrNull { it.name == value } ?: OTHER
    }
}

/** A house's list of rooms: the cap, the reader's coercion and the area arithmetic, the same on both apps. */
object HouseRooms {
    const val MAX = 30
    const val MAX_NAME = 60
    const val MAX_NOTES = 2000
    const val MAX_CM = 5000

    /** A dimension as read, or null outside 0..[MAX_CM]. */
    fun cm(value: Int?): Int? = value?.takeIf { it in 0..MAX_CM }

    /**
     * What a reader keeps (a file, the server, another device, the form's save), like [HouseCost.coerced]: a room
     * with a bad id or an id already seen is dropped, an unknown type is [RoomType.OTHER], a value out of range is
     * unknown, blank text is none, a negative sort is 0; then the rooms in the order shown (sort, then id) and the
     * first [MAX] of them. Null for no rooms: an empty list is never stored or written.
     */
    fun coerced(rooms: List<HouseRoom>?): List<HouseRoom>? {
        if (rooms.isNullOrEmpty()) return null
        val seen = HashSet<String>()
        return rooms.asSequence()
            .filter { RecordRules.isValidId(it.id) && seen.add(it.id) }
            .map { r ->
                HouseRoom(
                    id = r.id,
                    type = r.roomType.name,
                    name = text(r.name, MAX_NAME),
                    lengthCm = cm(r.lengthCm),
                    widthCm = cm(r.widthCm),
                    condition = r.condition?.takeIf { it in 1..5 },
                    notes = text(r.notes, MAX_NOTES),
                    sort = r.sort.coerceAtLeast(0),
                )
            }
            .sortedWith(ORDER)
            .take(MAX)
            .toList()
            .takeIf { it.isNotEmpty() }
    }

    /** The order shown: [HouseRoom.sort], then the id. */
    val ORDER: Comparator<HouseRoom> = compareBy<HouseRoom> { it.sort }.thenBy { it.id }

    /** The room's floor area in square centimetres, or null unless both sizes are known. */
    fun areaSqCm(room: HouseRoom): Long? {
        val l = room.lengthCm ?: return null
        val w = room.widthCm ?: return null
        return l.toLong() * w
    }

    /** The sum of the areas that are known, and how many rooms have one (the total is worth showing from two). */
    fun totalAreaSqCm(rooms: List<HouseRoom>?): Pair<Long, Int> {
        val areas = rooms.orEmpty().mapNotNull(::areaSqCm)
        return areas.sum() to areas.size
    }

    /** The next room's sort: one past the largest, 0 for the first. */
    fun nextSort(rooms: List<HouseRoom>?): Int = (rooms.orEmpty().maxOfOrNull { it.sort } ?: -1) + 1

    private fun text(value: String?, max: Int): String? = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= max }

    private val listSerializer = ListSerializer(HouseRoom.serializer())
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    /**
     * The rooms as compact JSON text in the format's key order, or null for none: Room's `houses.rooms` column and the
     * house form's saved draft.
     */
    fun encode(rooms: List<HouseRoom>?): String? = rooms?.takeIf { it.isNotEmpty() }?.let { json.encodeToString(listSerializer, it) }

    /** [encode]'s text back, as it was written; null for none or for text that does not decode (never written here). */
    fun decode(text: String?): List<HouseRoom>? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }
}

/**
 * How lengths are shown on this device (the setting `units.length`): feet and inches (the default, as sizes are
 * talked about in India) or metres. Local only: never synced and not in a backup; a readable copy is written in the
 * unit of the device that makes it.
 */
enum class LengthUnit {
    FT, M;

    companion object {
        fun fromWire(value: String?): LengthUnit = entries.firstOrNull { it.name == value } ?: FT
    }
}
