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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [DriveClient] contract (S4b-BL-115, docs/06 TC-U-124): every case runs twice, on [InMemoryFakeDrive] and on
 * [HttpDriveClient] over Ktor's MockEngine with the same fake behind Drive's HTTP surface ([FakeDriveHttp]), and must
 * give the same answer, so later tickets can test on the fake and trust the real client (web: `drive-contract.spec.ts`).
 * Both wait on the server's clock with the middle of the jitter (`random` 0.5), so the waits are exact.
 */
class DriveClientContract {

    private class Subject(val name: String, val server: FakeDriveServer, val tokens: FakeTokenProvider, val drive: DriveClient) {
        val slept get() = server.clock.slept
        val ops get() = server.requests.map { it.first }
    }

    private fun subjects(setup: (FakeDriveServer) -> Unit): List<Subject> = listOf("fake", "http").map { name ->
        val server = FakeDriveServer().also(setup)
        val tokens = FakeTokenProvider()
        val retry = DriveRetry(random = { 0.5 }, sleep = { server.clock.sleep(it) })
        val drive = if (name == "fake") {
            InMemoryFakeDrive(server, tokens, retry)
        } else {
            HttpDriveClient(ApiHttp.client(FakeDriveHttp(server).engine), tokens, retry, now = server.clock::now)
        }
        Subject(name, server, tokens, drive)
    }

    /** Runs [case] on both clients; a failure names the client. */
    private fun both(setup: (FakeDriveServer) -> Unit = {}, case: suspend Subject.() -> Unit) = runTest {
        for (s in subjects(setup)) {
            try {
                s.case()
            } catch (e: AssertionError) {
                throw AssertionError("[${s.name}] ${e.message}", e)
            }
        }
    }

    private suspend fun Subject.root(): DriveFile = drive.ensureFolder(DriveLayout.ROOT, null, create = true)!!

    private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    private fun backup(parent: String, n: Int, state: String = DriveLayout.STATE_COMPLETE, device: String = "d1") = NewFile(
        "Doorprints-backup-$n.dpx", "application/octet-stream", listOf(parent),
        mapOf(DriveLayout.KIND to "backup", DriveLayout.DEVICE to device, DriveLayout.STATE to state, DriveLayout.CREATED_AT to "$n"),
    )

    @Test
    fun theFolderIsFoundByItsPropertiesAndNeverMadeAgainWithoutCreate() = both {
        assertNull(drive.ensureFolder(DriveLayout.ROOT, null, create = false))
        assertTrue(DriveOp.CREATE !in ops)
        val root = root()
        assertEquals("Doorprints", root.name)
        assertEquals(FOLDER_MIME, root.mimeType)
        assertEquals(mapOf("doorprints" to "root"), root.appProperties)
        assertEquals(root.id, root().id, "found again, not made twice")
        assertEquals(root.id, drive.ensureFolder(DriveLayout.ROOT, null, create = false, knownId = root.id)?.id)
        val backups = drive.ensureFolder(DriveLayout.BACKUPS, root.id, create = true)!!
        assertEquals(listOf(root.id), backups.parents)
        // The person deletes the folder: it is not made again quietly (docs/15 §3.4).
        server.deleteByHand(root.id)
        val creates = ops.count { it == DriveOp.CREATE }
        assertNull(drive.ensureFolder(DriveLayout.ROOT, null, create = false, knownId = root.id))
        assertEquals(creates, ops.count { it == DriveOp.CREATE })
        // In the bin counts as gone too.
        val again = root()
        server.trashByHand(again.id)
        assertNull(drive.ensureFolder(DriveLayout.ROOT, null, create = false, knownId = again.id))
    }

    @Test
    fun aLostCreateAnswerLeavesTwoFoldersAndTheOldestIsUsed() = both({ it.faults.on(DriveOp.CREATE, 1, DriveFault.ResponseLost) }) {
        val made = root()
        val all = server.allFiles().filter { it.appProperties == DriveLayout.ROOT.appProperties }
        assertEquals(2, all.size, "the retried create made a second one")
        server.clock.advance(1)
        assertEquals(all.minWith(compareBy({ it.createdTime }, { it.id })).id, root().id)
        assertTrue(made.id in all.map { it.id })
        assertEquals(1, slept.size)
    }

