package com.househunt.shared.api

import kotlinx.serialization.Serializable

// Wire types of the Spring Boot API (backend com.househunt.*). Moved unchanged from :app's data/Api.kt in Sprint 3.5:
// same property names, defaults and nullability, so the JSON on the wire is identical. Timestamps are ISO-8601
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

@Serializable
data class StatsDto(val houses: Long, val shortlisted: Long, val rejected: Long, val visits: Long, val streets: Long)

// ---- AI endpoints (docs/ai/ai-design.md section 13; backend com.househunt.ai.*) ----

@Serializable
data class AiStatusDto(
    val enabled: Boolean = false,
    val mcpEnabled: Boolean = false,
    val chatModel: String? = null,
    val embeddingModel: String? = null,
)

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
