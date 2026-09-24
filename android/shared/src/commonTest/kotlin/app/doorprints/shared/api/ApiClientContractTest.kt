package app.doorprints.shared.api

import app.doorprints.shared.api.RecordedResponses as R
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.VisitSource
import app.doorprints.shared.sync.SyncOutcome
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the Ktor [ApiClient] against recorded backend responses (MockEngine, no network). Each test
 * pins one behaviour of the OkHttp client it replaced in Sprint 3.5: the X-API-Key header, URLs and JSON on the
 * wire, the status -> [ApiException.Kind] mapping, retries with backoff for idempotent calls only, Retry-After,
 * captive-portal detection (HTML, redirects, wrong content type), redirects never followed, the overall call timeout.
 */
class ApiClientContractTest {

    private sealed interface Reply {
        class Http(val status: Int, val body: ByteArray, val headers: List<Pair<String, String>>) : Reply
        class Fail(val error: Throwable) : Reply
        class Hang(val ms: Long) : Reply
    }

    private class Seen(val method: String, val url: String, val headers: Headers, val contentType: String?, val body: ByteArray) {
        val text get() = body.decodeToString()
    }

    /** Plays back [replies] in order (the last one repeats) and records every request and backoff sleep. */
    private class FakeServer(vararg replies: Reply) {
        val seen = mutableListOf<Seen>()
        val sleeps = mutableListOf<Long>()
        val logs = mutableListOf<String>()

        private val engine = MockEngine { request ->
            val body = request.body.toByteArray()
            seen += Seen(request.method.value, request.url.toString(), request.headers,
                request.body.contentType?.toString(), body)
            when (val reply = replies[minOf(seen.size - 1, replies.size - 1)]) {
                is Reply.Fail -> throw reply.error
                is Reply.Hang -> {
                    delay(reply.ms)
                    respond(R.STATS, HttpStatusCode.OK, Headers.build { append("Content-Type", "application/json") })
                }
                is Reply.Http -> respond(
                    reply.body, HttpStatusCode.fromValue(reply.status),
                    Headers.build { reply.headers.forEach { (name, value) -> append(name, value) } },
                )
            }
        }

        fun api(baseUrl: String = BASE, callTimeoutMs: Long? = null) = ApiClient(
            baseUrl, KEY, ApiHttp.client(engine),
            retry = RetryPolicy(random = Random(42), sleep = { sleeps += it }),
            callTimeoutMs = callTimeoutMs,
            debugLog = { logs += it },
        )
    }

    private companion object {
        const val BASE = "https://api.example.com"
        const val KEY = "k3y-for-tests-only"
        const val JSON = "application/json"
        const val PROBLEM = "application/problem+json"

        fun json(status: Int, body: String, vararg extra: Pair<String, String>) =
            Reply.Http(status, body.encodeToByteArray(), listOf("Content-Type" to JSON) + extra)

        fun problem(status: Int, body: String, vararg extra: Pair<String, String>) =
            Reply.Http(status, body.encodeToByteArray(), listOf("Content-Type" to PROBLEM) + extra)

        fun html(status: Int, body: String, vararg extra: Pair<String, String>) =
            Reply.Http(status, body.encodeToByteArray(), listOf("Content-Type" to "text/html; charset=utf-8") + extra)

        fun empty(status: Int, vararg headers: Pair<String, String>) = Reply.Http(status, ByteArray(0), headers.toList())

        fun reset() = Reply.Fail(IOException("unexpected end of stream"))
    }

    // ---------------------------------------------------------------- happy paths, headers, URLs, JSON on the wire

    @Test
    fun statsCarryTheServersHighestSyncVersionAndAnOlderServerSendsNone() = runTest {
        // S4b-BL-20: read when present; an older server's answer (R.STATS, no field) leaves it null, i.e. unknown.
        assertEquals(418L, FakeServer(json(200, R.STATS_WITH_MAX_VERSION)).api().stats().maxSyncVersion)
        assertNull(FakeServer(json(200, R.STATS)).api().stats().maxSyncVersion)
    }

