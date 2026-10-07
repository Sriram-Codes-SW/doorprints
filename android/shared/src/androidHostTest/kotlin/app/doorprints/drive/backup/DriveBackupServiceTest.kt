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

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.Dpx
import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.crypto.KeysException
import app.doorprints.crypto.RecoveryKey
import app.doorprints.crypto.kidOf
import app.doorprints.crypto.sha256Of
import app.doorprints.drive.DriveFault
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.DriveOp
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.MetadataChange
import app.doorprints.drive.NewFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Drive backup service on the fake Drive (S4b-BL-116, TC-U-134): the round trip, interrupted and resumed uploads,
 * checksum mismatch, partial files, retention, the shrink guard, files that are not backups, refused imports, keys
 * and control rollback, offline. Every scenario runs on [InMemoryFakeDrive] with the real crypto.
 */
class DriveBackupServiceTest {
    private val p = JvmCryptoProvider
    private val server = FakeDriveServer()
    private val clock get() = server.clock
    private val a = Rig(server, "Pixel 8")

    private val day = 86_400_000L
    private val writes = setOf(DriveOp.CREATE, DriveOp.UPLOAD, DriveOp.UPLOAD_START, DriveOp.UPLOAD_CHUNK, DriveOp.UPDATE, DriveOp.DELETE, DriveOp.TRASH)

    private fun writeCount() = server.requests.count { it.first in writes }

    private suspend fun ready(): ReadyFolder {
        a.created()
        return a.ready()
    }

    private suspend fun DriveBackupService.done(folder: ReadyFolder, payload: Payload): BackupOutcome.Done =
        when (val o = backUp(folder, payload.source())) {
            is BackupOutcome.Done -> o
            is BackupOutcome.Failed -> fail("backup failed: ${o.problem}").let { error("unreachable") }
        }

    private suspend fun DriveBackupService.failed(folder: ReadyFolder, payload: Payload): DriveProblem =
        (backUp(folder, payload.source()) as? BackupOutcome.Failed ?: fail("expected a failure").let { error("unreachable") }).problem

    private suspend fun import(rig: Rig, folder: ReadyFolder, b: DriveBackup, staging: RecordingStaging = RecordingStaging()) =
        rig.service.imports.download(folder, b, staging)

    // ---- the round trip ----

    @Test
    fun createBackUpListAndImportRoundTrip() = runTest {
        val out = a.service.createFolder(withRecoveryKey = true)
        assertTrue(out.connection is DriveConnection.Ready)
        assertNotNull(out.recoveryKey)
        val folder = a.ready()
        assertEquals(1, folder.keys.epoch)
        assertEquals(1, folder.control.revision)

        val payload = Payload.of(200_000, houses = 12)
        val done = a.service.done(folder, payload)
        assertEquals(12, done.backup.houses)
        assertEquals(clock.now(), done.backup.createdAt)
        assertTrue(done.tidy.trashed.isEmpty())
        assertNull(done.tidy.problem)
        assertFalse(done.missingNewer)

        val file = a.live().single()
        assertEquals("Doorprints-backup-2026-09-21-1913.dpx".length, file.name.length)
        assertTrue(file.name, file.name.startsWith("Doorprints-backup-") && file.name.endsWith(".dpx"))
        assertEquals(DriveLayout.STATE_COMPLETE, file.appProperties[DriveLayout.STATE])
        // What Drive holds is dpx/1, not the ZIP, and the plaintext is nowhere in it.
        val stored = server.contentOf(file.id)!!
        assertEquals("DPX1", String(stored.copyOf(4)))
        assertFalse(String(stored, Charsets.ISO_8859_1).contains(String(payload.bytes.copyOf(64), Charsets.ISO_8859_1)))

        val listing = a.service.listBackups(folder)
        assertEquals(listOf(done.backup.fileId), listing.backups.map { it.fileId })
        assertTrue(listing.unfinished.isEmpty() && listing.junk.isEmpty() && listing.ignored.isEmpty())
        assertFalse(listing.missingNewer)

        val staging = RecordingStaging()
        val r = import(a, folder, listing.newest!!, staging) as ImportDownload.Verified
        assertEquals("doorprints-backup/1", r.format)
        assertEquals(payload.bytes.size.toLong(), r.plaintextSize)
        assertArrayEquals(payload.bytes, staging.bytes())
        assertEquals(0, staging.discards)
        assertNull(a.service.verifyNewest(folder))
        assertEquals(clock.now(), a.state.value.lastVerifyAt)
    }

    @Test
    fun filesCarryTheKindsAndLiveWhereTheDeletionRulesExpectThem() = runTest {
        val folder = ready()
        a.service.done(folder, Payload.of(3_000, 3))
        val all = server.allFiles()
        val root = all.first { it.appProperties[DriveLayout.ROLE] == "root" }
        val keys = all.first { it.appProperties[DriveLayout.KIND] == "keys" }
        val control = all.first { it.appProperties[DriveLayout.KIND] == "control" }
        val backup = all.first { it.appProperties[DriveLayout.KIND] == "backup" }
        assertEquals(listOf(root.id), keys.parents)
        assertEquals(listOf(root.id), control.parents)
        assertEquals(listOf(a.backupsFolder().id), backup.parents)
        assertEquals(listOf(root.id), a.backupsFolder().parents)
        assertTrue(all.filter { !it.isFolder }.all { it.appProperties[DriveLayout.KIND] != null })
    }

