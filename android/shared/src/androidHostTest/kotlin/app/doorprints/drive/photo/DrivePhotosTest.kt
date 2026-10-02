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

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.Dpx
import app.doorprints.crypto.DpxException
import app.doorprints.crypto.KeysException
import app.doorprints.crypto.KeysGuard
import app.doorprints.crypto.MemoryWatermarkStore
import app.doorprints.crypto.sha256Of
import app.doorprints.drive.DriveException
import app.doorprints.drive.DriveFault
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.DriveOp
import app.doorprints.drive.NewFile
import app.doorprints.drive.sync.DriveSyncBackend
import app.doorprints.drive.sync.FolderSession
import app.doorprints.drive.sync.SyncWorld
import app.doorprints.drive.sync.TestDevice
import app.doorprints.drive.sync.photoRow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** TC-U: photos over Drive (S4b-BL-128) on the fake Drive, with the real encryption core. */
class DrivePhotosTest {
    private fun bytes(n: Int, seed: Int = 1): ByteArray = Random(seed).nextBytes(n)
    private fun src(b: ByteArray): () -> Source = { Buffer().apply { write(b) } }
    private fun sha(w: SyncWorld, b: ByteArray) = Bytes.hex(w.p.sha256Of(b))
    private fun count(w: SyncWorld, op: DriveOp) = w.server.requests.count { it.first == op }

    private suspend fun upload(d: TestDevice, id: String, b: ByteArray, photos: DrivePhotos = d.photos()): PhotoUploadResult =
        photos.upload(id, b.size.toLong(), src(b))

    // ---- The file ----------------------------------------------------------------------------------------------------