    @Test
    fun listingByAppPropertiesPagesInCreationOrder() = both {
        val root = root()
        val ids = (1..5).map { n ->
            server.clock.advance(10)
            drive.upload(UploadTarget.New(backup(root.id, n, device = if (n % 2 == 0) "d2" else "d1")), bytes(10, n)).id
        }
        server.clock.advance(10)
        drive.upload(UploadTarget.New(backup(root.id, 6, DriveLayout.STATE_PARTIAL)), bytes(3))
        val query = DriveQuery(root.id, mapOf(DriveLayout.KIND to "backup", DriveLayout.STATE to DriveLayout.STATE_COMPLETE))
        val first = drive.list(query, pageSize = 2)
        assertEquals(ids.take(2), first.files.map { it.id })
        assertNotNull(first.nextPageToken)
        val before = ops.count { it == DriveOp.LIST }
        assertEquals(ids, drive.listAll(query, pageSize = 2).map { it.id })
        assertEquals(3, ops.count { it == DriveOp.LIST } - before, "three pages")
        assertEquals(listOf(ids[1], ids[3]), drive.listAll(query.copy(appProperties = query.appProperties + (DriveLayout.DEVICE to "d2"))).map { it.id })
        drive.trash(ids[0])
        assertEquals(ids.drop(1), drive.listAll(query).map { it.id })
        assertEquals(ids, drive.listAll(query.copy(trashed = null)).map { it.id })
        assertEquals(listOf(ids[0]), drive.listAll(query.copy(trashed = true)).map { it.id })
    }

    @Test
    fun aNewFileIsMissingFromListingsForTheLagButGetFileSeesIt() = both({ it.listingLagMs = 1_000 }) {
        val root = root()
        server.clock.advance(1_000)
        val file = drive.upload(UploadTarget.New(backup(root.id, 1)), bytes(5))
        val query = DriveQuery(root.id, mapOf(DriveLayout.KIND to "backup"))
        assertTrue(drive.listAll(query).isEmpty(), "the index has not caught up")
        assertEquals(file.id, drive.getFile(file.id).id)
        server.clock.advance(1_000)
        assertEquals(listOf(file.id), drive.listAll(query).map { it.id })
    }

    @Test
    fun aSmallUploadKeepsItsBytesPropertiesAndChecksum() = both {
        val root = root()
        val content = bytes(1_000)
        val file = drive.upload(UploadTarget.New(backup(root.id, 1, DriveLayout.STATE_PARTIAL)), content)
        assertEquals(1_000L, file.size)
        assertEquals(sha256HexOf(content), file.sha256Checksum)
        assertEquals(DriveLayout.STATE_PARTIAL, file.appProperties[DriveLayout.STATE])
        assertContentEquals(content, drive.download(file.id))
        assertContentEquals(content.copyOfRange(10, 20), drive.download(file.id, 10L..19L))
        assertContentEquals(content, drive.downloadVerified(file.id, sha256HexOf(content)))
        val parts = mutableListOf<ByteArray>()
        drive.downloadTo(file.id, 1_000, chunkSize = DriveClient.CHUNK_UNIT) { parts += it }
        assertContentEquals(content, parts.reduce { a, b -> a + b })
    }

    @Test
    fun aResumableUploadSendsChunksAndResumesAfterADroppedConnection() = both({
        it.faults.on(DriveOp.UPLOAD_CHUNK, 2, DriveFault.DropAfter(100_000))
    }) {
        val root = root()
        val content = bytes(3 * DriveClient.CHUNK_UNIT + 1_000)
        val sessions = mutableListOf<UploadSession>()
        val file = drive.uploadResumable(
            UploadTarget.New(backup(root.id, 1, DriveLayout.STATE_PARTIAL)), content.size.toLong(),
            { offset, length -> content.copyOfRange(offset.toInt(), offset.toInt() + length) },
            chunkSize = DriveClient.CHUNK_UNIT, onSession = { sessions += it },
        )
        assertEquals(1, sessions.size)
        assertEquals(sha256HexOf(content), file.sha256Checksum)
        assertContentEquals(content, server.contentOf(file.id))
        val upload = ops.dropWhile { it != DriveOp.UPLOAD_START }
        assertEquals(
            // The status says 256 KiB + 100 000 bytes arrived; the rest goes in two chunks from there.
            listOf(DriveOp.UPLOAD_START, DriveOp.UPLOAD_CHUNK, DriveOp.UPLOAD_CHUNK, DriveOp.UPLOAD_STATUS,
                DriveOp.UPLOAD_CHUNK, DriveOp.UPLOAD_CHUNK),
            upload,
        )
        assertEquals(listOf(500L), slept)
    }