    @Test
    fun aSecondRunReconnectsWithoutWriting() = runTest {
        a.created()
        val before = writeCount()
        assertTrue(a.service.connect() is DriveConnection.Ready)
        assertEquals(before, writeCount())
    }

    @Test
    fun aLargeBackupIsUploadedResumableAndAnInterruptedChunkResumes() = runTest {
        val folder = ready()
        val payload = Payload.of(3_500_000, houses = 40)
        // 3.5 MB is under the multipart limit; make it bigger than 5 MB so the resumable path is used.
        val big = Payload.of(5_600_000, houses = 40)
        assertTrue(payload.bytes.size < 5 * 1024 * 1024 && big.bytes.size > 5 * 1024 * 1024)
        server.faults.on(DriveOp.UPLOAD_CHUNK, 2, DriveFault.DropAfter(100_000))
        val done = a.service.done(folder, big)
        val ops = server.requests.map { it.first }
        assertTrue(ops.contains(DriveOp.UPLOAD_START))
        assertTrue("resumed after the drop", ops.contains(DriveOp.UPLOAD_STATUS))
        assertTrue(clock.slept.isNotEmpty())
        val staging = RecordingStaging()
        assertTrue(import(a, folder, done.backup, staging) is ImportDownload.Verified)
        assertArrayEquals(big.bytes, staging.bytes())
        assertEquals(1, a.live().size)
    }

    @Test
    fun anUploadThatNeverFinishesLeavesNothingListed() = runTest {
        val folder = ready()
        val big = Payload.of(5_600_000, houses = 5)
        // The network goes after the session started and one chunk went: the file is never created.
        val first = server.requests.size
        server.faults.stopAfter(first + 3)
        val problem = a.service.failed(folder, big)
        assertEquals(DriveProblem.Kind.OFFLINE, problem.kind)
        server.faults.clear()
        assertTrue(a.live().isEmpty())
        assertTrue(a.service.listBackups(folder).backups.isEmpty())
        assertEquals(BackupSchedule.Failure.RETRYABLE, a.state.value.lastFailure)
    }

    @Test
    fun aChecksumMismatchIsNeverABackupAndIsBinned() = runTest {
        val folder = ready()
        server.faults.next(DriveFault.CorruptContent, DriveOp.UPLOAD)
        val problem = a.service.failed(folder, Payload.of(10_000, 3))
        assertEquals(DriveProblem.Kind.CORRUPT, problem.kind)
        assertTrue(a.live().isEmpty())
        assertEquals(1, a.binned().size)
        assertTrue(a.service.listBackups(folder).backups.isEmpty())
        // The next run works and finds exactly one backup.
        clock.advance(60_000)
        a.service.done(folder, Payload.of(10_000, 3))
        assertEquals(1, a.service.listBackups(folder).backups.size)
    }

    @Test
    fun aPartialFileIsNeverListedAndAnUnfinishedGenuineOneIsCompletedByTheNextRun() = runTest {
        val folder = ready()
        val payload = Payload.of(30_000, 7)
        // The upload lands, the last step (state=complete) never does.
        server.faults.always(DriveFault.Offline, DriveOp.UPDATE)
        val problem = a.service.failed(folder, payload)
        assertEquals(DriveProblem.Kind.OFFLINE, problem.kind)
        server.faults.clear()
        val partial = a.live().single()
        assertEquals(DriveLayout.STATE_PARTIAL, partial.appProperties[DriveLayout.STATE])
        assertTrue(partial.name.startsWith(BackupNames.PARTIAL_PREFIX))
        val listing = a.service.listBackups(folder)
        assertTrue(listing.backups.isEmpty())
        assertEquals(listOf(partial.id), listing.unfinished.map { it.fileId })

        clock.advance(day)
        val done = a.service.done(folder, Payload.of(30_000, 7, seed = 1))
        assertEquals(listOf(partial.id), done.tidy.completed)
        assertTrue(done.tidy.trashed.isEmpty())
        val after = a.service.listBackups(folder)
        assertEquals(2, after.backups.size)
        assertTrue(a.live().none { it.name.startsWith(BackupNames.PARTIAL_PREFIX) })
        // The healed one imports like any other.
        val staging = RecordingStaging()
        assertTrue(import(a, folder, after.backups.last(), staging) is ImportDownload.Verified)
        assertArrayEquals(payload.bytes, staging.bytes())
    }

    @Test
    fun aPartialFileThatDoesNotVerifyIsBinnedOnlyAfterADay() = runTest {
        val folder = ready()
        val planted = server.putByHand(
            NewFile(
                "partial-Doorprints-backup-x.dpx", "application/octet-stream", listOf(a.backupsFolder().id),
                mapOf(DriveLayout.KIND to "backup", DriveLayout.STATE to DriveLayout.STATE_PARTIAL),
            ),
            ByteArray(10) { 1 },
        )
        val first = a.service.done(folder, Payload.of(5_000, 2))
        assertTrue(first.tidy.trashed.isEmpty())
        assertEquals(listOf(planted.id), a.service.listBackups(folder).junk.map { it.id })
        clock.advance(25 * 3_600_000L)
        val second = a.service.done(folder, Payload.of(5_000, 2, seed = 3))
        assertEquals(listOf(planted.id), second.tidy.trashed)
        assertTrue(a.binned().any { it.id == planted.id })
        assertEquals(2, a.service.listBackups(folder).backups.size)
    }

    // ---- retention ----

