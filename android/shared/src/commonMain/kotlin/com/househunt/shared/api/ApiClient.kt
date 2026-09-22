package com.househunt.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Builds the [HttpClient] behind [ApiClient]. Engine-specific settings (socket timeouts, connection pool) belong to
 * the platform engine (Android: AndroidApiHttp in androidMain); this only sets what must hold on every platform.
 */
object ApiHttp {
    fun client(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
        // No redirects: a redirect would send the API key to another host, and captive portals answer with one.
        followRedirects = false
        // Status codes are mapped to ApiException by ApiClient itself.
        expectSuccess = false
    }
}

/**
 * Client for the Spring Boot API. Replaces the OkHttp-based client that lived in :app until Sprint 3.5, with the
 * same observable behaviour (docs/09-osi-layer-analysis.md, L4-L7):
 *  - every request carries the `X-API-Key` header,
 *  - idempotent calls are retried with exponential backoff and full jitter ([RetryPolicy]),
 *  - redirects are never followed; a 3xx is reported as a captive portal,
 *  - every JSON response must really be JSON (and every photo an image), otherwise it is a captive portal or a
 *    proxy error page and [ApiException.Kind.CAPTIVE_PORTAL] is thrown,
 *  - the whole call, retries included, is limited to [callTimeoutMs] (was OkHttp's callTimeout, 4 minutes),
 *  - failures are [ApiException] (HTTP status) or another IOException (network); response bodies are never
 *    surfaced (threat model F-12).
 *
 * All functions suspend and are main-safe (Ktor does the I/O on its own dispatcher). One [http] client should be
 * shared by the whole app so connections are pooled; ApiClient itself is cheap and holds no resources.
 */