    @Test
    fun aForgottenSessionStartsOnceAgain() = both {
        val root = root()
        val content = bytes(2 * DriveClient.CHUNK_UNIT)
        var first = true
        val file = drive.uploadResumable(
            UploadTarget.New(backup(root.id, 1)), content.size.toLong(),
            { offset, length ->
                if (offset > 0 && first) {
                    first = false
                    server.expireSessions()
                }
                content.copyOfRange(offset.toInt(), offset.toInt() + length)
            },
            chunkSize = DriveClient.CHUNK_UNIT,
        )
        assertContentEquals(content, server.contentOf(file.id))
        assertEquals(2, ops.count { it == DriveOp.UPLOAD_START })
    }

    @Test
    fun theCompleteMarkerComesOnlyAfterTheChecksumMatches() = both({ it.faults.on(DriveOp.UPLOAD, 2, DriveFault.CorruptContent) }) {
        val root = root()
        val content = bytes(500)
        val good = drive.upload(UploadTarget.New(backup(root.id, 1, DriveLayout.STATE_PARTIAL).copy(name = "partial-a")), content)
        val done = drive.markComplete(good.id, sha256HexOf(content), "Doorprints-backup-a.dpx", good)
        assertEquals(DriveLayout.STATE_COMPLETE, done.appProperties[DriveLayout.STATE])
        assertEquals("Doorprints-backup-a.dpx", done.name)
        assertEquals("backup", done.appProperties[DriveLayout.KIND], "other properties stay")
        val bad = drive.upload(UploadTarget.New(backup(root.id, 2, DriveLayout.STATE_PARTIAL)), content)
        val e = assertFailsWith<DriveException> { drive.markComplete(bad.id, sha256HexOf(content), "x") }
        assertEquals(DriveException.Kind.CORRUPT, e.kind)
        assertEquals(DriveLayout.STATE_PARTIAL, server.fileOrNull(bad.id)!!.appProperties[DriveLayout.STATE])
        assertFailsWith<DriveException> { drive.downloadVerified(bad.id, sha256HexOf(content)) }
    }

    @Test
    fun aChecksumDriveHasNotComputedIsAskedAgain() = both({ it.faults.on(DriveOp.GET, 1, DriveFault.LateChecksum) }) {
        val root = root()
        val content = bytes(64)
        val file = drive.upload(UploadTarget.New(backup(root.id, 1)), content)
        assertEquals(file.id, drive.confirmChecksum(file.id, sha256HexOf(content)).id)
        assertEquals(2, ops.count { it == DriveOp.GET })
        assertEquals(listOf(500L), slept)
    }

    @Test
    fun metadataChangesTouchOnlyWhatTheyName() = both {
        val root = root()
        val other = drive.ensureFolder(DriveLayout.SYNC, root.id, create = true)!!
        val file = drive.upload(UploadTarget.New(backup(root.id, 1)), bytes(4))
        val changed = drive.updateMetadata(
            file.id,
            MetadataChange(appProperties = mapOf(DriveLayout.DEVICE to null, "x" to "1"), addParents = listOf(other.id), removeParents = listOf(root.id)),
        )
        assertEquals(file.name, changed.name)
        assertNull(changed.appProperties[DriveLayout.DEVICE])
        assertEquals("1", changed.appProperties["x"])
        assertEquals("backup", changed.appProperties[DriveLayout.KIND])
        assertEquals(listOf(other.id), changed.parents)
        val tooLong = assertFailsWith<DriveException> { drive.updateMetadata(file.id, MetadataChange(appProperties = mapOf("k" to "v".repeat(124)))) }
        assertEquals(DriveException.Kind.BAD_REQUEST, tooLong.kind)
    }

