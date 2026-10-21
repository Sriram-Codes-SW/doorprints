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
package app.doorprints.drive.backup

import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.NewFile
import app.doorprints.drive.photo.GonePhoto
import app.doorprints.drive.photo.PhotoRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The backup tidy's 30-day clean-up of deleted photos' Drive files (S4b-BL-293, docs/15 section 5.1): with and without a
 * tombstone source, a source that fails, a cancelled one, and a held shrink guard. The rules themselves are
 * `PhotoSweepRulesTest`.
 */
class DriveBackupSweepTest {
    private val p = JvmCryptoProvider
    private val server = FakeDriveServer()
    private val clock get() = server.clock
    private val a = Rig(server, "Pixel 8")
    private val day = 86_400_000L

    private suspend fun ready(): ReadyFolder {
        a.created()
        return a.ready()
    }

    private fun serviceWith(source: (suspend () -> List<GonePhoto>)?) =
        DriveBackupService(a.drive, p, a.identity, a.state, a.trust, { server.clock.now() }, { a.offset }, photoTombstones = source)

    /** A Drive file standing for a photo's encrypted bytes, and the tombstone naming it. */
    private fun photoFile(id: String, deletedAt: Long): Pair<String, GonePhoto> {
        val folder = server.putByHand(NewFile("photos", "application/vnd.google-apps.folder", emptyList()), ByteArray(0)).id
        val file = server.putByHand(NewFile("photo-$id", "application/octet-stream", listOf(folder)), p.randomBytes(40))
        return file.id to GonePhoto(id = id, deletedAt = deletedAt, ref = PhotoRef(file.id, "a".repeat(64)))
    }

    private fun binned(fileId: String) = server.fileOrNull(fileId)?.trashed == true

    private suspend fun DriveBackupService.done(folder: ReadyFolder, payload: Payload): BackupOutcome.Done =
        when (val o = backUp(folder, payload.source())) {
            is BackupOutcome.Done -> o
            is BackupOutcome.Failed -> fail("backup failed: ${o.problem}").let { error("unreachable") }
        }

    @Test
    fun withoutATombstoneSourceNothingIsSwept() = runTest {
        val folder = ready()
        val (file, _) = photoFile("p1", clock.now() - 100 * day)
        val service = serviceWith(null)
        service.done(folder, Payload.of(2_000, 5))
        clock.advance(40 * day)
        val done = service.done(folder, Payload.of(2_000, 5, seed = 1))
        assertFalse(binned(file))
        assertTrue(done.tidy.trashed.none { it == file })
        assertNull(done.tidy.problem)
    }

    @Test
    fun aDeletedPhotosFileLeavesDriveOnlyWhenOldEnoughAndNoStayingBackupCouldHoldIt() = runTest {
        val folder = ready()
        val t0 = clock.now()
        // Deleted before the first backup (which cannot hold it) and long ago: swept.
        val (old, oldGone) = photoFile("old", t0 - day)
        // Deleted after the first backup (which still holds that photo): kept for as long as that backup stays.
        val (later, laterGone) = photoFile("later", t0 + day)
        val service = serviceWith { listOf(oldGone, laterGone) }
        service.done(folder, Payload.of(2_000, 5))
        clock.advance(31 * day)
        val done = service.done(folder, Payload.of(2_000, 5, seed = 1))
        assertTrue(binned(old))
        assertTrue(old in done.tidy.trashed)
        assertFalse(binned(later))
        assertNull(done.tidy.problem)
    }

    @Test
    fun aPhotoInsideTheWindowIsKeptEvenWithoutAnyOlderBackup() = runTest {
        val folder = ready()
        val (file, gone) = photoFile("young", clock.now() - 5 * day)
        val service = serviceWith { listOf(gone) }
        val done = service.done(folder, Payload.of(2_000, 5))
        assertFalse(binned(file))
        assertTrue(done.tidy.trashed.isEmpty())
    }

    @Test
    fun aSourceThatFailsSweepsNothingAndNeverFailsTheBackup() = runTest {
        val folder = ready()
        val (file, _) = photoFile("p1", clock.now() - 100 * day)
        val service = serviceWith { throw IOException("sync file unreadable") }
        service.done(folder, Payload.of(2_000, 5))
        clock.advance(40 * day)
        val done = service.done(folder, Payload.of(2_000, 5, seed = 1))
        assertFalse(binned(file))
        assertNull(done.tidy.problem)
    }

    @Test
    fun aCancelledSourceCancelsTheBackupRun() = runTest {
        val folder = ready()
        val service = serviceWith { throw CancellationException("stop") }
        var cancelled = false
        try {
            service.backUp(folder, Payload.of(2_000, 5).source())
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun theBackupsRetentionPrunedDoNotCountAsStaying() = runTest {
        val folder = ready()
        val service = serviceWith(null)
        val first = service.done(folder, Payload.of(2_000, 5)).backup
        // Deleted a day after the first backup, which therefore holds the photo, for as long as it stays.
        val (file, gone) = photoFile("p1", first.createdAt + day)
        val sweeping = serviceWith { listOf(gone) }
        // Backups a month apart: retention keeps the newest 7, so the first is pruned at the 8th run, exactly when no
        // backup that stays could hold the photo any more; the sweep of that same run must take the file.
        for (i in 1..7) {
            clock.advance(31 * day)
            sweeping.done(folder, Payload.of(2_000, 5, seed = i))
            if (i < 7) assertFalse("run ${i + 1}", binned(file))
        }
        assertTrue(a.binned().any { it.id == first.fileId })
        assertTrue(binned(file))
    }

    @Test
    fun aHeldShrinkGuardStillCountsTheBackupsThatWouldHaveBeenPruned() = runTest {
        val folder = ready()
        val service = serviceWith(null)
        repeat(14) {
            service.done(folder, Payload.of(2_000, houses = 40, seed = it))
            clock.advance(day)
        }
        val oldestStaying = service.listBackups(folder).backups.minOf { it.createdAt }
        // Deleted just after the oldest backup that stays was made, so that backup may hold the photo.
        val (file, gone) = photoFile("p1", oldestStaying + 60_000)
        val sweeping = serviceWith { listOf(gone) }
        clock.advance(40 * day)
        val small = sweeping.done(folder, Payload.of(2_000, houses = 3, seed = 99))
        assertNotNull(small.tidy.hold)
        assertFalse(binned(file))
    }
}