    @Test
    fun statsSendsTheApiKeyAndParses() = runTest {
        val server = FakeServer(json(200, R.STATS))
        assertEquals(StatsDto(houses = 12, shortlisted = 3, rejected = 2, visits = 27, streets = 9), server.api().stats())
        val request = server.seen.single()
        assertEquals("GET", request.method)
        assertEquals("$BASE/api/stats", request.url)
        assertEquals(listOf(KEY), request.headers.getAll("X-API-Key"))
        assertTrue(server.sleeps.isEmpty())
        assertTrue(server.logs.isEmpty())
    }

    @Test
    fun housesSinceParsesRecordedPayloadAndIgnoresServerOnlyFields() = runTest {
        val server = FakeServer(json(200, R.HOUSES_SINCE))
        val houses = server.api().housesSince(40)
        assertEquals("$BASE/api/houses?since=40", server.seen.single().url)
        assertEquals(2, houses.size)

        val flat = houses[0]
        assertEquals("5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10", flat.id)
        assertEquals("SHORTLISTED", flat.status)
        assertEquals(32_000L, flat.price)
        assertEquals(mapOf("water" to 5, "parking" to 4, "noise" to 2), flat.checklist)
        assertNull(flat.contactName)
        assertEquals(41L, flat.syncVersion)
        // Microsecond timestamps from Postgres are truncated to milliseconds.
        assertEquals(IsoTime.parseMillis("2026-09-21T17:02:44.901Z"), IsoTime.parseMillis(flat.updatedAt!!))

        val villa = houses[1]
        assertTrue(villa.deleted)
        assertTrue(villa.checklist.isEmpty())
        // A status this app version does not know falls back to NEW in the mapper, as before.
        assertEquals(HouseStatus.NEW, HouseStatus.fromWire(villa.status))
    }

    @Test
    fun visitsAndPhotoChangesParse() = runTest {
        val server = FakeServer(json(200, R.VISITS_SINCE), json(200, R.PHOTOS_SINCE))
        val api = server.api()
        val visit = api.visitsSince(0).single()
        assertEquals(VisitSource.AUTO, VisitSource.fromWire(visit.source))
        assertEquals(IsoTime.parseMillis("2026-09-21T11:05:00.250Z"), IsoTime.parseMillis(visit.arrivedAt))

        val photos = api.photoChangesSince(6)
        assertEquals(listOf(false, true), photos.map { it.deleted })
        assertEquals(listOf(7L, 8L), photos.map { it.syncVersion })
        assertEquals(listOf("$BASE/api/visits?since=0", "$BASE/api/photos?since=6"), server.seen.map { it.url })
    }

    @Test
    fun putHouseSendsTheSameJsonAsBefore() = runTest {
        val server = FakeServer(json(200, R.HOUSE_PUT_ECHO))
        val dto = HouseDto(
            id = "h-1", label = "Flat", street = "MG Road", lat = 12.97, lon = 77.59, status = "NEW",
            priceType = "RENT", checklist = mapOf("water" to 3),
            createdAt = IsoTime.format(1_790_072_130_000), updatedAt = IsoTime.format(1_790_072_130_120),
        )
        val echo = server.api().putHouse(dto)
        assertEquals(43L, echo.syncVersion)

        val request = server.seen.single()
        assertEquals("PUT", request.method)
        assertEquals("$BASE/api/houses/h-1", request.url)
        assertEquals("application/json; charset=utf-8", request.contentType?.lowercase())
        // Same Json settings as v0.1: nulls and default values (status NEW, deleted false, syncVersion 0) are omitted.
        assertEquals(
            """{"id":"h-1","label":"Flat","street":"MG Road","lat":12.97,"lon":77.59,"priceType":"RENT",""" +
                """"checklist":{"water":3},"createdAt":"2026-09-22T10:15:30Z","updatedAt":"2026-09-22T10:15:30.120Z"}""",
            request.text,
        )
    }