    @Test
    fun newContentMakesARevisionAndARollBackShowsInThem() = both {
        val root = root()
        val file = drive.upload(UploadTarget.New(backup(root.id, 1)), bytes(10, 1))
        server.clock.advance(5)
        val second = bytes(2 * DriveClient.CHUNK_UNIT, 2)
        val updated = drive.uploadBytes(UploadTarget.Existing(file.id, "application/octet-stream", MetadataChange(name = "n2")), second)
        assertEquals("n2", updated.name)
        assertEquals(sha256HexOf(second), updated.sha256Checksum)
        val revisions = drive.revisions(file.id)
        assertEquals(2, revisions.size)
        assertTrue(revisions[0].modifiedTime < revisions[1].modifiedTime)
        server.rollBack(file.id, revisions[0].id)
        assertEquals(3, drive.revisions(file.id).size)
        assertContentEquals(bytes(10, 1), drive.download(file.id))
    }

    @Test
    fun deletingIsForGoodAndTrashIsTheBin() = both {
        val root = root()
        val photos = drive.ensureFolder(DriveLayout.PHOTOS, root.id, create = true)!!
        val photo = drive.upload(UploadTarget.New(NewFile("p-1.dpx", "application/octet-stream", listOf(photos.id))), bytes(100))
        val old = drive.upload(UploadTarget.New(backup(root.id, 1)), bytes(100))
        val usage = drive.about().quotaUsage
        assertTrue(drive.trash(old.id).trashed)
        assertEquals(usage, drive.about().quotaUsage, "the bin still counts")
        drive.delete(old.id)
        assertNull(server.fileOrNull(old.id))
        drive.delete(old.id) // gone already: done
        drive.delete(root.id)
        assertNull(server.fileOrNull(photo.id), "a folder takes what is in it")
        assertEquals(0, drive.about().quotaUsage)
    }

    @Test
    fun deleteAllStopsAtTheFirstFailureAndSaysWhatIsLeft() = both {
        val root = root()
        val ids = (1..4).map { drive.upload(UploadTarget.New(backup(root.id, it)), bytes(8)).id }
        server.faults.stopAfter(server.requests.size + 2)
        val report = drive.deleteAll(ids)
        assertEquals(ids.take(2), report.deleted)
        assertEquals(ids.drop(2), report.left)
        assertEquals(DriveException.Kind.OFFLINE, report.error?.kind)
        server.faults.clear()
        assertEquals(ids.drop(2), drive.deleteAll(report.left).deleted)
    }

    @Test
    fun aboutGivesTheAccountAndTheQuota() = both({ it.quotaBytes = 1_000 }) {
        val about = drive.about()
        assertEquals("person@example.com", about.email)
        assertEquals(1_000L, about.quotaLimit)
        val root = root()
        drive.upload(UploadTarget.New(backup(root.id, 1)), bytes(600))
        assertEquals(600L, drive.about().quotaUsage)
        val e = assertFailsWith<DriveException> { drive.upload(UploadTarget.New(backup(root.id, 2)), bytes(600)) }
        assertEquals(DriveException.Kind.QUOTA_EXCEEDED, e.kind)
        assertEquals(1, ops.count { it == DriveOp.UPLOAD } - 1, "a full Drive is not asked again")
        assertTrue(slept.isEmpty())
    }

    @Test
    fun anExpiredTokenIsAskedForOnceMore() = both({ it.faults.onCall(1, DriveFault.TokenExpired) }) {
        assertEquals("person@example.com", drive.about().email)
        assertEquals(listOf("token-1"), tokens.rejected)
        assertEquals(listOf("token-1", "token-2"), server.tokensSeen)
        server.refusedTokens += setOf("token-2", "token-3")
        val e = assertFailsWith<DriveException> { drive.about() }
        assertEquals(DriveException.Kind.UNAUTHORIZED, e.kind)
        assertEquals(listOf("token-1", "token-2"), tokens.rejected, "asked once more, then failed")
        assertTrue(slept.isEmpty())
    }

    @Test
    fun rateLimitsWaitForRetryAfterOrTheBackoff() = both({
        it.faults.onCall(1, DriveFault.RateLimited(retryAfterMs = 2_000))
            .onCall(2, DriveFault.RateLimited(as403 = true))
            .onCall(4, DriveFault.RateLimited(retryAfterMs = 120_000))
    }) {
        drive.about()
        assertEquals(listOf(2_000L, 1_000L), slept)
        val e = assertFailsWith<DriveException> { drive.about() }
        assertEquals(DriveException.Kind.RATE_LIMITED, e.kind)
        assertEquals(120_000L, e.retryAfterMs, "too long to hold a worker: back to the caller")
    }

