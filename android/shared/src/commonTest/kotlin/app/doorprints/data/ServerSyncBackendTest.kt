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

package app.doorprints.data

import app.doorprints.shared.api.ApiClient
import app.doorprints.shared.api.ApiException
import app.doorprints.shared.api.ApiHttp
import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.api.PhotoMetaDto
import app.doorprints.shared.api.RecordedResponses
import app.doorprints.shared.api.RetryPolicy
import app.doorprints.shared.sync.SyncOutcome
import app.doorprints.shared.sync.SyncRules
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * S4b-BL-70: [ServerSyncBackend] is the server's calls moved out of `CommonRepository.sync` unchanged, so it still maps
 * the server's answers exactly as the loop did: the stats check, the permanent photo refusals kept on the phone, the
 * meta refusals, and every other HTTP failure or a lost connection thrown for [SyncOutcome.fromError] to classify.
 * Against Ktor's MockEngine; no network, no waits (the retry policy's sleep does nothing).
 */
class ServerSyncBackendTest {

    /** Answers every request with [status] and [body], or throws [fail] (a lost connection); records the requests. */
    private class Server(val status: Int = 200, val body: String = "[]", val fail: Throwable? = null) {
        val seen = mutableListOf<String>()
        private val engine = MockEngine { request ->
            seen += "${request.method.value} ${request.url.encodedPath}"
            fail?.let { throw it }
            respond(body, HttpStatusCode.fromValue(status), Headers.build { append("Content-Type", "application/json") })
        }
        val backend = ServerSyncBackend(
            ApiClient(
                "https://sync.example", "test-key", ApiHttp.client(engine),
                retry = RetryPolicy(random = Random(1), sleep = {}), callTimeoutMs = null,
            ),
        )
    }

    private val meta = PhotoMetaDto(caption = "Kitchen", metaUpdatedAt = 1_760_000_000_000)

    @Test
    fun theMergeRuleIsTheServersKeepLocal() {
        assertSame(SyncRules.serverMerge, Server().backend.mergeRule)
        val clean = HouseEntity(id = "h", label = "", lat = 0.0, lon = 0.0, createdAt = 1, updatedAt = 200, dirty = false)
        val older = clean.copy(updatedAt = 100)
        // A clean local row takes the server's, even an older one: the server has already merged.
        assertFalse(SyncRules.serverMerge.keepLocal(clean, older))
        assertTrue(SyncRules.serverMerge.keepLocal(clean.copy(dirty = true), older))
    }

    @Test
    fun isBehindReadsTheServersHighestVersion() = runTest {
        val server = Server(body = RecordedResponses.STATS_WITH_MAX_VERSION) // maxSyncVersion 418
        assertTrue(server.backend.isBehind(listOf(500, 0)))
        assertFalse(server.backend.isBehind(listOf(418, 10)))
        assertEquals("GET /api/stats", server.seen.first())
        // An older server without the field is unknown: not behind.
        assertFalse(Server(body = RecordedResponses.STATS).backend.isBehind(listOf(500)))
    }

    @Test
    fun aFailedStatsCallIsUnknownNotAFailure() = runTest {
        assertFalse(Server(status = 500, body = "{}").backend.isBehind(listOf(500)))
        assertFalse(Server(fail = IOException("offline")).backend.isBehind(listOf(500)))
    }

    @Test
    fun permanentPhotoRefusalsAreKeptOnThePhone() = runTest {
        for (status in listOf(400, 404, 409)) {
            Server(status = status, body = "{}").backend.uploadPhoto("h", "p", "p.jpg", 1) { Buffer().apply { writeByte(1) } }
        }
    }

    @Test
    fun otherPhotoUploadFailuresEndTheSync() = runTest {
        val server = Server(status = 500, body = "{}")
        val e = assertFailsWith<ApiException> {
            server.backend.uploadPhoto("h", "p", "p.jpg", 1) { Buffer().apply { writeByte(1) } }
        }
        assertEquals(SyncOutcome.Kind.SERVER, SyncOutcome.fromError(e).kind)
        val auth = assertFailsWith<ApiException> {
            Server(status = 401, body = "{}").backend.uploadPhoto("h", "p", "p.jpg", 1) { Buffer() }
        }
        assertEquals(SyncOutcome.Kind.AUTH, SyncOutcome.fromError(auth).kind)
    }

    @Test
    fun aRefusedPhotoMetaIsNullAndOtherFailuresAreThrown() = runTest {
        assertNull(Server(status = 404, body = "{}").backend.pushPhotoMeta("p", meta))
        assertNull(Server(status = 400, body = "{}").backend.pushPhotoMeta("p", meta))
        // A conflict was never a meta refusal: it ends the sync, as before the seam.
        assertFailsWith<ApiException> { Server(status = 409, body = "{}").backend.pushPhotoMeta("p", meta) }
        assertFailsWith<ApiException> { Server(status = 500, body = "{}").backend.pushPhotoMeta("p", meta) }
    }

    @Test
    fun httpErrorsAndALostConnectionAreClassifiedAsBefore() = runTest {
        val auth = assertFailsWith<ApiException> { Server(status = 401, body = "{}").backend.housesSince(0) }
        assertEquals(SyncOutcome.Kind.AUTH, SyncOutcome.fromError(auth).kind)
        val limited = assertFailsWith<ApiException> { Server(status = 429, body = "{}").backend.visitsSince(0) }
        assertEquals(SyncOutcome.Kind.RATE_LIMITED, SyncOutcome.fromError(limited).kind)
        val server = assertFailsWith<ApiException> {
            Server(status = 503, body = "{}").backend.pushHouse(HouseDto(id = "h", label = "Flat", lat = 0.0, lon = 0.0))
        }
        assertEquals(SyncOutcome.Kind.SERVER, SyncOutcome.fromError(server).kind)
        val offline = assertFailsWith<IOException> { Server(fail = IOException("offline")).backend.recordsSince(0) }
        assertEquals(SyncOutcome.Kind.NETWORK, SyncOutcome.fromError(offline).kind)
    }

    @Test
    fun theCallsGoToTheSameEndpoints() = runTest {
        val server = Server()
        server.backend.housesSince(7)
        server.backend.visitsSince(7)
        server.backend.recordsSince(7)
        server.backend.photoChangesSince(7)
        assertEquals(listOf("GET /api/houses", "GET /api/visits", "GET /api/records", "GET /api/photos"), server.seen)
    }
}