    @Test
    fun retentionKeepsExactlyTheSetTheVectorsSay() = runTest {
        val folder = ready()
        val created = mutableListOf<RetentionEntry>()
        for (i in 0 until 40) {
            val done = a.service.done(folder, Payload.of(2_000, houses = 20, seed = i))
            created += RetentionEntry(done.backup.fileId, done.backup.createdAt, 20)
            val expected = BackupRetention.select(created.filter { e -> a.server.fileOrNull(e.id)?.trashed != true }, 330)
            val live = a.live().map { it.id }.toSet()
            assertEquals("run $i", expected.keep.toSet(), live)
            assertTrue(live.size <= 17)
            clock.advance(day)
        }
        // The pruned ones are in Drive's bin, not deleted.
        assertTrue(a.binned().isNotEmpty())
        assertEquals(created.size, a.live().size + a.binned().size)
        // The newest and the 7 days before it are all there.
        val liveIds = a.live().map { it.id }.toSet()
        assertTrue(created.takeLast(7).all { it.id in liveIds })
    }

    @Test
    fun theShrinkGuardHoldsPruningUntilThePersonConfirms() = runTest {
        val folder = ready()
        repeat(14) {
            a.service.done(folder, Payload.of(2_000, houses = 40, seed = it))
            clock.advance(day)
        }
        val steady = a.live().size
        assertTrue(a.binned().isNotEmpty())
        val small = a.service.done(folder, Payload.of(2_000, houses = 3, seed = 99))
        val hold = small.tidy.hold!!
        assertEquals(small.backup.fileId, hold.backupId)
        assertEquals(3, hold.houses)
        assertEquals(40, hold.previousHouses)
        assertTrue(small.tidy.trashed.isEmpty())
        assertEquals(steady + 1, a.live().size)
        clock.advance(day)
        val again = a.service.done(folder, Payload.of(2_000, houses = 3, seed = 98))
        assertNotNull(again.tidy.hold)
        assertTrue(again.tidy.trashed.isEmpty())

        a.service.confirmShrink(small.backup.fileId)
        a.service.confirmShrink(again.backup.fileId)
        clock.advance(day)
        val after = a.service.done(folder, Payload.of(2_000, houses = 3, seed = 97))
        assertNull(after.tidy.hold)
        assertTrue(after.tidy.trashed.isNotEmpty())
    }

    // ---- files that are not backups ----

    private fun plant(folderId: String, name: String, props: Map<String, String>, content: ByteArray, state: String = DriveLayout.STATE_COMPLETE) =
        server.putByHand(NewFile(name, "application/octet-stream", listOf(folderId), props + (DriveLayout.STATE to state)), content)

    private fun fakeMeta(createdAt: Long, houses: Int, epoch: Int = 1, mac: ByteArray = p.randomBytes(32)) = mapOf(
        DriveLayout.KIND to "backup", DriveLayout.CREATED_AT to createdAt.toString(), BackupMeta.HOUSES to houses.toString(),
        BackupMeta.EPOCH to epoch.toString(), BackupMeta.KID to Bytes.b64(p.randomBytes(16)), BackupMeta.MAC to Bytes.b64(mac),
    )

    @Test
    fun plainPlantedOrOlderFilesDoNotCountAsBackups() = runTest {
        val folder = ready()
        val good = a.service.done(folder, Payload.of(4_000, 10))
        val backups = a.backupsFolder().id
        clock.advance(day)
        // A plain ZIP with no properties is not even found; one with every property but no key is MAC_INVALID.
        val zip = server.putByHand(NewFile("Doorprints-backup-2030-01-01-0000.dpx", "application/zip", listOf(backups)), byteArrayOf(0x50, 0x4b, 3, 4))
        val forged = plant(backups, "Doorprints-backup-2030-01-01-0001.dpx", fakeMeta(clock.now() + 10 * day, 999), p.randomBytes(500))
        val partialForged = plant(backups, "partial-x.dpx", fakeMeta(clock.now() + 11 * day, 999), p.randomBytes(500), DriveLayout.STATE_PARTIAL)
        val noProps = plant(backups, "y.dpx", mapOf(DriveLayout.KIND to "backup"), p.randomBytes(500))
        // A tiny planted backup cannot make the shrink guard fire (it does not count).
        val tiny = plant(backups, "tiny.dpx", fakeMeta(clock.now() + 12 * day, 0), p.randomBytes(500))
        val listing = a.service.listBackups(folder)
        assertEquals(listOf(good.backup.fileId), listing.backups.map { it.fileId })
        val reasons = listing.ignored.toMap()
        assertEquals(IgnoredReason.MAC_INVALID, reasons[forged.id])
        assertEquals(IgnoredReason.MAC_INVALID, reasons[tiny.id])
        assertEquals(IgnoredReason.NOT_A_BACKUP, reasons[noProps.id])
        assertFalse(zip.id in reasons)
        assertEquals(listOf(partialForged.id), listing.junk.map { it.id })
        val next = a.service.done(folder, Payload.of(4_000, 10, seed = 5))
        assertNull(next.tidy.hold)
        assertEquals(2, a.service.listBackups(folder).backups.size)
    }