class ApiClient(
    baseUrl: String,
    private val apiKey: String,
    private val http: HttpClient,
    private val retry: RetryPolicy = RetryPolicy(),
    /** Null disables the overall limit (tests only). */
    private val callTimeoutMs: Long? = CALL_TIMEOUT_MS,
    /** Receives one line per failed HTTP status (status, method, path; never bodies or the key). */
    private val debugLog: ((String) -> Unit)? = null,
) {
    private val base = baseUrl.trimEnd('/')

    fun interface BodyFactory {
        /** Called once per attempt, so a retried request always sends a fresh, complete body. */
        fun create(): OutgoingContent
    }

    private enum class Expect { JSON, IMAGE, ANY }

    /** The parts of a response the client looks at; the body is read completely before the call returns. */
    private class Exchange(val status: Int, val contentType: String?, val retryAfter: String?, val body: ByteArray)

    private suspend fun call(
        method: HttpMethod,
        path: String,
        expect: Expect = Expect.JSON,
        markedIdempotent: Boolean = false,
        body: BodyFactory? = null,
    ): ByteArray {
        val url = base + path
        val exchange = if (callTimeoutMs == null) {
            exchangeWithRetry(method, url, markedIdempotent, body)
        } else {
            withTimeoutOrNull(callTimeoutMs) { exchangeWithRetry(method, url, markedIdempotent, body) }
                ?: throw ApiTimeoutException(callTimeoutMs)
        }
        if (exchange.status !in 200..299) {
            val encodedPath = Url(url).encodedPath
            debugLog?.invoke("HTTP ${exchange.status} for ${method.value} $encodedPath")
            throw ApiException(
                kind = ApiException.kindFor(exchange.status, encodedPath),
                code = exchange.status,
                retryAfterSeconds = exchange.retryAfter?.toLongOrNull(),
            )
        }
        val type = exchange.contentType.orEmpty().lowercase()
        val ok = when (expect) {
            Expect.JSON -> type.startsWith("application/json") || type.startsWith("application/problem+json")
            Expect.IMAGE -> type.startsWith("image/")
            Expect.ANY -> true
        }
        // A captive portal (hotel/airport Wi-Fi) answers 200 with its own HTML sign-in page.
        if (!ok) throw ApiException(ApiException.Kind.CAPTIVE_PORTAL, exchange.status)
        return exchange.body
    }

    private suspend fun exchangeWithRetry(
        method: HttpMethod,
        url: String,
        markedIdempotent: Boolean,
        body: BodyFactory?,
    ): Exchange {
        val retriable = retry.isRetriable(method.value, markedIdempotent)
        var attempt = 1
        while (true) {
            val exchange = try {
                exchangeOnce(method, url, body)
            } catch (e: IOException) {
                if (!retriable || attempt >= retry.maxAttempts || !currentCoroutineContext().isActive) throw e
                retry.sleep(retry.backoffMs(attempt))
                attempt++
                continue
            }
            if (!retriable || attempt >= retry.maxAttempts || exchange.status !in RetryPolicy.RETRY_CODES) {
                return exchange
            }
            val wait = exchange.retryAfter?.toLongOrNull()?.times(1000) ?: retry.backoffMs(attempt)
            if (wait > retry.maxDelayMs) return exchange
            retry.sleep(wait)
            attempt++
        }
    }

    private suspend fun exchangeOnce(method: HttpMethod, target: String, body: BodyFactory?): Exchange {
        val response = http.request {
            this.method = method
            url(target)
            header(API_KEY_HEADER, apiKey)
            if (body != null) setBody(body.create())
        }
        return Exchange(
            status = response.status.value,
            contentType = response.headers[HttpHeaders.ContentType],
            retryAfter = response.headers[HttpHeaders.RetryAfter],
            body = response.bodyAsBytes(),
        )
    }

    private fun jsonBody(text: String) = BodyFactory { TextContent(text, JSON_UTF8) }

    private suspend inline fun <reified T> get(path: String): T =
        json.decodeFromString(serializer<T>(), call(HttpMethod.Get, path).decodeToString())

    private suspend inline fun <reified B, reified T> send(method: HttpMethod, path: String, body: B): T {
        val payload = json.encodeToString(serializer<B>(), body)
        return json.decodeFromString(serializer<T>(), call(method, path, body = jsonBody(payload)).decodeToString())
    }

    suspend fun stats(): StatsDto = get("/api/stats")

    suspend fun housesSince(version: Long): List<HouseDto> = get("/api/houses?since=$version")
    suspend fun putHouse(h: HouseDto): HouseDto = send(HttpMethod.Put, "/api/houses/${h.id}", h)

    suspend fun visitsSince(version: Long): List<VisitDto> = get("/api/visits?since=$version")
    suspend fun putVisit(v: VisitDto): VisitDto = send(HttpMethod.Put, "/api/visits/${v.id}", v)

    suspend fun photoChangesSince(version: Long): List<PhotoChangeDto> = get("/api/photos?since=$version")

    /**
     * Uploads one JPEG as multipart/form-data: a text part "id" (the client-chosen photo id, which makes the POST
     * safe to retry) and a file part "file" with Content-Type image/jpeg and the given [fileName].
     * In-memory variant (tests, small payloads); the app streams from the file with the overload below.
     */
    suspend fun uploadPhoto(houseId: String, photoId: String, fileName: String, jpeg: ByteArray) {
        uploadPhoto(houseId, photoId, fileName, jpeg.size.toLong()) { Buffer().apply { write(jpeg) } }
    }

    /**
     * Streaming variant of [uploadPhoto]: the JPEG is not held in memory. [open] is called once per attempt (so a
     * retry re-reads the file from the start) and must return a new [Source] over exactly [size] bytes; the client
     * closes it. The request is byte-for-byte the same as with a [ByteArray] (the part also carries
     * Content-Length: [size], and the whole body keeps a known length).
     */
    suspend fun uploadPhoto(houseId: String, photoId: String, fileName: String, size: Long, open: () -> Source) {
        val quotedName = "\"" + fileName.replace("\"", "") + "\""
        call(
            HttpMethod.Post, "/api/houses/$houseId/photos", markedIdempotent = true,
            body = BodyFactory {
                MultiPartFormDataContent(
                    formData {
                        append("id", photoId)
                        append(
                            "file", InputProvider(size, open),
                            Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=$quotedName")
                            },
                        )
                    },
                )
            },
        )
    }

    suspend fun downloadPhoto(photoId: String): ByteArray =
        call(HttpMethod.Get, "/api/photos/$photoId", Expect.IMAGE)

    /** Deleting twice, or a photo the server never had, is fine. */
    suspend fun deletePhoto(photoId: String) {
        try {
            call(HttpMethod.Delete, "/api/photos/$photoId", Expect.ANY)
        } catch (e: ApiException) {
            if (e.kind != ApiException.Kind.NOT_FOUND) throw e
        }
    }

    suspend fun aiStatus(): AiStatusDto = get("/api/ai/status")
    suspend fun extractListing(text: String): HouseDraftDto =
        send(HttpMethod.Post, "/api/ai/extract-listing", ExtractListingRequest(text))
    suspend fun ask(question: String): AskResponseDto = send(HttpMethod.Post, "/api/ai/ask", AskRequest(question))
    suspend fun planVisits(request: PlanRequest): PlanResponseDto =
        send(HttpMethod.Post, "/api/ai/plan-visits", request)

    companion object {
        const val API_KEY_HEADER = "X-API-Key"

        /** Whole call including retries; free hosts may need 30-60 s just to wake a sleeping Java app. */
        const val CALL_TIMEOUT_MS = 4 * 60_000L

        private val JSON_UTF8 = ContentType.Application.Json.withCharset(Charsets.UTF_8)

        /** Lenient reader (new server fields are ignored), compact writer without explicit nulls. Same as v0.1. */
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
