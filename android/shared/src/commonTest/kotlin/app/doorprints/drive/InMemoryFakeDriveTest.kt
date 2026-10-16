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

package app.doorprints.drive

import app.doorprints.shared.api.ApiHttp
import app.doorprints.shared.sync.SyncOutcome
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fake Drive itself (S4b-BL-115, docs/06 TC-U-124): the fault script does exactly what it says, the hand edits act
 * like a person in Drive, and the rules Drive enforces (chunk alignment, page tokens, the bin) hold, so the later
 * tickets can trust it. Plus what only [HttpDriveClient] does: the token goes only to Google over https, redirects are
 * not followed, a non-JSON answer is corrupt, and [SyncOutcome.fromError] reads the kinds.
 */
class InMemoryFakeDriveTest {

    private fun bytes(n: Int) = ByteArray(n) { (it % 7).toByte() }

    @Test
    fun theScriptFailsExactlyTheRequestsItNames() = runTest {
        val server = FakeDriveServer()
        val drive = InMemoryFakeDrive(server, retry = DriveRetry(maxAttempts = 1))
        server.faults.onCall(3, DriveFault.Server(503))
        drive.about()
        drive.about()
        assertEquals(503, assertFailsWith<DriveException> { drive.about() }.httpStatus)
        drive.about()
        server.faults.on(DriveOp.LIST, 2, DriveFault.Forbidden)
        drive.list(DriveQuery())
        assertEquals(DriveException.Kind.FORBIDDEN, assertFailsWith<DriveException> { drive.list(DriveQuery()) }.kind)
        server.faults.next(DriveFault.Conflict, DriveOp.ABOUT, times = 2)
        drive.list(DriveQuery())
        repeat(2) { assertFailsWith<DriveException> { drive.about() } }
        drive.about()
        server.faults.always(DriveFault.Cancelled, DriveOp.GET)
        repeat(3) { assertEquals(DriveException.Kind.CANCELLED, assertFailsWith<DriveException> { drive.getFile("x") }.kind) }
        drive.about()
        server.faults.clear().stopAfter(server.requests.size + 1)
        drive.about()
        assertEquals(DriveException.Kind.OFFLINE, assertFailsWith<DriveException> { drive.about() }.kind)
        assertEquals(DriveOp.ABOUT, server.requests.first().first)
    }

    @Test
    fun theBinIsInheritedAndAFolderDeleteTakesEverything() = runTest {
        val server = FakeDriveServer()
        val drive = InMemoryFakeDrive(server)
        val root = drive.ensureFolder(DriveLayout.ROOT, null, create = true)!!
        val sync = drive.ensureFolder(DriveLayout.SYNC, root.id, create = true)!!
        val file = drive.upload(UploadTarget.New(NewFile("s", "application/octet-stream", listOf(sync.id))), bytes(10))
        server.trashByHand(root.id)
        assertTrue(drive.getFile(file.id).trashed, "in the bin through its folder")
        assertTrue(drive.listAll(DriveQuery(sync.id)).isEmpty())
        server.untrashByHand(root.id)
        assertEquals(listOf(file.id), drive.listAll(DriveQuery(sync.id)).map { it.id })
        server.deleteByHand(root.id)
        assertTrue(server.allFiles().isEmpty())
    }

    @Test
    fun driveRulesHold() = runTest {
        val server = FakeDriveServer()
        val drive = InMemoryFakeDrive(server)
        assertEquals(DriveException.Kind.BAD_REQUEST, assertFailsWith<DriveException> { drive.list(DriveQuery(), "bogus") }.kind)
        assertEquals(
            DriveException.Kind.NOT_FOUND,
            assertFailsWith<DriveException> { drive.createFile(NewFile("x", FOLDER_MIME, listOf("nope"))) }.kind,
        )
        val session = drive.startUpload(UploadTarget.New(NewFile("big", "application/octet-stream")), 3L * DriveClient.CHUNK_UNIT)
        assertEquals(
            DriveException.Kind.BAD_REQUEST,
            assertFailsWith<DriveException> { drive.uploadChunk(session, 0, bytes(1000)) }.kind,
            "a chunk that is not the last is a multiple of 256 KiB",
        )
        assertEquals(UploadProgress.Incomplete(0), drive.uploadStatus(session))
        assertEquals(UploadProgress.Incomplete(DriveClient.CHUNK_UNIT.toLong()), drive.uploadChunk(session, 0, bytes(DriveClient.CHUNK_UNIT)))
        // A resent chunk that overlaps is taken from where Drive is.
        assertEquals(
            UploadProgress.Incomplete(2L * DriveClient.CHUNK_UNIT),
            drive.uploadChunk(session, 0, bytes(2 * DriveClient.CHUNK_UNIT)),
        )
        val done = drive.uploadChunk(session, 2L * DriveClient.CHUNK_UNIT, bytes(DriveClient.CHUNK_UNIT))
        assertTrue(done is UploadProgress.Complete)
        assertEquals(done, drive.uploadStatus(session), "a finished session answers with the file")
        val folder = drive.createFile(NewFile("f", FOLDER_MIME))
        assertEquals(DriveException.Kind.FORBIDDEN, assertFailsWith<DriveException> { drive.download(folder.id) }.kind)
        val small = drive.upload(UploadTarget.New(NewFile("s", "text/plain")), bytes(5))
        assertEquals(DriveException.Kind.BAD_REQUEST, assertFailsWith<DriveException> { drive.download(small.id, 5L..9L) }.kind)
        assertContentEquals(bytes(5).copyOfRange(3, 5), drive.download(small.id, 3L..99L))
    }