    @Test
    fun changingAnOldBackupsTimeOrMovingMetadataBetweenFilesBreaksTheMac() = runTest {
        val folder = ready()
        val old = a.service.done(folder, Payload.of(4_000, 10, seed = 1))
        clock.advance(day)
        val newer = a.service.done(folder, Payload.of(4_000, 2, seed = 2))
        // Someone with write access makes the old one look newer than the real newest.
        a.drive.updateMetadata(old.backup.fileId, MetadataChange(appProperties = mapOf(DriveLayout.CREATED_AT to (clock.now() + day).toString())))
        var listing = a.service.listBackups(folder)
        assertEquals(listOf(newer.backup.fileId), listing.backups.map { it.fileId })
        assertEquals(IgnoredReason.MAC_INVALID, listing.ignored.toMap()[old.backup.fileId])
        // Or copies the large backup's numbers and MAC onto the small one.
        val oldProps = a.server.fileOrNull(old.backup.fileId)!!.appProperties
        a.drive.updateMetadata(old.backup.fileId, MetadataChange(appProperties = mapOf(DriveLayout.CREATED_AT to old.backup.createdAt.toString())))
        a.drive.updateMetadata(
            newer.backup.fileId,
            MetadataChange(appProperties = oldProps.filterKeys { it in setOf(BackupMeta.HOUSES, BackupMeta.MAC, BackupMeta.KID) } + (DriveLayout.CREATED_AT to old.backup.createdAt.toString())),
        )
        listing = a.service.listBackups(folder)
        assertEquals(listOf(old.backup.fileId), listing.backups.map { it.fileId })
        assertEquals(IgnoredReason.MAC_INVALID, listing.ignored.toMap()[newer.backup.fileId])
    }

    @Test
    fun aCopyOfAGenuineBackupIsADuplicateAndNotANewBackup() = runTest {
        val folder = ready()
        val b = a.service.done(folder, Payload.of(4_000, 10))
        clock.advance(day)
        val src = server.fileOrNull(b.backup.fileId)!!
        val copy = server.putByHand(NewFile("copy.dpx", "application/octet-stream", listOf(a.backupsFolder().id), src.appProperties), server.contentOf(src.id))
        val listing = a.service.listBackups(folder)
        assertEquals(listOf(b.backup.fileId), listing.backups.map { it.fileId })
        assertEquals(listOf(copy.id), listing.duplicates)
        assertEquals(b.backup.createdAt, listing.newest!!.createdAt)
        val next = a.service.done(folder, Payload.of(4_000, 10, seed = 4))
        assertTrue(copy.id in next.tidy.trashed)
        assertTrue(a.live().none { it.id == copy.id })
    }

    @Test
    fun backupsMadeBeforeDeleteAllBackupsAreIgnoredAndTheNewestMissingIsReported() = runTest {
        val folder = ready()
        val b1 = a.service.done(folder, Payload.of(3_000, 5, seed = 1))
        clock.advance(day)
        val b2 = a.service.done(folder, Payload.of(3_000, 5, seed = 2))
        // The control file says: everything up to b1's time is deleted.
        val next = ControlFile(p).next(folder.keys, folder.control, backupsDeletedAt = b1.backup.createdAt)
        server.editByHand(folder.controlId, next.bytes)
        val folder2 = a.ready()
        val listing = a.service.listBackups(folder2)
        assertEquals(listOf(b2.backup.fileId), listing.backups.map { it.fileId })
        assertEquals(IgnoredReason.DELETED_BEFORE, listing.ignored.toMap()[b1.backup.fileId])
        // Someone removes the newest: the device notices and reports; nothing is deleted or re-created.
        server.deleteByHand(b2.backup.fileId)
        val after = a.service.listBackups(folder2)
        assertTrue(after.backups.isEmpty())
        assertTrue(after.missingNewer)
    }

    // ---- refused imports ----

    @Test
    fun aBackupEditedAfterTheListingIsRefusedAndTheStagingIsDiscarded() = runTest {
        val folder = ready()
        val b = a.service.done(folder, Payload.of(40_000, 10))
        val listed = a.service.listBackups(folder).newest!!
        val bytes = server.contentOf(b.backup.fileId)!!
        server.editByHand(b.backup.fileId, bytes.copyOf().also { it[bytes.size - 5] = (it[bytes.size - 5].toInt() xor 1).toByte() })
        val staging = RecordingStaging()
        val r = import(a, folder, listed, staging) as ImportDownload.Refused
        assertEquals(DriveProblem.Kind.BACKUP_REFUSED, r.problem.kind)
        assertEquals(1, staging.discards)
        assertEquals(0, staging.bytes().size)
        // And a fresh listing no longer shows it.
        assertTrue(a.service.listBackups(folder).backups.isEmpty())
    }

    @Test
    fun bytesChangedWhileDownloadingFailTheChecksumAndDiscardTheStaging() = runTest {
        val folder = ready()
        val b = a.service.done(folder, Payload.of(40_000, 10))
        val listed = a.service.listBackups(folder).newest!!
        server.faults.next(
            DriveFault.Interleave { s ->
                val c = s.contentOf(b.backup.fileId)!!
                s.editByHand(b.backup.fileId, c.copyOf().also { it[c.size / 2] = (it[c.size / 2].toInt() xor 4).toByte() })
            },
            DriveOp.DOWNLOAD,
        )
        val staging = RecordingStaging()
        val r = import(a, folder, listed, staging) as ImportDownload.Refused
        assertEquals(DriveProblem.Kind.CORRUPT, r.problem.kind)
        assertEquals(1, staging.discards)
    }

    @Test
    fun aBackupInTheBinOrMovedAwayIsGoneAndDiscards() = runTest {
        val folder = ready()
        val b = a.service.done(folder, Payload.of(4_000, 10))
        server.trashByHand(b.backup.fileId)
        val s1 = RecordingStaging()
        assertEquals(DriveProblem.Kind.BACKUP_GONE, (import(a, folder, b.backup, s1) as ImportDownload.Refused).problem.kind)
        assertEquals(1, s1.discards)
        server.untrashByHand(b.backup.fileId)
        server.moveByHand(b.backup.fileId, folder.rootId)
        val s2 = RecordingStaging()
        assertEquals(DriveProblem.Kind.BACKUP_GONE, (import(a, folder, b.backup, s2) as ImportDownload.Refused).problem.kind)
        assertEquals(1, s2.discards)
        server.deleteByHand(b.backup.fileId)
        val s3 = RecordingStaging()
        assertTrue(import(a, folder, b.backup, s3) is ImportDownload.Refused)
        assertEquals(1, s3.discards)
    }