    @Test
    fun putVisitSendsIsoTimestamps() = runTest {
        val server = FakeServer(json(200, R.VISITS_SINCE.removePrefix("[").removeSuffix("]")))
        val dto = VisitDto(
            id = "v-1", houseId = "h-1", lat = 1.5, lon = 2.5, arrivedAt = IsoTime.format(1_790_072_130_000),
            source = "AUTO", updatedAt = IsoTime.format(1_790_072_130_001),
        )
        server.api().putVisit(dto)
        assertEquals("$BASE/api/visits/v-1", server.seen.single().url)
        assertEquals(
            """{"id":"v-1","houseId":"h-1","lat":1.5,"lon":2.5,"arrivedAt":"2026-09-22T10:15:30Z","source":"AUTO",""" +
                """"updatedAt":"2026-09-22T10:15:30.001Z"}""",
            server.seen.single().text,
        )
    }

    @Test
    fun baseUrlTrailingSlashAndPathPrefixAreKept() = runTest {
        val server = FakeServer(json(200, R.STATS))
        server.api(baseUrl = "https://example.org/hunt/").stats()
        assertEquals("https://example.org/hunt/api/stats", server.seen.single().url)
    }

    @Test
    fun aiEndpointsParseAndIgnoreUnknownFields() = runTest {
        val server = FakeServer(json(200, R.AI_STATUS_OFF), json(200, R.ASK_RESPONSE))
        val api = server.api()
        assertFalse(api.aiStatus().enabled)
        val answer = api.ask("Which house has 24x7 water?")
        assertTrue(answer.grounded)
        assertEquals("5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10", answer.citations.single().houseId)
        assertEquals("POST", server.seen[1].method)
        assertEquals("""{"question":"Which house has 24x7 water?"}""", server.seen[1].text)
    }