    @Test
    fun eachPhotoIsOneEncryptedFileInThePhotosFolderWithOnlyOpaqueProperties() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val one = bytes(5000, 1)
        val two = bytes(5000, 2)
        val r1 = upload(a, "photo-1", one) as PhotoUploadResult.Uploaded
        upload(a, "photo-2", two)
        val files = w.photoFiles().filter { !it.trashed }
        assertEquals(2, files.size)
        for (f in files) {
            assertEquals(w.photosFolderId(), f.parents.single())
            assertEquals(KIND_PHOTO, f.appProperties[DriveLayout.KIND])
            assertEquals(DriveLayout.STATE_COMPLETE, f.appProperties[DriveLayout.STATE])
            assertEquals(setOf(DriveLayout.KIND, PROP_PHOTO_ID, DriveLayout.STATE, DriveLayout.DEVICE, DriveLayout.CREATED_AT), f.appProperties.keys)
            assertTrue(f.name, Regex("p-[0-9a-f]{24}\\.dpx").matches(f.name))
            val content = w.server.contentOf(f.id)!!
            assertEquals("DPX1", content.copyOfRange(0, 4).decodeToString())
            assertFalse(content.decodeToString().contains("photo-"))
        }
        assertEquals(r1.ref.driveFileId, files.first { it.appProperties[PROP_PHOTO_ID] == "photo-1" }.id)
        assertEquals(sha(w, one), r1.ref.sha256)
        // A fresh content key per file: the wrapped keys in the two headers differ.
        val h1 = w.server.contentOf(files[0].id)!!.copyOfRange(0, 300).decodeToString(throwOnInvalidSequence = false)
        val h2 = w.server.contentOf(files[1].id)!!.copyOfRange(0, 300).decodeToString(throwOnInvalidSequence = false)
        assertTrue(h1 != h2)
        assertEquals("the downloader gets the exact bytes", one.toList(), a.photos().download("photo-1")!!.toList())
    }

    @Test
    fun aPhotoIsNotUploadedAgainWhenUnchanged() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = bytes(4000)
        upload(a, "p1", b)
        val uploads = count(w, DriveOp.UPLOAD)
        val again = upload(a, "p1", b)
        assertTrue(again is PhotoUploadResult.Unchanged)
        assertEquals(uploads, count(w, DriveOp.UPLOAD))
        assertEquals(1, w.photoFiles().size)
    }

    @Test
    fun aChangedPhotoUnderTheSameIdIsUploadedAndTheOldFileOfThisDeviceGoesToTheBin() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val first = upload(a, "p1", bytes(4000, 1)) as PhotoUploadResult.Uploaded
        val second = upload(a, "p1", bytes(4000, 2)) as PhotoUploadResult.Uploaded
        assertTrue(first.ref.driveFileId != second.ref.driveFileId)
        assertTrue(w.server.fileOrNull(first.ref.driveFileId)!!.trashed)
        assertFalse(w.server.fileOrNull(second.ref.driveFileId)!!.trashed)
    }

    @Test
    fun dedupeByPhotoIdAdoptsAnotherDevicesFileInsteadOfWritingASecond() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = w.add("B")
        val data = bytes(6000)
        val ra = upload(a, "p1", data) as PhotoUploadResult.Uploaded
        val rb = upload(b, "p1", data)
        assertTrue(rb is PhotoUploadResult.Adopted)
        assertEquals(ra.ref, (rb as PhotoUploadResult.Adopted).ref)
        assertEquals(1, w.photoFiles().size)
        assertEquals(data.toList(), b.photos().download("p1")!!.toList())
    }

    // ---- Large, interrupted, resumed -----------------------------------------------------------------------------------

    @Test
    fun aLargePhotoUploadsInChunksAndResumesAfterADroppedChunk() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val big = bytes(6 * 1024 * 1024, 7)
        val cfg = PhotoConfig(chunkSize = 1024 * 1024)
        w.server.faults.on(DriveOp.UPLOAD_CHUNK, 2, DriveFault.DropAfter(300_000))
        val r = a.photos(config = cfg).upload("big", big.size.toLong(), src(big))
        assertTrue(r is PhotoUploadResult.Uploaded)
        assertEquals(1, count(w, DriveOp.UPLOAD_START))
        assertTrue("asked Drive how far it got", count(w, DriveOp.UPLOAD_STATUS) >= 1)
        assertEquals(big.toList(), a.photos().download("big")!!.toList())
    }

    @Test
    fun aStoppedUploadResumesTheSameSessionOnTheNextTry() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val big = bytes(6 * 1024 * 1024, 8)
        val photos = a.photos(config = PhotoConfig(chunkSize = 1024 * 1024))
        // The network goes away after the third chunk request, for every retry the client makes.
        w.server.faults.always(DriveFault.Offline, DriveOp.UPLOAD_CHUNK)
        try {
            photos.upload("big", big.size.toLong(), src(big))
            fail("expected the upload to stop")
        } catch (e: DriveException) {
            assertEquals(DriveException.Kind.OFFLINE, e.kind)
        }
        w.server.faults.clear()
        val r = photos.upload("big", big.size.toLong(), src(big))
        assertTrue(r is PhotoUploadResult.Uploaded)
        assertEquals("one session for both tries", 1, count(w, DriveOp.UPLOAD_START))
        assertEquals(big.toList(), a.photos().download("big")!!.toList())
        assertEquals(1, w.photoFiles().count { !it.trashed })
    }

    // ---- Checksums ----------------------------------------------------------------------------------------------------

    @Test
    fun aFileDriveStoredDifferentlyIsNotCompletedAndItsPartialGoesToTheBin() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = bytes(3000)
        w.server.faults.next(DriveFault.CorruptContent, DriveOp.UPLOAD)
        try {
            upload(a, "p1", b)
            fail("expected the checksum check to refuse")
        } catch (e: DriveException) {
            assertEquals(DriveException.Kind.CORRUPT, e.kind)
        }
        assertTrue(w.photoFiles().none { !it.trashed && it.appProperties[DriveLayout.STATE] == DriveLayout.STATE_COMPLETE })
        assertTrue("the next try writes a good file", upload(a, "p1", b) is PhotoUploadResult.Uploaded)
        assertEquals(b.toList(), a.photos().download("p1")!!.toList())
    }

    @Test
    fun aWrongPlaintextHashIsRefused() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = w.add("B")
        val r = upload(a, "p1", bytes(2000)) as PhotoUploadResult.Uploaded
        b.photoState = PhotoState(refs = mapOf("p1" to PhotoRef(r.ref.driveFileId, "0".repeat(64))))
        val photos = b.photos()
        assertNull(photos.download("p1"))
        assertEquals(PhotoSkipReason.CHECKSUM_MISMATCH, photos.skipped.single().reason)
    }

    @Test
    fun aSwappedPhotoFileIsRefused() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = w.add("B")
        val r1 = upload(a, "p1", bytes(2000, 1)) as PhotoUploadResult.Uploaded
        val r2 = upload(a, "p2", bytes(2000, 2)) as PhotoUploadResult.Uploaded
        // Row p1 says p1's hash but points at p2's (valid, same folder key) file.
        b.photoState = PhotoState(refs = mapOf("p1" to PhotoRef(r2.ref.driveFileId, r1.ref.sha256)))
        val photos = b.photos()
        assertNull(photos.download("p1"))
        assertEquals(PhotoSkipReason.CHECKSUM_MISMATCH, photos.skipped.single().reason)
    }

    @Test
    fun aPhotoFileOpenedWithoutTheRowsHashIsRefusedByTheEnvelope() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val r = upload(a, "p1", bytes(2000)) as PhotoUploadResult.Uploaded
        try {
            Dpx(w.p).decryptBytes(a.opened, Dpx.PHOTO, w.server.contentOf(r.ref.driveFileId)!!)
            fail("expected CHECKSUM_REQUIRED")
        } catch (e: DpxException) {
            assertEquals(DpxException.Kind.CHECKSUM_REQUIRED, e.kind)
        }
    }

    // ---- Tamper, plant, revoke, gone ----------------------------------------------------------------------------------

    @Test
    fun aTamperedPhotoIsSkippedAndTheOthersStillCome() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = w.add("B")
        val bad = upload(a, "bad", bytes(3000, 1)) as PhotoUploadResult.Uploaded
        val good = bytes(3000, 2)
        upload(a, "good", good)
        val content = w.server.contentOf(bad.ref.driveFileId)!!
        content[content.size - 5] = (content[content.size - 5].toInt() xor 1).toByte()
        w.server.editByHand(bad.ref.driveFileId, content)
        b.photoState = a.photoState
        val photos = b.photos()
        assertNull(photos.download("bad"))
        assertEquals(PhotoSkipReason.BAD_ENVELOPE, photos.skipped.single().reason)
        assertEquals(good.toList(), photos.download("good")!!.toList())
        // Remembered: the next pass does not download the bad file again.
        val downloads = count(w, DriveOp.DOWNLOAD)
        assertNull(photos.download("bad"))
        assertEquals(downloads, count(w, DriveOp.DOWNLOAD))
        // ... until the retry time has passed.
        w.server.clock.advance(PhotoConfig().badRetryMs + 1)
        assertNull(photos.download("bad"))
        assertTrue(count(w, DriveOp.DOWNLOAD) > downloads)
    }

    @Test
    fun aTruncatedPhotoIsSkipped() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = w.add("B")
        val r = upload(a, "p1", bytes(200_000)) as PhotoUploadResult.Uploaded
        val content = w.server.contentOf(r.ref.driveFileId)!!
        w.server.editByHand(r.ref.driveFileId, content.copyOf(content.size / 2))
        b.photoState = a.photoState
        assertNull(b.photos().download("p1"))
    }

    @Test
    fun aPlantedPlainFileIsIgnoredEverywhere() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = w.add("B")
        val data = bytes(3000)
        upload(a, "p1", data) // makes the Photos folder
        val planted = w.server.putByHand(
            NewFile("p-planted.dpx", "application/octet-stream", listOf(w.photosFolderId()),
                mapOf(DriveLayout.KIND to KIND_PHOTO, PROP_PHOTO_ID to "p2", DriveLayout.STATE to DriveLayout.STATE_COMPLETE)),
            "not encrypted, just a photo".encodeToByteArray(),
        )
        // (a) A row naming the planted file: skipped as not encrypted.
        b.photoState = PhotoState(refs = mapOf("p2" to PhotoRef(planted.id, sha(w, "not encrypted, just a photo".encodeToByteArray()))))
        val photos = b.photos()
        assertNull(photos.download("p2"))
        assertEquals(PhotoSkipReason.NOT_ENCRYPTED, photos.skipped.single().reason)
        // (b) A planted file under p2's id is not adopted when p2 is uploaded: a new file is written, the planted one untouched.
        b.photoState = PhotoState()
        val p2 = bytes(3000, 9)
        val r = upload(b, "p2", p2) as PhotoUploadResult.Uploaded
        assertTrue(r.ref.driveFileId != planted.id)
        assertFalse(w.server.fileOrNull(planted.id)!!.trashed)
        // (c) A planted file outside Photos is NOT_OURS even when a row names it.
        val outside = w.server.putByHand(
            NewFile("p-outside.dpx", "application/octet-stream", listOf(w.rootId),
                mapOf(DriveLayout.KIND to KIND_PHOTO, DriveLayout.STATE to DriveLayout.STATE_COMPLETE)),
            w.server.contentOf(r.ref.driveFileId)!!,
        )
        b.photoState = PhotoState(refs = mapOf("p2" to PhotoRef(outside.id, r.ref.sha256)))
        val photos2 = b.photos()
        assertNull(photos2.download("p2"))
        assertEquals(PhotoSkipReason.NOT_OURS, photos2.skipped.single().reason)
    }

    @Test
    fun aFileInTheBinOrNotCompleteOrNotAPhotoIsNotOurs() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = w.add("B")
        val r = upload(a, "p1", bytes(2000)) as PhotoUploadResult.Uploaded
        val content = w.server.contentOf(r.ref.driveFileId)!!
        val folder = w.photosFolderId()
        fun planted(props: Map<String, String>) = w.server.putByHand(NewFile("x.dpx", "application/octet-stream", listOf(folder), props), content).id
        val partial = planted(mapOf(DriveLayout.KIND to KIND_PHOTO, DriveLayout.STATE to DriveLayout.STATE_PARTIAL))
        val wrongKind = planted(mapOf(DriveLayout.KIND to "sync", DriveLayout.STATE to DriveLayout.STATE_COMPLETE))
        for (id in listOf(partial, wrongKind)) {
            b.photoState = PhotoState(refs = mapOf("p1" to PhotoRef(id, r.ref.sha256)))
            val photos = b.photos()
            assertNull(id, photos.download("p1"))
            assertEquals(PhotoSkipReason.NOT_OURS, photos.skipped.single().reason)
        }
        w.server.trashByHand(r.ref.driveFileId)
        b.photoState = PhotoState(refs = mapOf("p1" to r.ref))
        val photos = b.photos()
        assertNull(photos.download("p1"))
        assertEquals(PhotoSkipReason.NOT_OURS, photos.skipped.single().reason)
    }

    @Test
    fun aDeletedPhotoFileIsReportedAndNothingElseHappens() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = w.add("B")
        val r = upload(a, "p1", bytes(2000)) as PhotoUploadResult.Uploaded
        w.server.deleteByHand(r.ref.driveFileId)
        b.photoState = a.photoState
        val photos = b.photos()
        assertNull(photos.download("p1"))
        assertEquals(PhotoSkipReason.UNREADABLE, photos.skipped.single().reason)
        assertEquals(0, count(w, DriveOp.DELETE))
    }

    @Test
    fun aRevokedDevicesPhotoWrittenAfterTheRevokeIsSkipped() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = w.add("B")
        val c = w.add("C")
        val before = upload(b, "before", bytes(2000, 1)) as PhotoUploadResult.Uploaded
        w.server.clock.advance(10_000)
        w.revoke(a, b)
        w.server.clock.advance(10_000)
        // B still holds the old keys and writes on (its session cannot reopen keys.json).
        val after = upload(b, "after", bytes(2000, 2), b.photos(stale = true)) as PhotoUploadResult.Uploaded
        c.photoState = PhotoState(refs = mapOf("before" to before.ref, "after" to after.ref))
        val photos = c.photos()
        assertNotNull("written before the revoke: still good", photos.download("before"))
        assertNull(photos.download("after"))
        assertEquals(PhotoSkipReason.REVOKED_WRITER, photos.skipped.single().reason)
    }

    // ---- Caps, pin, partial -------------------------------------------------------------------------------------------

    @Test
    fun aPhotoOverTheCapIsSkippedWithoutAnyRequest() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val cfg = PhotoConfig(maxPlaintextBytes = 1000)
        val photos = a.photos(config = cfg)
        val before = w.server.requests.size
        assertEquals(PhotoUploadResult.Skipped(PhotoSkipReason.TOO_LARGE), photos.upload("p1", 2000, src(bytes(2000))))
        // A source longer than it said is stopped too.
        assertEquals(PhotoUploadResult.Skipped(PhotoSkipReason.TOO_LARGE), photos.upload("p2", 10, src(bytes(2000))))
        assertEquals(PhotoUploadResult.Skipped(PhotoSkipReason.EMPTY), photos.upload("p3", 0, src(ByteArray(0))))
        assertEquals(PhotoUploadResult.Skipped(PhotoSkipReason.BAD_ID), photos.upload("../x", 5, src(bytes(5))))
        assertEquals(before, w.server.requests.size)
        assertEquals(4, photos.skipped.size)
    }

    @Test
    fun aHugeFileIsNotDownloaded() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = w.add("B")
        val r = upload(a, "p1", bytes(50_000)) as PhotoUploadResult.Uploaded
        b.photoState = a.photoState
        val photos = b.photos(config = PhotoConfig(maxPlaintextBytes = 1000))
        assertNull(photos.download("p1"))
        assertEquals(PhotoSkipReason.TOO_LARGE, photos.skipped.single().reason)
        assertEquals(0, count(w, DriveOp.DOWNLOAD))
        assertNotNull(r)
    }

    @Test
    fun withoutAPinNothingIsAskedOfDrive() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val unpinned = FolderSession(w.rootId, a.kid, a.opened, KeysGuard(w.p, MemoryWatermarkStore()), null)
        val photos = DrivePhotos(a.drive, w.p, unpinned, a.photoStore, { w.now() })
        val before = w.server.requests.size
        for (call in listOf<suspend () -> Unit>({ photos.upload("p1", 3, src(bytes(3))) }, { photos.download("p1") })) {
            try {
                call()
                fail("expected NOT_PINNED")
            } catch (e: KeysException) {
                assertEquals(KeysException.Kind.NOT_PINNED, e.kind)
            }
        }
        assertEquals(before, w.server.requests.size)
    }

    @Test
    fun aPartialFileIsNeverReadAsAPhotoAndTheSameServiceCompletesItOnTheNextTry() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = w.add("B")
        val data = bytes(4000)
        val photos = a.photos()
        w.server.faults.always(DriveFault.Server(503), DriveOp.UPDATE)
        try {
            photos.upload("p1", data.size.toLong(), src(data))
            fail("the completing update fails")
        } catch (e: DriveException) {
            assertEquals(DriveException.Kind.SERVER, e.kind)
        }
        val partial = w.photoFiles().single()
        assertEquals(DriveLayout.STATE_PARTIAL, partial.appProperties[DriveLayout.STATE])
        assertTrue(partial.name.startsWith("partial-p-"))
        // Nobody reads a partial file as a photo.
        b.photoState = PhotoState(refs = mapOf("p1" to PhotoRef(partial.id, sha(w, data))))
        assertNull(b.photos().download("p1"))
        w.server.faults.clear()
        val uploads = count(w, DriveOp.UPLOAD)
        val r = photos.upload("p1", data.size.toLong(), src(data))
        assertTrue(r is PhotoUploadResult.Uploaded)
        assertEquals("the bytes were not sent twice", uploads, count(w, DriveOp.UPLOAD))
        assertEquals(1, w.photoFiles().size)
        assertEquals(data.toList(), a.photos().download("p1")!!.toList())
    }

    @Test
    fun aRestartedDeviceFindsItsOwnVerifiedPartialAndCompletesItWithoutSendingAgain() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val data = bytes(4000)
        w.server.faults.always(DriveFault.Server(503), DriveOp.UPDATE)
        try {
            a.photos().upload("p1", data.size.toLong(), src(data))
            fail("the completing update fails")
        } catch (_: DriveException) {
        }
        w.server.faults.clear()
        val uploads = count(w, DriveOp.UPLOAD)
        val r = a.photos().upload("p1", data.size.toLong(), src(data)) // a new service object: the process restarted
        assertTrue(r is PhotoUploadResult.Adopted)
        assertEquals(uploads, count(w, DriveOp.UPLOAD))
        val file = w.photoFiles().single()
        assertEquals(DriveLayout.STATE_COMPLETE, file.appProperties[DriveLayout.STATE])
        assertTrue(file.name.startsWith("p-"))
    }

    // ---- Through the backend: two devices, tombstones ---------------------------------------------------------------

    private suspend fun apply(d: TestDevice, backend: DriveSyncBackend) {
        for (c in backend.photoChangesSince(0)) {
            val row = app.doorprints.drive.sync.SyncRows.photo(c, d.id)
            if (!c.deleted) d.local.put(row, false) else d.local.rows.remove(row.kind to row.key)
        }
    }

    @Test
    fun twoDevicesConvergeWithAPhotoAndATombstone() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = w.add("B")
        a.edit("h1", "house")
        a.local.put(photoRow("p1", "h1", a.now(), a.id), true)
        val data = bytes(8000)
        val ba = a.backend()
        // Photos are held (Wi-Fi): metadata flows, nothing for B to download yet.
        ba.commitPushes()
        w.server.clock.advance(1000)
        val bb = b.backend()
        bb.commitPushes()
        assertTrue("no bytes in Drive yet: the row is left out", bb.photoChangesSince(0).isEmpty())
        // Now A uploads, and writes its file.
        ba.uploadPhoto("h1", "p1", "p1.jpg", data.size.toLong(), src(data))
        ba.commitPushes()
        w.server.clock.advance(1000)
        val bb2 = b.backend()
        bb2.commitPushes()
        val changes = bb2.photoChangesSince(0)
        assertEquals(listOf("p1"), changes.map { it.id })
        assertEquals(data.toList(), bb2.downloadPhotoIfAvailable("p1")!!.toList())
        assertTrue(bb2.photoSkips().isEmpty())
        apply(b, bb2)
        // B now has the ref (learned from A's authenticated file) and relays it in its own file.
        assertEquals(a.photoState.refs["p1"], b.photoState.refs["p1"])
        assertNotNull(b.local.rows[app.doorprints.shared.sync.SyncKind.PHOTOS to "p1"])
        // A deletes the photo: a tombstone with the Drive file stays in A's file for ever, even after its local row is gone.
        val bd = a.backend()
        bd.deletePhoto("p1")
        a.local.rows.remove(app.doorprints.shared.sync.SyncKind.PHOTOS to "p1")
        w.server.clock.advance(1000)
        bd.commitPushes()
        repeat(2) {
            w.server.clock.advance(1000)
            a.backend().commitPushes()
        }
        w.server.clock.advance(1000)
        val bb3 = b.backend()
        bb3.commitPushes()
        val gone = bb3.photoChangesSince(0)
        assertEquals(listOf("p1"), gone.map { it.id })
        assertTrue(gone.single().deleted)
        // The tombstone row names the Drive file, for the retention clean-up.
        val tomb = a.backend().let { backend ->
            backend.commitPushes()
            a.local.rows[app.doorprints.shared.sync.SyncKind.PHOTOS to "p1"]
        }
        assertNull("the engine never puts a tombstone back into the local rows", tomb)
        // Deleting a photo never touches the Drive file.
        assertEquals(0, count(w, DriveOp.DELETE))
    }

    @Test
    fun aTamperedPhotoDoesNotStopTheBackendAndIsReported() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val b = w.add("B")
        a.local.put(photoRow("bad", "h1", a.now(), a.id), true)
        a.local.put(photoRow("good", "h1", a.now(), a.id), true)
        val good = bytes(3000, 2)
        val ba = a.backend()
        ba.uploadPhoto("h1", "bad", "b.jpg", 3000, src(bytes(3000, 1)))
        ba.uploadPhoto("h1", "good", "g.jpg", 3000, src(good))
        ba.commitPushes()
        val badFile = a.photoState.refs.getValue("bad").driveFileId
        val c = w.server.contentOf(badFile)!!
        c[c.size - 3] = (c[c.size - 3].toInt() xor 0x55).toByte()
        w.server.editByHand(badFile, c)
        w.server.clock.advance(1000)
        val bb = b.backend()
        bb.commitPushes()
        assertEquals(setOf("bad", "good"), bb.photoChangesSince(0).map { it.id }.toSet())
        assertNull(bb.downloadPhotoIfAvailable("bad"))
        assertEquals(good.toList(), bb.downloadPhotoIfAvailable("good")!!.toList())
        assertEquals(listOf(PhotoSkipReason.BAD_ENVELOPE), bb.photoSkips().map { it.reason })
        try {
            bb.downloadPhoto("bad")
            fail("downloadPhoto has no null")
        } catch (e: DriveException) {
            assertEquals(DriveException.Kind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun withoutAPhotoServiceTheBackendKeepsItsOldAnswer() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val backend = DriveSyncBackend(a.engine(), a.local, { w.now() }, a.id)
        try {
            backend.uploadPhoto("h", "p", "p.jpg", 1) { Buffer() }
            fail("expected DriveSyncNotYet")
        } catch (e: app.doorprints.drive.sync.DriveSyncNotYet) {
            assertEquals("S4b-BL-128", e.ticket)
        }
    }

    @Test
    fun theUploadStaysOffTheNetworkWhenThePolicyHoldsItBack() = runTest {
        // The loop asks the gate before it calls the backend: on mobile data with the default setting no photo request is made.
        val w = SyncWorld()
        val a = w.add("A")
        var net = NetworkConditions(true, Metering.METERED)
        var t = w.now()
        val gate = PhotoUploadGate({ net }, { PhotoSettings() }, { t })
        val before = w.server.requests.size
        val data = bytes(2000)
        suspend fun loopStep(): Boolean {
            if (!gate.photosAllowed()) return false
            upload(a, "p1", data)
            return true
        }
        assertFalse(loopStep())
        assertEquals(before, w.server.requests.size)
        gate.grantOneOff()
        assertTrue(loopStep())
        t += PhotoNetworkPolicy.ONE_OFF_TTL_MS
        net = NetworkConditions(true, Metering.METERED)
        assertFalse("expired grant: held again", gate.photosAllowed())
    }

    @Test
    fun theDeviceIdAndNoHouseNameAppearInAnyPhotoFileName() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        upload(a, "photo-of-house-17", bytes(1500))
        val f = w.photoFiles().single()
        assertFalse(f.name.contains("house"))
        assertEquals("photo-of-house-17", f.appProperties[PROP_PHOTO_ID]) // the id only, as designed
    }
}