    /** A backup made by a key holder by hand: [kid] in the metadata, [headerKid] and [headerEpochKey] in the file. */
    private fun handMade(folder: ReadyFolder, plain: ByteArray, headerKid: ByteArray, metaKid: ByteArray, createdAt: Long, state: String = DriveLayout.STATE_COMPLETE): String {
        val key = folder.keys.currentFolderKey()
        val (file, written) = Dpx(p).encryptBytes(key, folder.keys.epoch, headerKid, "doorprints-backup/1", plain)
        val meta = BackupMeta(createdAt, 4, folder.keys.epoch, metaKid, written.ciphertextSha256)
        val props = meta.appProperties(meta.mac(p, key), "hand").toMutableMap().also { it[DriveLayout.STATE] = state }
        return server.putByHand(NewFile("by-hand.dpx", "application/octet-stream", listOf(a.backupsFolder().id), props), file).id
    }

    @Test
    fun aHeaderThatDisagreesWithTheMetadataIsRefused() = runTest {
        val folder = ready()
        val myKid = kidOf(p, a.identity.key.publicKey)
        // Sanity: a by-hand backup with consistent header and metadata, by a listed writer, imports.
        val okId = handMade(folder, ByteArray(300) { 9 }, myKid, myKid, clock.now())
        val ok = a.service.listBackups(folder).backups.single { it.fileId == okId }
        val s0 = RecordingStaging()
        assertTrue(import(a, folder, ok, s0) is ImportDownload.Verified)
        assertEquals(300, s0.bytes().size)
        // The header names another writer than the (MAC-covered) metadata.
        clock.advance(day)
        val badId = handMade(folder, ByteArray(300) { 9 }, p.randomBytes(16), myKid, clock.now())
        val bad = a.service.listBackups(folder).backups.single { it.fileId == badId }
        val s1 = RecordingStaging()
        assertTrue(import(a, folder, bad, s1) is ImportDownload.Refused)
        assertEquals(1, s1.discards)
        assertEquals(0, s1.bytes().size)
        // A writer the key list does not know is not listed at all.
        clock.advance(day)
        val ghost = p.randomBytes(16)
        val ghostId = handMade(folder, ByteArray(300) { 9 }, ghost, ghost, clock.now())
        val listing = a.service.listBackups(folder)
        assertEquals(IgnoredReason.WRITER_REFUSED, listing.ignored.toMap()[ghostId])
    }

    @Test
    fun aPlainZipWithValidLookingMetadataIsNotDpx() = runTest {
        val folder = ready()
        val myKid = kidOf(p, a.identity.key.publicKey)
        val key = folder.keys.currentFolderKey()
        // A key holder's metadata over plain (unencrypted) bytes: listed, but the import fails closed (downgrade).
        val zip = byteArrayOf(0x50, 0x4b, 3, 4) + ByteArray(200)
        val sha = p.sha256Of(zip)
        val meta = BackupMeta(clock.now(), 4, 1, myKid, sha)
        val props = meta.appProperties(meta.mac(p, key), "hand").toMutableMap().also { it[DriveLayout.STATE] = DriveLayout.STATE_COMPLETE }
        val id = server.putByHand(NewFile("zip.dpx", "application/octet-stream", listOf(a.backupsFolder().id), props), zip).id
        val listed = a.service.listBackups(folder).backups.single { it.fileId == id }
        val staging = RecordingStaging()
        val r = import(a, folder, listed, staging) as ImportDownload.Refused
        assertEquals(DriveProblem.Kind.BACKUP_REFUSED, r.problem.kind)
        assertNotNull(r.problem.dpxKind)
        assertEquals(1, staging.discards)
        assertEquals(0, staging.bytes().size)
    }

    // ---- keys, recovery and enrolment ----

    @Test
    fun aNewDeviceIsNeverAdoptedAndWritesNothing() = runTest {
        a.created()
        a.service.done(a.ready(), Payload.of(3_000, 3))
        val b = Rig(server, "Tablet")
        val before = writeCount()
        val c = b.service.connect()
        assertTrue(c is DriveConnection.NeedsEnrolment)
        assertTrue((c as DriveConnection.NeedsEnrolment).recoveryAvailable)
        val e = b.service.createFolder(withRecoveryKey = true)
        assertEquals(DriveProblem.Kind.FOLDER_EXISTS, (e.connection as DriveConnection.Error).problem.kind)
        assertNull(e.recoveryKey)
        assertEquals(before, writeCount())
        assertEquals(1, server.allFiles().count { it.appProperties[DriveLayout.ROLE] == "root" })
    }