    @Test
    fun serverErrorsAreRetriedFiveTimesWithBackoff() = both({
        it.faults.onCall(3, DriveFault.Server(503))
    }) {
        drive.about()
        drive.about()
        drive.about()
        assertEquals(listOf(500L), slept)
        server.faults.always(DriveFault.Server(500))
        val e = assertFailsWith<DriveException> { drive.about() }
        assertEquals(DriveException.Kind.SERVER, e.kind)
        assertEquals(500, e.httpStatus)
        assertEquals(listOf(500L, 500L, 1_000L, 2_000L, 4_000L), slept)
        assertEquals(4 + 5, server.requests.size)
        server.faults.clear().always(DriveFault.Offline)
        assertEquals(DriveException.Kind.OFFLINE, assertFailsWith<DriveException> { drive.about() }.kind)
    }

    @Test
    fun refusalsAreNotRetried() = both {
        for ((fault, kind) in listOf(
            DriveFault.Forbidden to DriveException.Kind.FORBIDDEN,
            DriveFault.NotFound to DriveException.Kind.NOT_FOUND,
            DriveFault.Conflict to DriveException.Kind.CONFLICT,
            DriveFault.Cancelled to DriveException.Kind.CANCELLED,
        )) {
            server.faults.next(fault)
            assertEquals(kind, assertFailsWith<DriveException> { drive.about() }.kind)
        }
        assertEquals(4, server.requests.size)
        assertTrue(slept.isEmpty())
        assertEquals(DriveException.Kind.NOT_FOUND, assertFailsWith<DriveException> { drive.getFile("nope") }.kind)
        assertEquals(DriveException.Kind.BAD_REQUEST, assertFailsWith<DriveException> { drive.getFile("../x") }.kind)
        assertEquals(5, server.requests.size, "a bad id never leaves the client")
    }

    @Test
    fun anotherWriterBetweenListAndUpdateIsNotUndone() = both {
        val root = root()
        val file = drive.upload(UploadTarget.New(backup(root.id, 1, DriveLayout.STATE_PARTIAL)), bytes(4))
        server.faults.next(DriveFault.Interleave { it.renameByHand(file.id, "renamed by hand") }, DriveOp.UPDATE)
        val done = drive.updateMetadata(file.id, MetadataChange(appProperties = mapOf(DriveLayout.STATE to DriveLayout.STATE_COMPLETE)))
        assertEquals("renamed by hand", done.name)
        assertEquals(DriveLayout.STATE_COMPLETE, done.appProperties[DriveLayout.STATE])
    }

    @Test
    fun twoDevicesWritingAtOnceKeepBothFiles() = runTest {
        val server = FakeDriveServer()
        val a = InMemoryFakeDrive(server)
        val b = HttpDriveClient(ApiHttp.client(FakeDriveHttp(server).engine), FakeTokenProvider(), now = server.clock::now)
        val root = a.ensureFolder(DriveLayout.ROOT, null, create = true)!!
        val sync = b.ensureFolder(DriveLayout.SYNC, root.id, create = true)!!
        val made = listOf(a, b).mapIndexed { i, d ->
            async {
                d.upload(UploadTarget.New(NewFile("device-$i.dpx", "application/octet-stream", listOf(sync.id), mapOf(DriveLayout.KIND to "sync", DriveLayout.DEVICE to "d$i"))), bytes(50, i))
            }
        }.awaitAll()
        val listed = a.listAll(DriveQuery(sync.id, mapOf(DriveLayout.KIND to "sync")))
        assertEquals(made.map { it.id }.toSet(), listed.map { it.id }.toSet())
        // Both write the same file: no compare-and-swap, the last write wins and both are revisions.
        b.upload(UploadTarget.Existing(made[0].id, "application/octet-stream"), bytes(5, 9))
        a.upload(UploadTarget.Existing(made[0].id, "application/octet-stream"), bytes(5, 8))
        assertContentEquals(bytes(5, 8), server.contentOf(made[0].id))
        assertEquals(3, a.revisions(made[0].id).size)
    }
}
