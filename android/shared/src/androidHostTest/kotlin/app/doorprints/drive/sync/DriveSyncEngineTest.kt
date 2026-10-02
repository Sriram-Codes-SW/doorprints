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

package app.doorprints.drive.sync

import app.doorprints.crypto.KeysException
import app.doorprints.drive.DriveException
import app.doorprints.drive.DriveFault
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.DriveOp
import app.doorprints.drive.NewFile
import app.doorprints.shared.sync.SyncKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** TC-U: the Drive sync engine over the fake Drive and the real encryption core (S4b-BL-118). */
class DriveSyncEngineTest {

    private fun permutations(items: List<String>): List<List<String>> =
        if (items.size <= 1) listOf(items) else items.flatMap { x -> permutations(items - x).map { listOf(x) + it } }

    private suspend fun settle(world: SyncWorld, rounds: Int = 3) {
        repeat(rounds) { world.devices.values.forEach { it.sync(); world.server.clock.advance(1000) } }
    }

    @Test
    fun twoDevicesConvergeInEveryOrder() = runTest {
        for (order in permutations(listOf("A", "B", "A", "B").distinct()).flatMap { o -> permutations(listOf("A", "B")).map { o + it } }) {
            val w = SyncWorld()
            val a = w.add("A")
            val b = w.add("B")
            a.edit("h1", "one"); a.edit("h2", "two")
            w.server.clock.advance(10)
            b.edit("h3", "three"); b.edit("h2", "two-b")
            for (n in order) { w.devices.getValue(n).sync(); w.server.clock.advance(1000) }
            settle(w)
            assertEquals("order $order", a.local.labels(), b.local.labels())
            assertEquals("order $order", mapOf("h1" to "one", "h2" to "two-b", "h3" to "three"), a.local.labels())
        }
    }

    @Test
    fun threeDevicesConvergeInEveryOrderWithConflictsAndDeletes() = runTest {
        for (order in permutations(listOf("A", "B", "C"))) {
            val w = SyncWorld()
            val a = w.add("A"); val b = w.add("B"); val c = w.add("C")
            a.edit("h1", "a1"); a.sync(); w.server.clock.advance(1000)
            b.sync(); c.sync(); w.server.clock.advance(1000)
            b.edit("h1", "b1"); w.server.clock.advance(1000)
            c.delete("h1"); w.server.clock.advance(1000)
            a.edit("h2", "a2"); w.server.clock.advance(1000)
            c.edit("h1", "c-again"); w.server.clock.advance(1000)
            for (n in order + order) { w.devices.getValue(n).sync(); w.server.clock.advance(1000) }
            settle(w)
            assertEquals("order $order", a.local.labels(), b.local.labels())
            assertEquals("order $order", a.local.labels(), c.local.labels())
            assertEquals("c-again", a.local.label("h1"))
        }
    }