    @Test
    fun aWrongRecoveryKeyOpensNothingAndChangesNothing() = runTest {
        val out = a.service.createFolder(withRecoveryKey = true)
        val keysBefore = server.contentOf((out.connection as DriveConnection.Ready).folder.keysId)!!
        val b = Rig(server, "Tablet")
        val before = writeCount()
        val wrong = b.service.openWithRecoveryKey(RecoveryKey.generate(p))
        assertEquals(DriveProblem.Kind.WRONG_RECOVERY_KEY, (wrong as DriveConnection.Error).problem.kind)
        assertEquals(before, writeCount())
        assertArrayEquals(keysBefore, server.contentOf((out.connection as DriveConnection.Ready).folder.keysId))
        assertTrue(b.trust.keys.values.all { it.value == null })
        // The right key joins, lists the device, and then both devices read each other's backups.
        a.service.done(a.ready(), Payload.of(3_000, 3, seed = 1))
        clock.advance(day)
        val ok = b.service.openWithRecoveryKey(out.recoveryKey!!)
        val folderB = (ok as DriveConnection.Ready).folder
        assertEquals(2, folderB.keys.body.devices.size)
        assertEquals(1, b.service.listBackups(folderB).backups.size)
        b.service.done(folderB, Payload.of(3_000, 4, seed = 2))
        val folderA = a.ready()
        assertEquals(2, a.service.listBackups(folderA).backups.size)
        val staging = RecordingStaging()
        assertTrue(import(a, folderA, a.service.listBackups(folderA).newest!!, staging) is ImportDownload.Verified)
        assertTrue(staging.bytes().isNotEmpty())
    }

    @Test
    fun aRolledBackKeysFileIsRefusedWithoutAnyWrite() = runTest {
        val out = a.service.createFolder(withRecoveryKey = true)
        val keysId = (out.connection as DriveConnection.Ready).folder.keysId
        val firstRevision = a.drive.revisions(keysId).first().id
        val b = Rig(server, "Tablet")
        b.service.openWithRecoveryKey(out.recoveryKey!!)
        assertTrue(a.service.connect() is DriveConnection.Ready) // a pins revision 2
        val folder = a.ready()
        server.rollBack(keysId, firstRevision)
        val before = writeCount()
        val c = a.service.connect()
        assertEquals(DriveProblem.Kind.KEYS_ROLLED_BACK, (c as DriveConnection.Error).problem.kind)
        assertEquals(KeysException.Kind.ROLLED_BACK, c.problem.keysKind)
        assertEquals(before, writeCount())
        // The service handed out an old ReadyFolder: nothing it does can re-key or wipe.
        assertEquals(1, folder.keys.epoch)
    }

    @Test
    fun aRolledBackControlFileIsRefusedButOnlyANewerOneIsAccepted() = runTest {
        val folder = ready()
        val old = server.contentOf(folder.controlId)!!
        val next = ControlFile(p).next(folder.keys, folder.control, backupsDeletedAt = 5)
        server.editByHand(folder.controlId, next.bytes)
        assertEquals(2L, (a.service.connect() as DriveConnection.Ready).folder.control.revision)
        server.editByHand(folder.controlId, old)
        val before = writeCount()
        val c = a.service.connect()
        assertEquals(DriveProblem.Kind.CONTROL_ROLLED_BACK, (c as DriveConnection.Error).problem.kind)
        assertEquals(before, writeCount())
        // A forged one (another key) is invalid, not "rolled back".
        val foreign = Rig(FakeDriveServer(), "Other")
        foreign.created()
        server.editByHand(folder.controlId, foreign.server.contentOf((foreign.ready()).controlId)!!)
        assertEquals(DriveProblem.Kind.CONTROL_INVALID, ((a.service.connect()) as DriveConnection.Error).problem.kind)
    }

    @Test
    fun aMissingControlFileIsWrittenAgainKeepingTheDeleteMark() = runTest {
        val folder = ready()
        val next = ControlFile(p).next(folder.keys, folder.control, backupsDeletedAt = 77)
        server.editByHand(folder.controlId, next.bytes)
        a.ready()
        server.deleteByHand(folder.controlId)
        val again = a.ready()
        assertEquals(3L, again.control.revision)
        assertEquals(77L, again.control.backupsDeletedAt)
    }

    @Test
    fun aFolderWithoutKeysOrGoneIsReportedNotRecreated() = runTest {
        val folder = ready()
        server.deleteByHand(folder.keysId)
        val before = writeCount()
        assertEquals(DriveProblem.Kind.FOLDER_WITHOUT_KEYS, (a.service.connect() as DriveConnection.Error).problem.kind)
        assertEquals(before, writeCount())
        server.trashByHand(folder.rootId)
        assertTrue(a.service.connect() is DriveConnection.FolderGone)
        assertEquals(before, writeCount())
    }

    @Test
    fun anUnfinishedCreateIsRestartedByTheSameDeviceOnly() = runTest {
        // The network dies while the key list is written: nothing is pinned.
        server.faults.always(DriveFault.Offline, DriveOp.UPLOAD)
        val out = a.service.createFolder(withRecoveryKey = true)
        assertEquals(DriveProblem.Kind.OFFLINE, (out.connection as DriveConnection.Error).problem.kind)
        assertNull(out.recoveryKey)
        server.faults.clear()
        assertTrue(a.trust.keys.values.all { it.value == null })
        assertTrue(a.service.connect() is DriveConnection.NoFolder)
        // Another device sees a folder with no keys of its own to adopt.
        val other = Rig(server, "Tablet")
        assertTrue(other.service.createFolder(true).connection is DriveConnection.Error)
        // The same device starts again, and ends with one folder, one key list.
        val again = a.service.createFolder(withRecoveryKey = true)
        assertTrue(again.connection is DriveConnection.Ready)
        assertEquals(1, server.allFiles().count { it.appProperties[DriveLayout.ROLE] == "root" && !it.trashed })
        assertEquals(1, server.allFiles().count { it.appProperties[DriveLayout.KIND] == "keys" && !it.trashed })
    }

