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

package app.doorprints.shared.api

import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.HouseCost
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.MoveIn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonObject

// Wire types of the Spring Boot API (backend app.doorprints.server.*). Moved unchanged from :app's data/Api.kt in
// Sprint 3.5: same property names, defaults and nullability, so the JSON on the wire is identical. Timestamps are ISO-8601
// strings (see IsoTime); the apps store epoch milliseconds.

@Serializable
data class HouseDto(
    val id: String,
    val label: String,
    val address: String? = null,
    val street: String? = null,
    val locality: String? = null,
    val lat: Double,
    val lon: Double,
    val status: String? = "NEW",
    val price: Long? = null,
    val priceType: String? = null,
    val bedrooms: Int? = null,
    val rating: Int? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val listingUrl: String? = null,
    val notes: String? = null,
    /** HouseDto v2 (docs/11 5.30 item 1, slice 1a): the three optional values, in the format's order, before `checklist`. */
    val areaSqft: Int? = null,
    /** `GPS`, `MAP` or `APPROX` (`LocationSource`); absent for a house saved before slice 1a. */
    val locationSource: String? = null,
    val cost: HouseCost? = null,
    /** The rooms (slice 1c), at most 30, after `cost`; absent for none (never `[]`), and `[]` read is none too. */
    val rooms: List<HouseRoom>? = null,
    /** The questions asked (slice 3a), at most 60, after `rooms`; absent for none (never `[]`), and `[]` read is none too. */
    val answers: List<HouseAnswer>? = null,
    /** Moving in (slice 5), after `answers`; absent when it has no date, notes or items. */
    val moveIn: MoveIn? = null,
    /** The broker's record id (slice 1b); the server keeps no foreign key, a dangling id reads as no broker. */
    val brokerId: String? = null,
    val checklist: Map<String, Int> = emptyMap(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deleted: Boolean = false,
    val syncVersion: Long = 0,
)

@Serializable
data class VisitDto(
    val id: String,
    val houseId: String? = null,
    val lat: Double,
    val lon: Double,
    val street: String? = null,
    val arrivedAt: String,
    val leftAt: String? = null,
    val source: String? = "MANUAL",
    val updatedAt: String? = null,
    val deleted: Boolean = false,
    val syncVersion: Long = 0,
)

/**
 * One row of `GET /api/photos?since=`: a new photo, a delete tombstone (no bytes) or, since slice 5, a change of a
 * photo's metadata (docs/11 5.7): its room, tags and caption and when they were last edited ([metaUpdatedAt], epoch ms,
 * 0 or absent = never). Also the answer of `PUT /api/photos/{id}/meta`, which is the photo's current meta.
 */
@Serializable
data class PhotoChangeDto(
    val id: String,
    val houseId: String,
    val contentType: String? = null,
    val sizeBytes: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deleted: Boolean = false,
    val syncVersion: Long = 0,
    val roomId: String? = null,
    val tags: List<String>? = null,
    val caption: String? = null,
    @Serializable(with = LenientEpochMillisSerializer::class)
    val metaUpdatedAt: Long? = null,
)

/** The body of `PUT /api/photos/{id}/meta` (slice 5): the meta and when it was edited (epoch ms); last write wins. */
@Serializable
data class PhotoMetaDto(
    val roomId: String? = null,
    val tags: List<String> = emptyList(),
    val caption: String? = null,
    val metaUpdatedAt: Long,
)

/**
 * An epoch-millisecond instant read from a number or, from a server that writes instants as text, an ISO-8601 string
 * ([IsoTime.parseMillis]); anything else reads as 0 (never edited), so one odd value never stops a sync. Written as a
 * number.
 */
object LenientEpochMillisSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EpochMillis", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long {
        val p = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive ?: return 0L
        return (if (p.isString) runCatching { IsoTime.parseMillis(p.content) }.getOrNull() else p.longOrNull) ?: 0L
    }
}