    @Test
    fun uploadPhotoSendsMultipartWithIdAndJpegPart() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00)
        val server = FakeServer(json(201, R.PHOTO_UPLOADED))
        server.api().uploadPhoto("h-1", "p-1", "p-1.jpg", jpeg)

        val request = server.seen.single()
        assertEquals("POST", request.method)
        assertEquals("$BASE/api/houses/h-1/photos", request.url)
        assertTrue(request.contentType!!.startsWith("multipart/form-data; boundary="), request.contentType)
        val body = request.body.decodeToString(0, request.body.size, throwOnInvalidSequence = false).lowercase()
        assertTrue("content-disposition: form-data; name=\"id\"" in body, body)
        assertTrue("\r\n\r\np-1\r\n" in body, body)
        assertTrue("content-disposition: form-data; name=\"file\"; filename=\"p-1.jpg\"" in body, body)
        assertTrue("content-type: image/jpeg" in body, body)
        assertTrue(request.body.asList().windowed(jpeg.size).any { it == jpeg.asList() }, "JPEG bytes not in body")
    }

    @Test
    fun downloadPhotoReturnsTheImageBytes() = runTest {
        val jpeg = byteArrayOf(1, 2, 3, 4)
        val server = FakeServer(Reply.Http(200, jpeg, listOf("Content-Type" to "image/jpeg")))
        assertContentEquals(jpeg, server.api().downloadPhoto("p-1"))
        assertEquals("$BASE/api/photos/p-1", server.seen.single().url)
    }

    @Test
    fun deletePhotoAcceptsNoContentAndIgnoresNotFound() = runTest {
        val server = FakeServer(empty(204), problem(404, R.NOT_FOUND))
        val api = server.api()
        api.deletePhoto("p-1")
        api.deletePhoto("p-9") // deleting twice, or a photo the server never had, is fine
        assertEquals(listOf("DELETE", "DELETE"), server.seen.map { it.method })
    }

    // ---------------------------------------------------------------- status mapping (error bodies are never read)

    @Test
    fun unauthorizedIsAuthAndNotRetried() = runTest {
        val server = FakeServer(problem(401, R.UNAUTHORIZED))
        val e = assertFailsWith<ApiException> { server.api().stats() }
        assertEquals(ApiException.Kind.AUTH, e.kind)
        assertEquals(401, e.code)
        assertNull(e.retryAfterSeconds)
        assertEquals("HTTP 401 (AUTH)", e.message)
        assertEquals(1, server.seen.size)
        assertEquals(SyncOutcome.Kind.AUTH, SyncOutcome.fromError(e).kind)
        // Debug log: status, method and path only; never the key or the server's text.
        assertEquals(listOf("HTTP 401 for GET /api/stats"), server.logs)
    }

    @Test
    fun forbiddenIsAuthToo() = runTest {
        val server = FakeServer(problem(403, """{"status":403,"detail":"Forbidden"}"""))
        assertEquals(ApiException.Kind.AUTH, assertFailsWith<ApiException> { server.api().stats() }.kind)
    }

    @Test
    fun clientErrorsMapToTheirKinds() = runTest {
        val server = FakeServer(
            problem(404, R.NOT_FOUND), problem(409, R.CONFLICT), problem(400, R.BAD_REQUEST), problem(413, R.TOO_LARGE),
        )
        val api = server.api()
        val dto = HouseDto(id = "h-1", label = "x", lat = 0.0, lon = 0.0)
        assertEquals(ApiException.Kind.NOT_FOUND, assertFailsWith<ApiException> { api.downloadPhoto("p-9") }.kind)
        assertEquals(ApiException.Kind.CONFLICT,
            assertFailsWith<ApiException> { api.uploadPhoto("h-1", "p-2", "p-2.jpg", ByteArray(1)) }.kind)
        assertEquals(ApiException.Kind.CLIENT, assertFailsWith<ApiException> { api.putHouse(dto) }.kind)
        val tooLarge = assertFailsWith<ApiException> { api.extractListing("x".repeat(10)) }
        assertEquals(ApiException.Kind.CLIENT, tooLarge.kind)
        assertEquals(413, tooLarge.code)
        assertEquals(4, server.seen.size) // 4xx other than 408/429 are never retried
    }

    @Test
    fun serverErrorWithoutRetryCodeIsNotRetried() = runTest {
        val server = FakeServer(problem(500, """{"type":"about:blank","title":"Internal Server Error","status":500}"""))
        val e = assertFailsWith<ApiException> { server.api().deletePhoto("p-1") }
        assertEquals(ApiException.Kind.SERVER, e.kind)
        assertEquals(500, e.code)
        assertEquals(1, server.seen.size)
    }

    @Test
    fun aiProviderDownIsAiUnavailable() = runTest {
        val server = FakeServer(problem(503, R.AI_UNAVAILABLE, "Retry-After" to "60"))
        val e = assertFailsWith<ApiException> { server.api().ask("anything?") }
        assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind)
        assertEquals(60L, e.retryAfterSeconds)
        assertEquals(1, server.seen.size) // POST: never retried
    }

    @Test
    fun aiStatus503WithLongRetryAfterIsReturnedNotWaitedFor() = runTest {
        val server = FakeServer(problem(503, R.AI_UNAVAILABLE, "Retry-After" to "60"))
        val e = assertFailsWith<ApiException> { server.api().aiStatus() }
        assertEquals(ApiException.Kind.AI_UNAVAILABLE, e.kind)
        assertEquals(1, server.seen.size) // 60 s is longer than the 15 s cap: handed back to the caller
        assertTrue(server.sleeps.isEmpty())
    }

    @Test
    fun the503AiRuleUsesTheUrlPathAsBefore() = runTest {
        // Unchanged quirk of the OkHttp client: the check is on the full URL path, so a base URL with a path
        // prefix reports a 503 from the AI endpoints as SERVER.
        val server = FakeServer(problem(503, R.AI_UNAVAILABLE))
        val e = assertFailsWith<ApiException> { server.api(baseUrl = "https://example.org/hunt").ask("q") }
        assertEquals(ApiException.Kind.SERVER, e.kind)
    }

    // ---------------------------------------------------------------- rate limiting and Retry-After

    @Test
    fun shortRetryAfterIsHonouredThenRetried() = runTest {
        val server = FakeServer(problem(429, R.RATE_LIMITED, "Retry-After" to "2"), json(200, R.STATS))
        server.api().stats()
        assertEquals(2, server.seen.size)
        assertEquals(listOf(2_000L), server.sleeps)
    }

    @Test
    fun longRetryAfterIsReportedAsRateLimited() = runTest {
        val server = FakeServer(problem(429, R.AUTH_LOCKOUT, "Retry-After" to "300"))
        val e = assertFailsWith<ApiException> { server.api().housesSince(0) }
        assertEquals(ApiException.Kind.RATE_LIMITED, e.kind)
        assertEquals(429, e.code)
        assertEquals(300L, e.retryAfterSeconds)
        assertEquals(1, server.seen.size)
        assertEquals(SyncOutcome.Kind.RATE_LIMITED, SyncOutcome.fromError(e).kind)
    }

    @Test
    fun rateLimitedPostIsNotRetried() = runTest {
        val server = FakeServer(json(429, """{"status":429,"detail":"AI rate limit exceeded, retry in 30s"}""", "Retry-After" to "30"))
        val e = assertFailsWith<ApiException> { server.api().planVisits(PlanRequest("route", 12.9, 77.6)) }
        assertEquals(ApiException.Kind.RATE_LIMITED, e.kind)
        assertEquals(30L, e.retryAfterSeconds)
        assertEquals(1, server.seen.size)
    }

    // ---------------------------------------------------------------- retries with backoff

    @Test
    fun retriesGatewayErrorsThenSucceeds() = runTest {
        val server = FakeServer(html(503, R.BAD_GATEWAY_HTML), html(502, R.BAD_GATEWAY_HTML), json(200, R.STATS))
        server.api().stats()
        assertEquals(3, server.seen.size)
        assertEquals(2, server.sleeps.size)
        assertTrue(server.sleeps[0] in 0..1_000)
        assertTrue(server.sleeps[1] in 0..2_000)
    }

    @Test
    fun givesUpAfterThreeAttempts() = runTest {
        val server = FakeServer(html(502, R.BAD_GATEWAY_HTML))
        val e = assertFailsWith<ApiException> { server.api().stats() }
        assertEquals(ApiException.Kind.SERVER, e.kind)
        assertEquals(502, e.code)
        assertEquals(3, server.seen.size)
        assertEquals(2, server.sleeps.size)
    }

    @Test
    fun retriesNetworkErrorsForIdempotentCalls() = runTest {
        val server = FakeServer(reset(), json(200, R.HOUSE_PUT_ECHO))
        server.api().putHouse(HouseDto(id = "h-1", label = "Flat", lat = 12.97, lon = 77.59))
        assertEquals(2, server.seen.size)
        assertEquals(server.seen[0].text, server.seen[1].text) // the same body is sent again
    }

    @Test
    fun networkErrorsSurfaceAsIOExceptionAfterRetries() = runTest {
        val server = FakeServer(reset())
        val e = assertFailsWith<IOException> { server.api().stats() }
        assertFalse(e is ApiException)
        assertEquals(3, server.seen.size)
        assertEquals(SyncOutcome.Kind.NETWORK, SyncOutcome.fromError(e).kind)
    }

    @Test
    fun plainPostsAreNotRetriedButThePhotoUploadIs() = runTest {
        val ask = FakeServer(reset(), json(200, R.ASK_RESPONSE))
        assertFailsWith<IOException> { ask.api().ask("q") }
        assertEquals(1, ask.seen.size)

        val upload = FakeServer(reset(), json(201, R.PHOTO_UPLOADED))
        upload.api().uploadPhoto("h-1", "p-1", "p-1.jpg", byteArrayOf(9, 8, 7))
        assertEquals(2, upload.seen.size)
        // Each attempt carries a complete multipart body (same parts; the boundary may differ).
        assertTrue(upload.seen.all { "filename=\"p-1.jpg\"" in it.body.decodeToString(throwOnInvalidSequence = false) })
        assertEquals(upload.seen[0].body.size, upload.seen[1].body.size)
    }

    @Test
    fun streamedUploadOpensTheFileOncePerAttemptClosesItAndSendsTheSameBody() = runTest {
        val jpeg = ByteArray(3000) { (it * 31).toByte() }
        val sources = mutableListOf<TrackedSource>()
        val streamed = FakeServer(reset(), json(201, R.PHOTO_UPLOADED))
        streamed.api().uploadPhoto("h-1", "p-1", "p-1.jpg", jpeg.size.toLong()) {
            TrackedSource(jpeg).also { sources += it }.buffered()
        }
        val inMemory = FakeServer(json(201, R.PHOTO_UPLOADED))
        inMemory.api().uploadPhoto("h-1", "p-1", "p-1.jpg", jpeg)

        assertEquals(2, streamed.seen.size)
        assertEquals(2, sources.size, "one fresh source per attempt")
        assertTrue(sources.all { it.closed }, "every source is closed")
        val sent = streamed.seen.last()
        val reference = inMemory.seen.single()
        assertEquals(reference.body.size, sent.body.size)
        for (request in listOf(sent, reference)) {
            val body = request.body.decodeToString(0, request.body.size, throwOnInvalidSequence = false).lowercase()
            assertTrue("content-length: 3000" in body, body)
            assertTrue(request.body.asList().windowed(jpeg.size).any { it == jpeg.asList() }, "JPEG bytes not in body")
        }
    }

    /** A file stand-in that records whether the client closed it. */
    private class TrackedSource(data: ByteArray) : RawSource {
        private val buffer = Buffer().apply { write(data) }
        var closed = false
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = buffer.readAtMostTo(sink, byteCount)
        override fun close() {
            closed = true
        }
    }

    // ---------------------------------------------------------------- captive portals and redirects

    @Test
    fun htmlSignInPageIsACaptivePortal() = runTest {
        val server = FakeServer(html(200, R.CAPTIVE_PORTAL_HTML))
        val e = assertFailsWith<ApiException> { server.api().stats() }
        assertEquals(ApiException.Kind.CAPTIVE_PORTAL, e.kind)
        assertEquals(200, e.code)
        assertEquals(1, server.seen.size)
        assertEquals(SyncOutcome.Kind.CAPTIVE_PORTAL, SyncOutcome.fromError(e).kind)
    }

    @Test
    fun redirectsAreNeverFollowed() = runTest {
        val server = FakeServer(
            html(302, "", "Location" to "http://10.10.0.1/login"),
            empty(307, "Location" to "https://evil.example/api/houses/h-1"),
        )
        val api = server.api()
        val get = assertFailsWith<ApiException> { api.stats() }
        assertEquals(ApiException.Kind.CAPTIVE_PORTAL, get.kind)
        assertEquals(302, get.code)
        val put = assertFailsWith<ApiException> { api.putHouse(HouseDto(id = "h-1", label = "x", lat = 0.0, lon = 0.0)) }
        assertEquals(ApiException.Kind.CAPTIVE_PORTAL, put.kind)
        assertEquals(307, put.code)
        // Only the two original requests: the API key never went to the redirect target.
        assertEquals(listOf("$BASE/api/stats", "$BASE/api/houses/h-1"), server.seen.map { it.url })
    }

    @Test
    fun jsonCallsNeedAJsonContentType() = runTest {
        val server = FakeServer(
            Reply.Http(200, R.STATS.encodeToByteArray(), emptyList()),
            Reply.Http(200, R.STATS.encodeToByteArray(), listOf("Content-Type" to "text/plain")),
            Reply.Http(200, R.STATS.encodeToByteArray(), listOf("Content-Type" to "Application/JSON;charset=UTF-8")),
        )
        val api = server.api()
        assertEquals(ApiException.Kind.CAPTIVE_PORTAL, assertFailsWith<ApiException> { api.stats() }.kind)
        assertEquals(ApiException.Kind.CAPTIVE_PORTAL, assertFailsWith<ApiException> { api.stats() }.kind)
        assertEquals(12L, api.stats().houses) // media type check is case-insensitive
    }

    @Test
    fun photoDownloadNeedsAnImage() = runTest {
        val server = FakeServer(html(200, R.CAPTIVE_PORTAL_HTML))
        val e = assertFailsWith<ApiException> { server.api().downloadPhoto("p-1") }
        assertEquals(ApiException.Kind.CAPTIVE_PORTAL, e.kind)
    }

    // ---------------------------------------------------------------- overall call timeout

    @Test
    fun wholeCallIsTimeLimited() = runTest {
        val server = FakeServer(Reply.Hang(60_000))
        val e = assertFailsWith<ApiTimeoutException> { server.api(callTimeoutMs = 100).stats() }
        assertEquals(SyncOutcome.Kind.NETWORK, SyncOutcome.fromError(e).kind)
    }
}