    // ---- offline and the schedule ----

    @Test
    fun offlineNeverLosesAnythingAndTheScheduleWaitsThenRetries() = runTest {
        val folder = ready()
        a.service.done(folder, Payload.of(3_000, 3))
        val liveBefore = a.live().map { it.id }
        clock.advance(day)
        server.faults.always(DriveFault.Offline)
        assertEquals(DriveProblem.Kind.OFFLINE, a.service.failed(folder, Payload.of(3_000, 3, seed = 1)).kind)
        assertEquals(DriveProblem.Kind.OFFLINE, a.service.verifyNewest(folder)!!.kind)
        assertEquals(BackupSchedule.Failure.RETRYABLE, a.state.value.lastFailure)
        server.faults.clear()
        assertEquals(liveBefore, a.live().map { it.id })
        val soon = a.service.schedule(enabled = true, ready = true)
        assertFalse(soon.backup)
        assertEquals(BackupSchedule.Reason.WAIT_RETRY, soon.reason)
        clock.advance(BackupSchedule.RETRY_MS)
        assertTrue(a.service.schedule(enabled = true, ready = true).backup)
        a.service.done(folder, Payload.of(3_000, 3, seed = 2))
        assertNull(a.state.value.lastFailure)
        assertEquals(BackupSchedule.Reason.NOT_DUE, a.service.schedule(enabled = true, ready = true).reason)
        // Not connected: the connection is an error, nothing is written, and nothing throws.
        server.faults.always(DriveFault.Offline)
        assertEquals(DriveProblem.Kind.OFFLINE, (a.service.connect() as DriveConnection.Error).problem.kind)
    }

    @Test
    fun aFullDriveAndARefusedGrantAreReportedAsTheyAre() = runTest {
        val folder = ready()
        server.faults.always(DriveFault.QuotaExceeded, DriveOp.UPLOAD)
        assertEquals(DriveProblem.Kind.QUOTA_EXCEEDED, a.service.failed(folder, Payload.of(3_000, 3)).kind)
        assertEquals(BackupSchedule.Failure.QUOTA, a.state.value.lastFailure)
        server.faults.clear()
        server.refusedTokens += "token-1"
        server.refusedTokens += "token-2"
        assertEquals(DriveProblem.Kind.UNAUTHORIZED, a.service.failed(folder, Payload.of(3_000, 3)).kind)
        assertEquals(BackupSchedule.Failure.UNAUTHORIZED, a.state.value.lastFailure)
    }

    @Test
    fun aSourceThatFailsOrIsNotABackupFormatWritesNothing() = runTest {
        val folder = ready()
        val before = writeCount()
        val failing = BackupSource { error("disk full") }
        assertEquals(DriveProblem.Kind.SOURCE_FAILED, (a.service.backUp(folder, failing) as BackupOutcome.Failed).problem.kind)
        val wrongFormat = Payload(ByteArray(10), 1, format = "doorprints-sync/1")
        assertEquals(DriveProblem.Kind.SOURCE_FAILED, a.service.failed(folder, wrongFormat).kind)
        assertEquals(before, writeCount())
    }

    /** A provider whose P-256 key derivation throws [boom] (what a phone's platform may do and the JVM never does). */
    private class BrokenProvider(private val inner: app.doorprints.crypto.CryptoProvider, private val boom: () -> Throwable) :
        app.doorprints.crypto.CryptoProvider by inner {
        override fun p256FromScalar(scalar: ByteArray): app.doorprints.crypto.P256PrivateKey = throw boom()
    }

    private var madeKey: RecoveryKey? = null

    private suspend fun joinWith(boom: () -> Throwable): DriveConnection.Error {
        val key = madeKey ?: a.service.createFolder(withRecoveryKey = true).recoveryKey!!.also { madeKey = it }
        val b = Rig(server, "Phone", BrokenProvider(p, boom))
        return b.service.openWithRecoveryKey(key) as DriveConnection.Error
    }

    @Test
    fun anUnexpectedExceptionOnTheJoinPathCarriesItsClassNameAndStepNeverItsMessage() = runTest {
        val e = joinWith { java.security.ProviderException("Keystore says secret-token-123 at https://x.example/y") }
        assertEquals(DriveProblem.Kind.SOURCE_FAILED, e.problem.kind)
        assertEquals("join-recover/java.security.ProviderException", e.problem.code)
        assertFalse(e.toString().contains("secret-token-123"))
    }

    @Test
    fun anErrorSubclassOnTheJoinPathBecomesAScreenWithACodeInsteadOfEscaping() = runTest {
        assertEquals("join-recover/java.lang.NoClassDefFoundError", joinWith { NoClassDefFoundError("Lorg/conscrypt/Missing;") }.problem.code)
        assertEquals("join-recover/java.lang.ExceptionInInitializerError", joinWith { ExceptionInInitializerError("boom") }.problem.code)
        assertEquals("join-recover/java.lang.OutOfMemoryError", joinWith { OutOfMemoryError("heap") }.problem.code)
    }

