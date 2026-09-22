package com.househunt.app.data

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

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

/** One row of {@code GET /api/photos?since=} : a new photo or a delete tombstone (no bytes). */
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

/**
 * A failed API call. [kind] drives the message shown to the user; the server's response body is never shown
 * (threat model F-12), only logged in debug builds.
 */
class ApiException(
    val kind: Kind,
    val code: Int = 0,
    val retryAfterSeconds: Long? = null,
) : IOException("HTTP $code ($kind)") {
    enum class Kind { AUTH, CAPTIVE_PORTAL, RATE_LIMITED, NOT_FOUND, CONFLICT, CLIENT, SERVER, AI_UNAVAILABLE }
}

private fun Long.iso() = Instant.ofEpochMilli(this).toString()
private fun String.millis() = Instant.parse(this).toEpochMilli()

fun HouseEntity.toDto() = HouseDto(
    id, label, address, street, locality, lat, lon, status.name, price, priceType, bedrooms, rating,
    contactName, contactPhone, listingUrl, notes, checklist, createdAt.iso(), updatedAt.iso(), deleted,
)

fun HouseDto.toEntity() = HouseEntity(
    id = id, label = label, address = address, street = street, locality = locality, lat = lat, lon = lon,
    status = runCatching { HouseStatus.valueOf(status ?: "NEW") }.getOrDefault(HouseStatus.NEW),
    price = price, priceType = priceType, bedrooms = bedrooms, rating = rating, contactName = contactName,
    contactPhone = contactPhone, listingUrl = listingUrl, notes = notes, checklist = checklist,
    createdAt = createdAt?.millis() ?: System.currentTimeMillis(),
    updatedAt = updatedAt?.millis() ?: System.currentTimeMillis(),
    deleted = deleted, dirty = false,
)

fun VisitEntity.toDto() = VisitDto(
    id, houseId, lat, lon, street, arrivedAt.iso(), leftAt?.iso(), source.name, updatedAt.iso(), deleted,
)

fun VisitDto.toEntity() = VisitEntity(
    id = id, houseId = houseId, lat = lat, lon = lon, street = street, arrivedAt = arrivedAt.millis(),
    leftAt = leftAt?.millis(),
    source = runCatching { VisitSource.valueOf(source ?: "MANUAL") }.getOrDefault(VisitSource.MANUAL),
    updatedAt = updatedAt?.millis() ?: System.currentTimeMillis(), deleted = deleted, dirty = false,
)

/** Marks a request as safe to retry even though it is a POST (the photo upload carries a client-chosen id). */
object Idempotent

/**
 * Blocking client for the Spring Boot API. Call from a background dispatcher.
 *
 * Transport behaviour (docs/09-osi-layer-analysis.md, L4-L7):
 *  - one shared OkHttp connection pool (keep-alive, HTTP/2 when the host offers it, transparent gzip),
 *  - retries with exponential backoff and full jitter for idempotent calls ([RetryInterceptor]),
 *  - no redirects: a redirect would send the API key to another host, and captive portals answer with one,
 *  - every JSON response must really be JSON, otherwise it is a captive portal or a proxy error page.
 */
class ApiClient(baseUrl: String, private val apiKey: String) {

    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    private val http = shared.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-API-Key", apiKey).build())
        }
        .addInterceptor(RetryInterceptor())
        .build()

    private fun call(request: Request, expect: Expect = Expect.JSON): ByteArray =
        http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) {
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "HTTP ${res.code} for ${request.method} ${request.url.encodedPath}")
                throw ApiException(
                    kind = when (res.code) {
                        401, 403 -> ApiException.Kind.AUTH
                        404 -> ApiException.Kind.NOT_FOUND
                        409 -> ApiException.Kind.CONFLICT
                        429 -> ApiException.Kind.RATE_LIMITED
                        503 -> if (request.url.encodedPath.startsWith("/api/ai/")) ApiException.Kind.AI_UNAVAILABLE else ApiException.Kind.SERVER
                        in 300..399 -> ApiException.Kind.CAPTIVE_PORTAL
                        in 400..499 -> ApiException.Kind.CLIENT
                        else -> ApiException.Kind.SERVER
                    },
                    code = res.code,
                    retryAfterSeconds = res.header("Retry-After")?.toLongOrNull(),
                )
            }
            val type = res.header("Content-Type").orEmpty().lowercase()
            val ok = when (expect) {
                Expect.JSON -> type.startsWith("application/json") || type.startsWith("application/problem+json")
                Expect.IMAGE -> type.startsWith("image/")
                Expect.ANY -> true
            }
            // A captive portal (hotel/airport Wi-Fi) answers 200 with its own HTML sign-in page.
            if (!ok) throw ApiException(ApiException.Kind.CAPTIVE_PORTAL, res.code)
            res.body?.bytes() ?: ByteArray(0)
        }

    private enum class Expect { JSON, IMAGE, ANY }

    private inline fun <reified T> get(path: String): T =
        json.decodeFromString(serializer<T>(), String(call(Request.Builder().url(base + path).build()), Charsets.UTF_8))

    private inline fun <reified B, reified T> send(method: String, path: String, body: B): T {
        val payload = json.encodeToString(serializer<B>(), body).toRequestBody(jsonType)
        val request = Request.Builder().url(base + path).method(method, payload).build()
        return json.decodeFromString(serializer<T>(), String(call(request), Charsets.UTF_8))
    }

    fun stats(): StatsDto = get("/api/stats")

    fun housesSince(version: Long): List<HouseDto> = get("/api/houses?since=$version")
    fun putHouse(h: HouseDto): HouseDto = send("PUT", "/api/houses/${h.id}", h)

    fun visitsSince(version: Long): List<VisitDto> = get("/api/visits?since=$version")
    fun putVisit(v: VisitDto): VisitDto = send("PUT", "/api/visits/${v.id}", v)

    fun photoChangesSince(version: Long): List<PhotoChangeDto> = get("/api/photos?since=$version")

    fun uploadPhoto(houseId: String, photoId: String, file: File) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("id", photoId)
            .addFormDataPart("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .build()
        call(
            Request.Builder().url("$base/api/houses/$houseId/photos").post(body)
                .tag(Idempotent::class.java, Idempotent).build()
        )
    }

    fun downloadPhoto(photoId: String): ByteArray =
        call(Request.Builder().url("$base/api/photos/$photoId").build(), Expect.IMAGE)

    /** Deleting twice, or a photo the server never had, is fine. */
    fun deletePhoto(photoId: String) {
        try {
            call(Request.Builder().url("$base/api/photos/$photoId").delete().build(), Expect.ANY)
        } catch (e: ApiException) {
            if (e.kind != ApiException.Kind.NOT_FOUND) throw e
        }
    }

    fun aiStatus(): AiStatusDto = get("/api/ai/status")
    fun extractListing(text: String): HouseDraftDto = send("POST", "/api/ai/extract-listing", ExtractListingRequest(text))
    fun ask(question: String): AskResponseDto = send("POST", "/api/ai/ask", AskRequest(question))
    fun planVisits(request: PlanRequest): PlanResponseDto = send("POST", "/api/ai/plan-visits", request)

    companion object {
        private const val TAG = "HouseHuntApi"

        /** One pool and dispatcher for the whole app; per-client settings are added with newBuilder(). */
        private val shared: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // Free hosts may need 30-60 s to wake a sleeping Java app (cold start).
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}
