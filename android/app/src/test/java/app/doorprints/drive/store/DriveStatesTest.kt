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

package app.doorprints.drive.store

import app.doorprints.drive.backup.BackupSchedule
import app.doorprints.drive.backup.DriveDeviceState
import app.doorprints.drive.photo.PhotoBad
import app.doorprints.drive.photo.PhotoRef
import app.doorprints.drive.photo.PhotoSkipReason
import app.doorprints.drive.photo.PhotoState
import app.doorprints.drive.sync.DriveSyncState
import app.doorprints.drive.sync.SyncPeer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** DriveStateStore, SyncStateStore, PhotoStateStore: round trips, a new instance on the same files, corrupt means absent. */
class DriveStatesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String) = File(tmp.root, name)

    private val device = DriveDeviceState(
        deviceId = "dev-1", rootId = "root", backupsId = "bk", keysId = "ky", controlId = "ct", creatingRootId = "cr",
        lastBackupId = "lb", lastSuccessAt = 11, lastAttemptAt = 12, lastFailure = BackupSchedule.Failure.QUOTA,
        lastVerifyAt = 13, newestSeenAt = 14, confirmedDrops = setOf("b1", "b2"),
    )

    private val sync = DriveSyncState(
        syncFolderId = "sf", lastSeq = 7, confirmedSeq = 6, lastFileId = "lf", lastChecksum = "cs", lastRowsHash = "rh",
        generation = 3, peers = mapOf("p1" to SyncPeer(5, "m1"), "p2" to SyncPeer(0, null)), failures = 2, notBefore = 99,
    )

    private val photo = PhotoState(
        photosFolderId = "pf",
        refs = mapOf("ph1" to PhotoRef("f1", "ab12"), "ph2" to PhotoRef("f2", "cd34")),
        bad = mapOf("ph3" to PhotoBad("f3", PhotoSkipReason.CHECKSUM_MISMATCH, 55)),
    )

    @Test
    fun absentFilesGiveTheEmptyStates() = runBlocking {
        assertEquals(DriveDeviceState(), FileDriveStateStore(file("d.json")).load())
        assertEquals(DriveSyncState(), FileSyncStateStore(file("s.json")).load())
        assertEquals(PhotoState(), FilePhotoStateStore(file("p.json")).load())
    }

    @Test
    fun deviceStateRoundTripsEveryFieldAndSurvivesANewInstance() = runBlocking {
        FileDriveStateStore(file("d.json")).save(device)
        assertEquals(device, FileDriveStateStore(file("d.json")).load())
    }

    @Test
    fun deviceStateKeepsEachFailureKind() = runBlocking {
        for (kind in BackupSchedule.Failure.entries) {
            val s = FileDriveStateStore(file("d-$kind.json"))
            s.save(DriveDeviceState(lastFailure = kind))
            assertEquals(kind, s.load().lastFailure)
        }
    }

    @Test
    fun savingAgainReplacesTheState() = runBlocking {
        val s = FileDriveStateStore(file("d.json"))
        s.save(device)
        s.save(DriveDeviceState(deviceId = "dev-2"))
        assertEquals(DriveDeviceState(deviceId = "dev-2"), s.load())
    }

    @Test
    fun syncStateRoundTripsAndSurvivesANewInstance() = runBlocking {
        FileSyncStateStore(file("s.json")).save(sync)
        assertEquals(sync, FileSyncStateStore(file("s.json")).load())
    }

    @Test
    fun photoStateRoundTripsAndSurvivesANewInstance() = runBlocking {
        FilePhotoStateStore(file("p.json")).save(photo)
        assertEquals(photo, FilePhotoStateStore(file("p.json")).load())
    }

    @Test
    fun garbageTruncatedAndWrongShapeFilesReadAsEmptyWithoutCrashing() = runBlocking {
        val bad = listOf("not json", "{\"v\":1,\"deviceId\":\"d", "[]", "{\"v\":1,\"lastSeq\":\"seven\"}", "\u0000\u0000\u0000")
        for ((i, text) in bad.withIndex()) {
            file("d$i.json").writeText(text)
            file("s$i.json").writeText(text)
            file("p$i.json").writeText(text)
            assertEquals(DriveDeviceState(), FileDriveStateStore(file("d$i.json")).load())
            assertEquals(DriveSyncState(), FileSyncStateStore(file("s$i.json")).load())
            assertEquals(PhotoState(), FilePhotoStateStore(file("p$i.json")).load())
        }
    }

    @Test
    fun aNewerFormatVersionReadsAsEmptyInsteadOfBeingMisread() = runBlocking {
        file("d.json").writeText("{\"v\":2,\"deviceId\":\"from-the-future\"}")
        assertEquals(DriveDeviceState(), FileDriveStateStore(file("d.json")).load())
    }

    @Test
    fun aFileWithoutAVersionReadsAsEmpty() = runBlocking {
        file("d.json").writeText("{\"deviceId\":\"no-version\"}")
        assertEquals(DriveDeviceState(), FileDriveStateStore(file("d.json")).load())
    }

    @Test
    fun unknownFieldsAreIgnored() = runBlocking {
        file("d.json").writeText("{\"v\":1,\"deviceId\":\"d9\",\"somethingNew\":[1,2]}")
        assertEquals("d9", FileDriveStateStore(file("d.json")).load().deviceId)
    }

    @Test
    fun anUnknownFailureKindBecomesNoFailureAndKeepsTheRest() = runBlocking {
        file("d.json").writeText("{\"v\":1,\"deviceId\":\"d9\",\"lastFailure\":\"MARTIAN\"}")
        val s = FileDriveStateStore(file("d.json")).load()
        assertEquals("d9", s.deviceId)
        assertNull(s.lastFailure)
    }

    @Test
    fun anUnknownSkipReasonDropsOnlyThatBadEntry() = runBlocking {
        file("p.json").writeText(
            "{\"v\":1,\"photosFolderId\":\"pf\",\"refs\":{\"a\":{\"driveFileId\":\"f\",\"sha256\":\"00\"}}," +
                "\"bad\":{\"x\":{\"fileId\":\"f\",\"reason\":\"MARTIAN\",\"at\":1},\"y\":{\"fileId\":\"g\",\"reason\":\"EMPTY\",\"at\":2}}}",
        )
        val s = FilePhotoStateStore(file("p.json")).load()
        assertEquals(setOf("a"), s.refs.keys)
        assertEquals(setOf("y"), s.bad.keys)
    }

    @Test
    fun savingOverACorruptFileWorks() = runBlocking {
        file("d.json").writeText("garbage")
        val s = FileDriveStateStore(file("d.json"))
        s.save(device)
        assertEquals(device, s.load())
    }

    @Test
    fun theFilesHoldNoTokenOrKey() = runBlocking {
        FileDriveStateStore(file("d.json")).save(device)
        FileSyncStateStore(file("s.json")).save(sync)
        FilePhotoStateStore(file("p.json")).save(photo)
        for (n in listOf("d.json", "s.json", "p.json")) {
            val text = file(n).readText().lowercase()
            assertTrue(n, listOf("token", "secret", "private", "password").none { it in text })
        }
    }
}