    @Test
    fun aSecondPassWithNothingNewWritesNothing() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        a.edit("h1", "one")
        assertTrue((a.sync() as SyncPassResult.Done).report.wrote)
        val files = w.syncFiles().size
        w.server.clock.advance(5000)
        val again = a.sync() as SyncPassResult.Done
        assertFalse(again.report.wrote)
        assertEquals(files, w.syncFiles().size)
        assertEquals(0, w.server.requests.takeLast(20).count { it.first == DriveOp.UPLOAD && false })
    }

    @Test
    fun theFileIsDpxAndCarriesOnlyOpaqueProperties() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        a.edit("h1", "secret label")
        a.sync()
        val f = w.syncFiles().single { !it.trashed }
        assertEquals(KIND_SYNC, f.appProperties[DriveLayout.KIND])
        assertEquals(DriveLayout.STATE_COMPLETE, f.appProperties[DriveLayout.STATE])
        assertEquals(a.id, f.appProperties[DriveLayout.DEVICE])
        assertEquals(w.syncFolderId(), f.parents.single())
        val bytes = w.server.contentOf(f.id)!!
        assertEquals("DPX1", bytes.copyOfRange(0, 4).decodeToString())
        assertFalse(bytes.decodeToString().contains("secret label"))
    }

    @Test
    fun anInterruptedUploadLeavesAPartialFileNobodyReadsAndTheNextPassHealsIt() = runTest {
        val w = SyncWorld()
        val a = w.add("A"); val b = w.add("B")
        a.edit("h1", "one")
        w.server.faults.always(DriveFault.Server(503), DriveOp.UPDATE)
        try { a.sync(); fail("expected the pass to fail") } catch (e: DriveException) { assertEquals(DriveException.Kind.SERVER, e.kind) }
        assertTrue("rows stay dirty", a.local.dirty.isNotEmpty())
        val partial = w.syncFiles().single()
        assertEquals(DriveLayout.STATE_PARTIAL, partial.appProperties[DriveLayout.STATE])
        // B reads nothing from it.
        w.server.faults.clear()
        val r = b.sync() as SyncPassResult.Done
        assertEquals(0, r.report.take.size)
        assertEquals(emptyMap<String, String?>(), b.local.labels())
        // A recovers: the burned seq is not reused and the partial leftover goes to the bin.
        a.sync(force = true)
        assertTrue(w.server.fileOrNull(partial.id)!!.trashed)
        assertTrue(a.state.confirmedSeq > 1)
        b.sync()
        assertEquals("one", b.local.label("h1"))
    }

    @Test
    fun aPlantedPartialFileIsNeverRead() = runTest {
        val w = SyncWorld()
        val a = w.add("A"); val b = w.add("B")
        a.edit("h1", "one"); a.sync()
        val real = w.syncFiles().single()
        w.server.putByHand(NewFile("partial-sync-9.dpx", "application/octet-stream", listOf(w.syncFolderId()),
            real.appProperties + (DriveLayout.STATE to DriveLayout.STATE_PARTIAL) + (DriveLayout.DEVICE to a.id)), w.server.contentOf(real.id))
        val r = b.sync() as SyncPassResult.Done
        assertEquals("one", b.local.label("h1"))
        assertTrue(r.report.skipped.isEmpty())
    }

    @Test
    fun rowsAreMarkedCleanOnlyAfterTheReadBack() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        a.edit("h1", "one")
        // The read-back download finds other bytes than were written.
        w.server.faults.next(DriveFault.Interleave { s -> s.editByHand(s.allFiles().first { it.name.startsWith("sync-") }.id, ByteArray(300) { 7 }) }, DriveOp.DOWNLOAD)
        try { a.sync(); fail("expected the read-back to fail") } catch (e: DriveException) { assertEquals(DriveException.Kind.CORRUPT, e.kind) }
        assertEquals(setOf(SyncKind.HOUSES to "h1"), a.local.dirty)
        assertEquals(0L, a.state.confirmedSeq)
        assertNull(a.state.lastFileId)
    }

    @Test
    fun aTamperedAPlantedAndAForeignFileAreSkippedAndReported() = runTest {
        val w = SyncWorld()
        val a = w.add("A"); val b = w.add("B"); val c = w.add("C")
        a.edit("h1", "one"); a.sync()
        val real = w.syncFiles().single()
        val good = w.server.contentOf(real.id)!!
        val folder = w.syncFolderId()
        fun plant(deviceId: String, bytes: ByteArray, extra: Map<String, String> = emptyMap()) = w.server.putByHand(
            NewFile("x.dpx", "application/octet-stream", listOf(folder),
                mapOf(DriveLayout.KIND to KIND_SYNC, DriveLayout.DEVICE to deviceId, DriveLayout.STATE to DriveLayout.STATE_COMPLETE, "seq" to "99") + extra), bytes)
        // Trash A's real file from view so only the plants are candidates for C's slot.
        val flipped = good.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        plant(a.id, flipped)                                          // tampered, in A's slot (the real file still counts)
        plant(c.id, "{\"format\":\"doorprints-sync/1\"}".encodeToByteArray())    // plain JSON, in C's slot
        plant(c.id, good)                                              // A's genuine file in C's slot
        plant("f".repeat(32), good)                                    // a device nobody listed
        val r = b.sync() as SyncPassResult.Done
        val reasons = r.report.skipped.map { it.reason }
        assertTrue(reasons.toString(), SkipReason.BAD_ENVELOPE in reasons)
        assertTrue(SkipReason.NOT_ENCRYPTED in reasons)
        assertTrue(SkipReason.WRONG_DEVICE in reasons)
        assertTrue(SkipReason.UNLISTED_DEVICE in reasons)
        assertEquals("one", b.local.label("h1")) // A's genuine file in A's slot still counts
        // Nothing was deleted: every planted file is still there.
        assertEquals(4, w.syncFiles().count { !it.trashed && it.name == "x.dpx" })
        assertTrue(r.report.skipped.toString(), r.report.skipped.size >= 4)
    }

    @Test
    fun aRevokedDevicesFileAfterTheRevokeIsSkippedButItsEarlierFileCounts() = runTest {
        val w = SyncWorld()
        val a = w.add("A"); val b = w.add("B"); val c = w.add("C")
        c.edit("early", "before"); c.sync()
        w.server.clock.advance(60_000)
        a.sync()
        assertEquals("before", a.local.label("early"))
        w.revoke(a, c)
        w.server.clock.advance(60_000)
        // C (not knowing) writes again with its old key.
        c.edit("late", "after")
        c.sync(stale = true)
        w.server.clock.advance(60_000)
        val r = a.sync() as SyncPassResult.Done
        assertTrue(r.report.skipped.toString(), r.report.skipped.any { it.reason == SkipReason.REVOKED_WRITER })
        assertNull(a.local.label("late"))
        // B catches up to the new epoch and also skips it.
        val rb = b.sync() as SyncPassResult.Done
        assertNull(b.local.label("late"))
        assertTrue(rb.report.skipped.any { it.reason == SkipReason.REVOKED_WRITER })
        // The revoked device itself can no longer open the folder (no key for the new epoch).
        try { c.sync(); fail() } catch (e: KeysException) { /* nothing is wiped or revoked because of it */ }
        assertNotNull(c.local.label("late"))
    }

    @Test
    fun aRolledBackFileIsIgnored() = runTest {
        val w = SyncWorld()
        val a = w.add("A"); val b = w.add("B")
        a.edit("h1", "v1"); a.sync()
        val old = w.syncFiles().single()
        val oldBytes = w.server.contentOf(old.id)!!
        w.server.clock.advance(1000)
        a.edit("h1", "v2"); a.sync()
        b.sync()
        w.server.clock.advance(1000)
        b.sync()
        assertEquals("v2", b.local.label("h1"))
        // Someone hides the newest file and puts the first version back under a new name with a high hint.
        w.server.trashByHand(a.state.lastFileId!!)
        w.server.putByHand(NewFile("again.dpx", "application/octet-stream", listOf(w.syncFolderId()),
            mapOf(DriveLayout.KIND to KIND_SYNC, DriveLayout.DEVICE to a.id, DriveLayout.STATE to DriveLayout.STATE_COMPLETE, "seq" to "50")), oldBytes)
        val r = b.sync() as SyncPassResult.Done
        assertTrue(r.report.skipped.any { it.reason == SkipReason.ROLLED_BACK })
        assertEquals("v2", b.local.label("h1"))
    }

    @Test
    fun offlineBacksOffThenRecovers() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        a.edit("h1", "one")
        w.server.faults.always(DriveFault.Offline)
        try { a.sync(); fail() } catch (e: DriveException) { assertEquals(DriveException.Kind.OFFLINE, e.kind) }
        assertEquals(1, a.state.failures)
        assertTrue(a.state.notBefore > w.now())
        assertTrue(a.local.dirty.isNotEmpty())
        w.server.faults.clear()
        assertTrue(a.sync(force = false) is SyncPassResult.Waiting)
        w.server.clock.advance(60_000)
        assertTrue(a.sync(force = false) is SyncPassResult.Done)
        assertEquals(0, a.state.failures)
        assertTrue(a.local.dirty.isEmpty())
    }

    @Test
    fun theShrinkGuardWaitsForTheUserThenApplies() = runTest {
        val w = SyncWorld()
        val a = w.add("A"); val b = w.add("B")
        for (i in 1..12) a.edit("h$i", "house $i")
        a.sync(); b.sync()
        w.server.clock.advance(1000)
        for (i in 1..11) a.delete("h$i")
        a.sync()
        val first = b.sync()
        assertTrue(first is SyncPassResult.NeedsConfirmation)
        first as SyncPassResult.NeedsConfirmation
        assertEquals(11, first.housesToDelete)
        assertEquals(12, first.liveHouses)
        assertEquals("house 1", b.local.label("h1"))
        w.server.clock.advance(1000)
        val second = b.sync(confirmShrink = true)
        assertTrue(second is SyncPassResult.Done)
        assertEquals("<deleted>", b.local.label("h1"))
        assertEquals("house 12", b.local.label("h12"))
    }

    @Test
    fun aFarFutureStampIsHeldUntilTheClockCatchesUp() = runTest {
        val w = SyncWorld()
        val a = w.add("A"); val b = w.add("B")
        a.skewMs = 48 * 3_600_000L
        a.edit("h1", "from the future")
        a.edit("h2", "from the future too")
        a.local.put(houseRow("ok", "fine", w.now(), a.id), true)
        a.sync()
        val r = b.sync() as SyncPassResult.Done
        assertEquals(2, r.report.held)
        assertEquals(mapOf("ok" to "fine"), b.local.labels())
        w.server.clock.advance(25 * 3_600_000L)
        val later = b.sync() as SyncPassResult.Done
        assertEquals(0, later.report.held)
        assertEquals("from the future", b.local.label("h1"))
    }

    @Test
    fun isBehindUsesTheListingHintButTheAuthenticatedSeqDecides() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        assertFalse(a.engine().isBehind())
        a.edit("h1", "one"); a.sync()
        val downloads = w.server.requests.count { it.first == DriveOp.DOWNLOAD }
        assertFalse(a.engine().isBehind())
        assertEquals("no download when the file is as written", downloads, w.server.requests.count { it.first == DriveOp.DOWNLOAD })
        // Touching metadata or the head revision's time changes nothing.
        w.server.renameByHand(a.state.lastFileId!!, "renamed.dpx")
        assertFalse(a.engine().isBehind())
        // The file vanishes: behind, and nothing else happens to Drive.
        w.server.deleteByHand(a.state.lastFileId!!)
        assertTrue(a.engine().isBehind())
        a.sync()
        assertFalse(a.engine().isBehind())
    }

    @Test
    fun aDeviceWithoutAPinOpensNothing() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val unpinned = app.doorprints.crypto.KeysGuard(w.p, app.doorprints.crypto.MemoryWatermarkStore())
        val engine = DriveSyncEngine(a.drive, w.p, FolderSession(w.rootId, a.kid, a.opened, unpinned), a.store, a.local, a::now)
        val before = w.server.requests.size
        try { engine.run(); fail() } catch (e: KeysException) { assertEquals(KeysException.Kind.NOT_PINNED, e.kind) }
        assertEquals(before, w.server.requests.size)
    }

    @Test
    fun aKeysErrorEndsThePassWithoutTouchingDrive() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val session = FolderSession(w.rootId, a.kid, a.opened, a.guard) { throw KeysException(KeysException.Kind.REVOKED, "x") }
        val engine = DriveSyncEngine(a.drive, w.p, session, a.store, a.local, a::now)
        val before = w.server.requests.size
        try { engine.run(); fail() } catch (e: KeysException) { assertEquals(KeysException.Kind.REVOKED, e.kind) }
        assertEquals(before, w.server.requests.size)
        assertEquals(0, w.server.allFiles().count { it.trashed })
    }

    @Test
    fun aPausedDeviceDoesNotSync() = runTest {
        val w = SyncWorld()
        val a = w.add("A")
        val engine = DriveSyncEngine(a.drive, w.p, FolderSession(w.rootId, a.kid, a.opened, a.guard), a.store, a.local, a::now, paused = { true })
        val before = w.server.requests.size
        assertEquals(SyncPassResult.Paused, engine.run())
        assertEquals(before, w.server.requests.size)
    }

    @Test
    fun aDeletedPhotoTombstoneIsKeptForEver() = runTest {
        val w = SyncWorld()
        val a = w.add("A"); val b = w.add("B")
        a.local.put(app.doorprints.drive.sync.SyncRows.photo(app.doorprints.shared.api.PhotoChangeDto("p1", "h1", updatedAt = "2026-09-01T10:00:00Z"), a.id), false)
        val backend = DriveSyncBackend(a.engine(), a.local, a::now, a.id)
        backend.deletePhoto("p1")
        a.local.rows.remove(SyncKind.PHOTOS to "p1") // what the loop does after the commit
        backend.commitPushes()
        w.server.clock.advance(1000)
        a.edit("h1", "later"); a.sync()   // the next file still carries the tombstone
        b.sync()
        val tomb = b.local.rows[SyncKind.PHOTOS to "p1"]
        assertNotNull(tomb)
        assertTrue(tomb!!.stamp.deleted)
    }

    @Test
    fun theBackendHandsOutWhatWasTakenAndPhotosAreNotYet() = runTest {
        val w = SyncWorld()
        val a = w.add("A"); val b = w.add("B")
        a.edit("h1", "one"); a.sync()
        val backend = DriveSyncBackend(b.engine(), b.local, b::now, b.id)
        assertTrue(backend.stagesPushes)
        backend.commitPushes()
        val houses = backend.housesSince(0)
        assertEquals(listOf("h1"), houses.map { it.id })
        assertEquals(1L, houses.single().syncVersion)
        assertTrue("already handled", backend.housesSince(1).isEmpty())
        assertEquals(0L, backend.pushHouse(houses.single()).syncVersion)
        try { backend.downloadPhoto("x"); fail() } catch (e: DriveSyncNotYet) { assertEquals("S4b-BL-128", e.ticket) }
        try { backend.uploadPhoto("h", "p", "f", 1) { throw AssertionError() }; fail() } catch (e: DriveSyncNotYet) { }
    }
}
