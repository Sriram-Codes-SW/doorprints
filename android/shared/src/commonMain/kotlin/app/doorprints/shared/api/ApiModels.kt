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

/**
  * A house on the wire (`/api/houses`), also the form the Drive sync file stores. Times are ISO-8601 text; [deleted]
  * marks a
 * tombstone and [syncVersion] is the server's position of the row (0 where none applies).
 */
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
    /** The floor (S4b-BL-87), -5..200 with 0 the ground floor, after `moveIn`; absent when unknown. */
    val floor: Int? = null,
    /** The broker's record id (slice 1b); the server keeps no foreign key, a dangling id reads as no broker. */
    val brokerId: String? = null,
    val checklist: Map<String, Int> = emptyMap(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deleted: Boolean = false,
    val syncVersion: Long = 0,
)

/** A visit on the wire (`/api/visits`): where and when the person arrived and left; a visit may be tied to a house. */
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

/**
 * One row of the record envelope (docs/11 5.30 item 2, ADR-28; `GET /api/records?since=`, `PUT /api/records/{type}/{id}`):
 * the server stores [payload] opaquely and never reads it. A tombstone carries `{}`.
 */
@Serializable
data class RecordDto(
    val type: String,
    val id: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    val updatedAt: String? = null,
    val deleted: Boolean = false,
    val syncVersion: Long = 0,
)

/**
 * `GET /api/stats`. [maxSyncVersion] is the highest sync version the server has handed out (added 2026-09-24,
 * S4b-BL-20): null from an older server that does not send it, which counts as unknown (no reset detected from it).
 */
@Serializable
data class StatsDto(
    val houses: Long,
    val shortlisted: Long,
    val rejected: Long,
    val visits: Long,
    val streets: Long,
    val maxSyncVersion: Long? = null,
)

// ---- AI endpoints (docs/ai/ai-design.md section 13; backend app.doorprints.server.ai.*) ----

/** The server's AI switches: whether AI and the MCP endpoint are on, and the models in use. */
@Serializable
data class AiStatusDto(
    val enabled: Boolean = false,
    val mcpEnabled: Boolean = false,
    val chatModel: String? = null,
    val embeddingModel: String? = null,
    /** AI is on, but the server's owner turned it off for this device (docs/03 §12.1). Older servers omit it. */
    val offForDevice: Boolean = false,
)

// ---- Pairing (docs/03 §12.1, ADR-25; backend app.doorprints.server.device.PairingController) ----

/** Begins pairing: the name this device will be listed under. */
@Serializable
data class PairStartRequest(val deviceName: String)

/** A code to type on the owner page (shown as `K7MQ-4XRD`), the token to poll with, and its timing in seconds. */
@Serializable
data class PairStartedDto(val userCode: String, val pollToken: String, val expiresIn: Long, val interval: Int)

/** Polls a pairing with the token from [PairStartedDto]. */
@Serializable
data class PairPollRequest(val pollToken: String)

/** [status] is pending, approved, denied or expired; [deviceKey] only with approved, and only once. */
@Serializable
data class PairPolledDto(val status: String, val deviceKey: String? = null)

/** Pairs with an invite code the owner made, naming this device. */
@Serializable
data class PairRedeemRequest(val invite: String, val deviceName: String)

/** The API key issued to this device once pairing is approved; a secret. */
@Serializable
data class DeviceKeyDto(val deviceKey: String)

/** The pasted listing text to read. */
@Serializable
data class ExtractListingRequest(val text: String)

/** A suggestion only: nothing is saved until the user saves the form. */
@Serializable
data class HouseDraftDto(
    val label: String? = null,
    val address: String? = null,
    val street: String? = null,
    val locality: String? = null,
    val price: Long? = null,
    val priceType: String? = null,
    val bedrooms: Int? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val listingUrl: String? = null,
    val notes: String? = null,
    val amenities: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** The carpet area in sq ft when the text says it (slice 1a; the no-AI parser fills it, `ListingText`). */
    val areaSqft: Int? = null,
)

/** A question about the saved houses. */
@Serializable
data class AskRequest(val question: String)

/** A house the answer relied on, with a label and the line of its record that supports it. */
@Serializable
data class CitationDto(val houseId: String, val label: String? = null, val snippet: String? = null)

/**
  * An answer to Ask with its [citations]. [grounded] says the answer rests on cited houses; [retrieved] is how many
  * houses
 * were considered.
 */
@Serializable
data class AskResponseDto(
    val answer: String = "",
    val citations: List<CitationDto> = emptyList(),
    val grounded: Boolean = false,
    val retrieved: Int = 0,
)

/** A visit-plan request: the wish in words, the start point, and an optional cap on stops. */
@Serializable
data class PlanRequest(val question: String, val startLat: Double, val startLon: Double, val maxStops: Int? = null)

/** One stop of a plan, in visiting order, with the walk from the previous point. */
@Serializable
data class PlannedStopDto(
    val order: Int,
    val houseId: String,
    val label: String? = null,
    val lat: Double,
    val lon: Double,
    val reason: String? = null,
    val legMeters: Long = 0,
    val walkMinutes: Int = 0,
)

/**
  * A visit plan; [fallback] is true when the stops are the nearest-neighbour order because the model gave no usable
  * plan.
 */
@Serializable
data class PlanResponseDto(
    val summary: String? = null,
    val stops: List<PlannedStopDto> = emptyList(),
    val totalMeters: Long = 0,
    val totalWalkMinutes: Int = 0,
    val toolCalls: List<String> = emptyList(),
    val fallback: Boolean = false,
)
