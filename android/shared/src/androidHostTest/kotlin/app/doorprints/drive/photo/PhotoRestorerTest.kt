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
package app.doorprints.drive.photo

import app.doorprints.drive.DriveFault
import app.doorprints.drive.DriveOp
import app.doorprints.drive.delete.AuthorizationGate
import app.doorprints.drive.delete.AuthorizationToken
import app.doorprints.drive.delete.DeletedMarker
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionOutcome
import app.doorprints.drive.delete.DeletionStore
import app.doorprints.drive.delete.DriveDeletionService
import app.doorprints.drive.delete.PendingDeletion
import app.doorprints.drive.delete.PlanResult
import app.doorprints.drive.sync.DriveSyncEngine
import app.doorprints.drive.sync.SyncWorld
import app.doorprints.drive.sync.TestDevice
import app.doorprints.drive.sync.photoRow
import app.doorprints.shared.model.PhotoMeta
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.sync.SyncKind
import app.doorprints.shared.sync.SyncRow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TC-U: getting a restored candidate's photos back from the person's own Google Drive (S4b-BL-293), on the fake Drive with
 * the real encryption core (the web's `photo-restorer.spec.ts`, the same cases): the photos came from a real upload, were
 * deleted with a real tombstone, and the restorer reads the device's own sync file and the photo files like any sync read.
 */
class PhotoRestorerTest {
    private val day = 24L * 60 * 60 * 1000

    private fun bytes(n: Int, seed: Int): ByteArray = ByteArray(n) { i -> (i * 31 + seed * 17 + (i shr 3)).toByte() }
    private fun src(b: ByteArray): () -> Source = { Buffer().apply { write(b) } }

    private class Scene(val w: SyncWorld, val a: TestDevice, val data: Map<String, ByteArray>, val houseAt: Long)

    /** Device A has a candidate with photos in Drive, deletes the candidate, and its photos' tombstones are in A's sync file. */
    private suspend fun scene(ids: List<String> = listOf("p1", "p2")): Scene {
        val w = SyncWorld()
        val a = w.add("A")
        a.edit("h1", "Blue gate")
        val data = LinkedHashMap<String, ByteArray>()
        ids.forEachIndexed { i, id ->
            data[id] = bytes(3000 + i, i + 1)
            a.local.put(photoRow(id, "h1", a.now(), a.id), true)
        }
        val ba = a.backend()
        ba.commitPushes()
        for (id in ids) ba.uploadPhoto("h1", id, "$id.jpg", data.getValue(id).size.toLong(), src(data.getValue(id)))
        ba.commitPushes()
        w.server.clock.advance(1000)
        a.delete("h1")
        val houseAt = a.local.stamp("h1")!!.updatedAt
        w.server.clock.advance(1000)
        val bd = a.backend()
        for (id in ids) {
            bd.deletePhoto(id)
            a.local.rows.remove(SyncKind.PHOTOS to id)
        }
        bd.commitPushes()
        w.server.clock.advance(1000)
        return Scene(w, a, data, houseAt)
    }

    private fun restorer(d: TestDevice, engine: DriveSyncEngine = d.engine()) = PhotoRestorer(engine, d.photos(), d::now)
    private fun request(houseAt: Long, known: List<GonePhoto> = emptyList()) = PhotoRestoreRequest("h1", houseAt, known)
    private fun ids(r: PhotoRestoreReport) = r.fetched.map { it.photo.id }.sorted()
    private fun reasons(r: PhotoRestoreReport) = r.failed.map { it.photo.id to it.reason }.sortedBy { it.first }
    private fun gone(id: String, deletedAt: Long) = GonePhoto(id, deletedAt, "image/jpeg", null, PhotoMeta())

    @Test
    fun bringsEveryPhotoBackFromDriveWithItsExactBytesReadingOnly() = runTest {
        val s = scene()
        val before = s.w.server.requests.size
        val report = restorer(s.a).restore(request(s.houseAt))
        assertEquals(RestoreConnection.OK, report.connection)
        assertEquals(listOf("p1", "p2"), ids(report))
        for (f in report.fetched) assertEquals(s.data.getValue(f.photo.id).toList(), f.bytes.toList())
        assertTrue(report.failed.isEmpty())
        val ops = s.w.server.requests.drop(before).map { it.first }.toSet()
        assertTrue(ops.toString(), ops.all { it in setOf(DriveOp.LIST, DriveOp.GET, DriveOp.DOWNLOAD) })
    }

    @Test
    fun bringsBackOnlyThePhotosWhoseFilesAreStillThereAndSaysWhichAreMissing() = runTest {
        val s = scene()
        s.w.server.deleteByHand(s.a.photoState.refs.getValue("p2").driveFileId)
        val report = restorer(s.a).restore(request(s.houseAt))
        assertEquals(listOf("p1"), ids(report))
        assertEquals(s.data.getValue("p1").toList(), report.fetched.single().bytes.toList())
        assertEquals(listOf("p2" to PhotoRestoreReason.MISSING), reasons(report))
    }

    @Test
    fun aFileInTheBinIsNotRead() = runTest {
        val s = scene(listOf("p1"))
        val file = s.a.photoState.refs.getValue("p1").driveFileId
        s.a.drive.trash(file)
        val before = s.w.server.requests.size
        val report = restorer(s.a).restore(request(s.houseAt))
        assertEquals(listOf("p1" to PhotoRestoreReason.MISSING), reasons(report))
        assertFalse(s.w.server.requests.drop(before).any { it.first == DriveOp.DOWNLOAD && it.second == file })
    }

    @Test
    fun pastThe30DaysNothingIsFetched() = runTest {
        val s = scene()
        s.w.server.clock.advance(31 * day)
        val before = s.w.server.requests.size
        val report = restorer(s.a).restore(request(s.houseAt))
        assertTrue(report.fetched.isEmpty())
        assertEquals(listOf("p1" to PhotoRestoreReason.PAST_WINDOW, "p2" to PhotoRestoreReason.PAST_WINDOW), reasons(report))
        val files = s.a.photoState.refs.values.map { it.driveFileId }
        assertFalse(s.w.server.requests.drop(before).any { it.first == DriveOp.DOWNLOAD && it.second in files })
    }

    @Test
    fun offlineAtTheFirstReadEveryKnownPhotoSaysOfflineAndNothingIsThrown() = runTest {
        val s = scene()
        s.w.server.faults.always(DriveFault.Offline)
        val report = restorer(s.a).restore(request(s.houseAt, listOf(gone("p1", s.houseAt + 1000), gone("p2", s.houseAt + 1000))))
        assertEquals(RestoreConnection.OFFLINE, report.connection)
        assertTrue(report.fetched.isEmpty())
        assertEquals(listOf("p1" to PhotoRestoreReason.OFFLINE, "p2" to PhotoRestoreReason.OFFLINE), reasons(report))
    }

    @Test
    fun theConnectionDropsAfterTheFirstPhotoThatOneIsBackTheRestSayOffline() = runTest {
        val s = scene()
        // of this run's downloads the first is the sync file, the second the first photo: from there on every download fails
        val downloads = s.w.server.requests.count { it.first == DriveOp.DOWNLOAD }
        s.w.server.faults.on(DriveOp.DOWNLOAD, downloads + 2, DriveFault.Interleave { it.faults.always(DriveFault.Offline, DriveOp.DOWNLOAD) })
        val report = restorer(s.a).restore(request(s.houseAt))
        assertEquals(RestoreConnection.OFFLINE, report.connection)
        assertEquals(listOf("p1"), ids(report))
        assertEquals(s.data.getValue("p1").toList(), report.fetched.single().bytes.toList())
        assertEquals(listOf("p2" to PhotoRestoreReason.OFFLINE), reasons(report))
    }

    @Test
    fun aPhotoFileChangedInDriveDoesNotCheckOutAndIsUnreadableTheOthersStillCome() = runTest {
        val s = scene()
        val file = s.a.photoState.refs.getValue("p1").driveFileId
        val content = s.w.server.contentOf(file)!!
        content[content.size - 5] = (content[content.size - 5].toInt() xor 1).toByte()
        s.w.server.editByHand(file, content)
        val report = restorer(s.a).restore(request(s.houseAt))
        assertEquals(listOf("p1" to PhotoRestoreReason.UNREADABLE), reasons(report))
        assertEquals(listOf("p2"), ids(report))
        assertEquals(s.data.getValue("p2").toList(), report.fetched.single().bytes.toList())
    }

    @Test
    fun aPhotoWhoseDriveFileIsAnotherPhotosIsRefusedByItsChecksum() = runTest {
        val s = scene()
        val r2 = s.a.photoState.refs.getValue("p2")
        val r1 = s.a.photoState.refs.getValue("p1")
        s.a.photoState = s.a.photoState.copy(refs = s.a.photoState.refs + ("p1" to PhotoRef(r2.driveFileId, r1.sha256)))
        // the file's own tombstone rows name the files too, so ask with the local record alone
        // a request for a candidate id the file has no tombstones for, so only the local record decides
        val report = restorer(s.a).restore(PhotoRestoreRequest("other-house", s.houseAt, listOf(gone("p1", s.houseAt + 1000))))
        assertEquals(listOf("p1" to PhotoRestoreReason.UNREADABLE), reasons(report))
    }

    @Test
    fun anotherDeviceThatTookTheTombstonesInASyncRestoresThemToo() = runTest {
        val s = scene()
        val b = s.w.add("B")
        repeat(2) {
            s.w.server.clock.advance(1000)
            b.backend().commitPushes()
        }
        val report = restorer(b).restore(request(s.houseAt))
        assertEquals(listOf("p1", "p2"), ids(report))
        for (f in report.fetched) assertEquals(s.data.getValue(f.photo.id).toList(), f.bytes.toList())
    }

    @Test
    fun aPhotoDeletedByHandLongBeforeItsCandidateIsNotBroughtBackWithIt() = runTest {
        val s = scene()
        val report = restorer(s.a).restore(request(s.houseAt + 10 * day))
        assertTrue(report.fetched.isEmpty())
        assertTrue(report.failed.isEmpty())
    }

    @Test
    fun whileSyncIsPausedForWantOfTheScreenLockNothingIsOpenedAndEveryKnownPhotoIsNotConnected() = runTest {
        val s = scene()
        val paused = DriveSyncEngine(s.a.drive, s.a.p, s.a.session(false), s.a.store, s.a.local, s.a::now, { true })
        val before = s.w.server.requests.size
        val report = restorer(s.a, paused).restore(request(s.houseAt, listOf(gone("p1", s.houseAt + 1000))))
        assertEquals(RestoreConnection.NOT_CONNECTED, report.connection)
        assertEquals(listOf("p1" to PhotoRestoreReason.NOT_CONNECTED), reasons(report))
        assertEquals(before, s.w.server.requests.size)
    }

    @Test
    fun aDriveAnswerThatIsNotAboutTheConnectionIsUnreadableForThatPhotoAndTheOthersStillCome() = runTest {
        val s = scene()
        // of this run's GETs the first looks at the Photos folder, the second at the first photo's file
        val gets = s.w.server.requests.count { it.first == DriveOp.GET }
        s.w.server.faults.on(DriveOp.GET, gets + 2, DriveFault.Conflict)
        val report = restorer(s.a).restore(request(s.houseAt))
        assertEquals(RestoreConnection.OK, report.connection)
        assertEquals(listOf("p1" to PhotoRestoreReason.UNREADABLE), reasons(report))
        assertEquals(listOf("p2"), ids(report))
    }

    @Test
    fun aTombstoneStagedForTheNextPassIsFoundBeforeAnyFileHasIt() = runTest {
        val s = scene(listOf("p1"))
        val eng = s.a.engine()
        val ref = s.a.photoState.refs.getValue("p1")
        eng.stage(
            SyncRow.of(
                SyncKind.PHOTOS,
                buildJsonObject {
                    put("id", "p9")
                    put("houseId", "h1")
                    put("contentType", "image/jpeg")
                    put("sizeBytes", 1)
                    put("updatedAt", IsoTime.format(s.houseAt + 5000))
                    put("deleted", true)
                    put("by", s.a.id)
                    put("driveFileId", ref.driveFileId)
                    put("sha256", ref.sha256)
                },
            ),
        )
        val report = restorer(s.a, eng).restore(request(s.houseAt))
        assertEquals(listOf("p1", "p9"), ids(report))
        assertEquals(s.data.getValue("p1").toList(), report.fetched.first { it.photo.id == "p9" }.bytes.toList())
    }

    @Test
    fun theDevicesOwnFileIsNotDownloadedAgainWhileItIsTheOneItWroteLast() = runTest {
        val s = scene()
        val eng = s.a.engine()
        s.a.sync(engine = eng)
        val before = s.w.server.requests.count { it.first == DriveOp.DOWNLOAD }
        val report = restorer(s.a, eng).restore(request(s.houseAt))
        assertEquals(listOf("p1", "p2"), ids(report))
        assertEquals("the two photo files only", 2, s.w.server.requests.count { it.first == DriveOp.DOWNLOAD } - before)
    }

    // ---- Delete everything ---------------------------------------------------------------------------------------------

    private class OpenGate : AuthorizationGate {
        override suspend fun isGenuine(token: AuthorizationToken) = token.proof == "ok"
        override suspend fun stillHolds(token: AuthorizationToken) = true
    }

    private class MemoryStore : DeletionStore {
        private var current: PendingDeletion? = null
        private var done: DeletedMarker? = null
        override suspend fun pending() = current
        override suspend fun savePending(pending: PendingDeletion) {
            current = pending
        }

        override suspend fun clearPending() {
            current = null
        }

        override suspend fun marker() = done
        override suspend fun recordFinished(marker: DeletedMarker, forgetFolder: Boolean) {
            done = marker
        }
    }

    @Test
    fun afterDeleteEverythingNothingIsLeftToRestore() = runTest {
        val s = scene()
        val service = DriveDeletionService(s.a.drive, OpenGate(), MemoryStore(), { true }, s.w.server.clock::now, saveEvery = 3)
        val ready = service.preflight(s.w.rootId, DeletionAction.Everything) as PlanResult.Ready
        val plan = ready.plan
        val out = service.delete(plan, AuthorizationToken(plan.level, s.w.server.clock.now(), plan.operationId, "ok"))
        assertTrue(out is DeletionOutcome.Ran)
        assertEquals(emptyList<String>(), s.w.photoFiles().map { it.id })
        val report = restorer(s.a).restore(request(s.houseAt, listOf(gone("p1", s.houseAt + 1000), gone("p2", s.houseAt + 1000))))
        assertTrue(report.fetched.isEmpty())
        assertEquals(listOf(PhotoRestoreReason.MISSING, PhotoRestoreReason.MISSING), report.failed.map { it.reason })
    }
}