    @Test
    fun aCancellationOnTheJoinPathStillPropagates() = runTest {
        val out = a.service.createFolder(withRecoveryKey = true)
        val b = Rig(server, "Phone", BrokenProvider(p) { kotlinx.coroutines.CancellationException("stop") })
        try {
            b.service.openWithRecoveryKey(out.recoveryKey!!)
            fail("expected the cancellation to propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
    }

    @Test
    fun aTypedFailureOnTheJoinPathHasNoCode() = runTest {
        val out = a.service.createFolder(withRecoveryKey = true)
        val b = Rig(server, "Tablet")
        val wrong = b.service.openWithRecoveryKey(RecoveryKey.generate(p)) as DriveConnection.Error
        assertNull(wrong.problem.code)
        server.faults.always(DriveFault.Offline)
        val offline = b.service.openWithRecoveryKey(out.recoveryKey!!) as DriveConnection.Error
        assertEquals(DriveProblem.Kind.OFFLINE, offline.problem.kind)
        assertNull(offline.problem.code)
    }

    @Test
    fun theConnectAndCreateStepsNameThemselves() = runTest {
        val rig = Rig(server, "Phone")
        val drive = object : app.doorprints.drive.DriveClient by rig.drive {
            override suspend fun list(query: app.doorprints.drive.DriveQuery, pageToken: String?, pageSize: Int): app.doorprints.drive.DrivePage =
                throw IllegalArgumentException("Drive said secret-id-42")
        }
        val service = DriveBackupService(drive, p, rig.identity, rig.state, rig.trust, { server.clock.now() }, { 330 })
        val c = service.connect() as DriveConnection.Error
        assertEquals("connect/java.lang.IllegalArgumentException", c.problem.code)
        val created = service.createFolder(withRecoveryKey = true)
        assertEquals("create/java.lang.IllegalArgumentException", (created.connection as DriveConnection.Error).problem.code)
        assertNull(created.recoveryKey)
    }

    @Test
    fun theJoinNamesTheStepWhereItFailed() = runTest {
        val out = a.service.createFolder(withRecoveryKey = true)
        val key = out.recoveryKey!!
        // locate: the folder lookup throws.
        val rig = Rig(server, "Phone")
        val locating = object : app.doorprints.drive.DriveClient by rig.drive {
            override suspend fun list(query: app.doorprints.drive.DriveQuery, pageToken: String?, pageSize: Int): app.doorprints.drive.DrivePage =
                throw UnsupportedOperationException("x")
        }
        val s1 = DriveBackupService(locating, p, rig.identity, rig.state, rig.trust, { server.clock.now() }, { 330 })
        assertEquals("join-locate/java.lang.UnsupportedOperationException", (s1.openWithRecoveryKey(key) as DriveConnection.Error).problem.code)
        // add: wrapping the folder key for this device needs a fresh key pair, which the provider refuses.
        var refuse = false
        val adding = Rig(server, "Phone2", object : app.doorprints.crypto.CryptoProvider by p {
            override fun p256Generate(): app.doorprints.crypto.P256PrivateKey =
                if (refuse) throw java.security.ProviderException("x") else p.p256Generate()
        })
        refuse = true
        assertEquals("join-add/java.security.ProviderException", (adding.service.openWithRecoveryKey(key) as DriveConnection.Error).problem.code)
        // write: the keys.json update throws.
        val rig3 = Rig(server, "Phone3")
        val writing = object : app.doorprints.drive.DriveClient by rig3.drive {
            override suspend fun upload(target: app.doorprints.drive.UploadTarget, content: ByteArray): app.doorprints.drive.DriveFile =
                throw IllegalStateException("x")
        }
        val s3 = DriveBackupService(writing, p, rig3.identity, rig3.state, rig3.trust, { server.clock.now() }, { 330 })
        assertEquals("join-write/java.lang.IllegalStateException", (s3.openWithRecoveryKey(key) as DriveConnection.Error).problem.code)
    }

    @Test
    fun aPhoneWhoseDeviceKeyIsNotMadeYetCanJoinWithTheRecoveryKey() = runTest {
        // The app's wiring (DriveAssembly): the device key is made on first use, and once the folder is pinned a missing key
        // is "lost", never remade. The join pins the folder, so the key must exist before the pin is made.
        val key = a.service.createFolder(withRecoveryKey = true).recoveryKey!!
        val backend = app.doorprints.drive.device.FakeKeyBackend()
        val state = MemoryStateStore()
        val trust = MemoryTrust()
        val identity = app.doorprints.drive.device.KeystoreDeviceIdentity(backend, "Phone", app.doorprints.crypto.DevicePlatform.ANDROID) {
            state.value.rootId?.let { trust.keys(it).load() != null } ?: false
        }
        val service = DriveBackupService(
            app.doorprints.drive.InMemoryFakeDrive(server), app.doorprints.drive.device.DeviceKeyCryptoProvider(p), identity, state, trust,
            { server.clock.now() }, { 330 },
        )
        assertEquals(DriveConnection.Kind.NEEDS_ENROLMENT, service.connect().kind)
        assertEquals(0, backend.creates)
        val joined = service.openWithRecoveryKey(key)
        assertEquals((joined as? DriveConnection.Error)?.problem?.code, DriveConnection.Kind.READY, joined.kind)
        assertEquals(1, backend.creates)
        assertNotNull((joined as DriveConnection.Ready).folder.keys.body.device(app.doorprints.crypto.kidOf(p, identity.key.publicKey)))
    }

    @Test
    fun aSourceThatFailsToOpenNamesTheBackupStep() = runTest {
        val folder = ready()
        val failing = BackupSource { throw java.io.FileNotFoundException("/data/user/0/app/files/secret.zip") }
        val outcome = a.service.backUp(folder, failing) as BackupOutcome.Failed
        assertEquals(DriveProblem.Kind.SOURCE_FAILED, outcome.problem.kind)
        assertEquals("backup/java.io.FileNotFoundException", outcome.problem.code)
    }
}