    @Test
    fun handEditsAndTheClockShowInTheMetadata() = runTest {
        val server = FakeDriveServer()
        val drive = InMemoryFakeDrive(server)
        val file = drive.upload(UploadTarget.New(NewFile("a", "text/plain", appProperties = mapOf("kind" to "sync"))), bytes(3))
        assertEquals(server.clock.now(), file.modifiedTime)
        server.clock.advance(60_000)
        server.editByHand(file.id, bytes(4))
        val edited = drive.getFile(file.id)
        assertEquals(file.modifiedTime + 60_000, edited.modifiedTime)
        assertEquals(sha256HexOf(bytes(4)), edited.sha256Checksum)
        server.renameByHand(file.id, "renamed")
        server.moveByHand(file.id, null)
        assertEquals("renamed", drive.getFile(file.id).name)
        assertEquals(listOf(file.id), drive.listAll(DriveQuery(appProperties = mapOf("kind" to "sync"))).map { it.id }, "found by properties, not name")
        assertEquals(2, drive.revisions(file.id).size)
        assertTrue(server.requests.none { it.first == DriveOp.UPDATE }, "hand edits are not requests")
    }

    @Test
    fun theHttpClientSendsTheTokenOnlyToGoogleOverHttps() = runTest {
        val server = FakeDriveServer()
        val http = FakeDriveHttp(server)
        val drive = HttpDriveClient(ApiHttp.client(http.engine), FakeTokenProvider(), DriveRetry(maxAttempts = 1))
        val insecure = UploadSession("http://www.googleapis.com/upload/drive/v3/files?upload_id=x", 10)
        val elsewhere = UploadSession("https://evil.example/upload?upload_id=x", 10)
        for (s in listOf(insecure, elsewhere)) {
            assertEquals(DriveException.Kind.CORRUPT, assertFailsWith<DriveException> { drive.uploadChunk(s, 0, bytes(10)) }.kind)
        }
        assertTrue(http.seen.isEmpty(), "nothing was sent")
        assertTrue("x" !in insecure.toString(), "a session URI is never printed")
        assertTrue(HttpDriveClient.isGoogleApi("https://www.googleapis.com/drive/v3/files"))
        assertTrue(!HttpDriveClient.isGoogleApi("https://www.googleapis.com.evil.example/x"))
        assertTrue(!HttpDriveClient.isGoogleApi("https://www.googleapis.com:8443/x"))
        drive.about()
        assertEquals("Bearer token-1", http.seen.single().headers["Authorization"])
    }

    @Test
    fun aRedirectIsNotFollowedAndANonJsonAnswerIsCorrupt() = runTest {
        var answer = 0
        val engine = MockEngine {
            answer++
            if (answer == 1) respond("", HttpStatusCode.Found, headersOf("Location", "https://portal.example/"))
            else respond("<html>sign in</html>", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
        }
        val drive = HttpDriveClient(ApiHttp.client(engine), FakeTokenProvider(), DriveRetry(maxAttempts = 1))
        assertEquals(DriveException.Kind.OFFLINE, assertFailsWith<DriveException> { drive.about() }.kind)
        assertEquals(DriveException.Kind.CORRUPT, assertFailsWith<DriveException> { drive.about() }.kind)
        assertEquals(2, answer)
    }

    @Test
    fun syncOutcomeReadsTheDriveKinds() {
        fun kind(k: DriveException.Kind, status: Int = 0) = SyncOutcome.fromError(DriveException(k, status)).kind
        assertEquals(SyncOutcome.Kind.AUTH, kind(DriveException.Kind.UNAUTHORIZED, 401))
        assertEquals(SyncOutcome.Kind.AUTH, kind(DriveException.Kind.FORBIDDEN, 403))
        assertEquals(SyncOutcome.Kind.RATE_LIMITED, kind(DriveException.Kind.RATE_LIMITED, 429))
        assertEquals(SyncOutcome.Kind.NETWORK, kind(DriveException.Kind.OFFLINE))
        assertEquals(SyncOutcome.Kind.SERVER, kind(DriveException.Kind.QUOTA_EXCEEDED, 403))
        assertEquals(503, SyncOutcome.fromError(DriveException(DriveException.Kind.SERVER, 503)).httpCode)
        assertNull(DriveException(DriveException.Kind.SERVER).retryAfterMs)
    }
}
