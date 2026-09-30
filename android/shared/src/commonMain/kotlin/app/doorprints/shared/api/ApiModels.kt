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

import app.doorprints.shared.model.HouseCost
import kotlinx.serialization.Serializable
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

/** One row of `GET /api/photos?since=`: a new photo or a delete tombstone (no bytes). */
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
)

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

@Serializable
data class PairStartRequest(val deviceName: String)

/** A code to type on the owner page (shown as `K7MQ-4XRD`), the token to poll with, and its timing in seconds. */
@Serializable
data class PairStartedDto(val userCode: String, val pollToken: String, val expiresIn: Long, val interval: Int)

@Serializable
data class PairPollRequest(val pollToken: String)

/** [status] is pending, approved, denied or expired; [deviceKey] only with approved, and only once. */
@Serializable
data class PairPolledDto(val status: String, val deviceKey: String? = null)

@Serializable
data class PairRedeemRequest(val invite: String, val deviceName: String)

@Serializable
data class DeviceKeyDto(val deviceKey: String)

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

@Serializable
data class AskRequest(val question: String)

@Serializable
data class CitationDto(val houseId: String, val label: String? = null, val snippet: String? = null)

@Serializable
data class AskResponseDto(
    val answer: String = "",
    val citations: List<CitationDto> = emptyList(),
    val grounded: Boolean = false,
    val retrieved: Int = 0,
)

@Serializable
data class PlanRequest(val question: String, val startLat: Double, val startLon: Double, val maxStops: Int? = null)

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

@Serializable
data class PlanResponseDto(
    val summary: String? = null,
    val stops: List<PlannedStopDto> = emptyList(),
    val totalMeters: Long = 0,
    val totalWalkMinutes: Int = 0,
    val toolCalls: List<String> = emptyList(),
    val fallback: Boolean = false,
)
