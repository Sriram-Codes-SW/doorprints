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

import app.doorprints.concurrent.PlatformLock
import app.doorprints.crypto.KeysWatermark
import app.doorprints.drive.backup.ControlWatermark
import app.doorprints.drive.backup.DriveDeviceState
import app.doorprints.drive.delete.DeletedMarker
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionItem
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.ItemKind
import app.doorprints.drive.delete.PendingDeletion
import app.doorprints.drive.photo.PhotoRef
import app.doorprints.drive.photo.PhotoState
import app.doorprints.drive.sync.DriveSyncState
import app.doorprints.drive.sync.SyncPeer
import app.doorprints.drive.wiring.FileDriveLockStore
import app.doorprints.drive.wiring.FileDrivePrefs
import app.doorprints.testing.blocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.io.IOException

/**
 * The Drive stores over a [StateFile] in memory: the same rules on the host and on the iPhone simulator, where the real
 * file is `IosStateFile` (the Android files are covered by `:app`'s tests over `AtomicJsonFile`).
 */
class StateFileStoresTest {
    private val device = DriveDeviceState(deviceId = "dev-1", rootId = "root", backupsId = "bk", lastSuccessAt = 11, confirmedDrops = setOf("b1", "b2"))

    // ---- the state stores ----

    @Test
    fun theDeviceStateRoundTripsAndANewInstanceReadsIt() = blocking {
        val file = MemoryStateFile()
        FileDriveStateStore(file).save(device)
        assertEquals(device, FileDriveStateStore(file).load())
        assertEquals("root", FileDriveStateStore(file).loadNow().rootId)
    }

    @Test
    fun aMissingCorruptOrForeignVersionFileReadsAsAbsent() = blocking {
        val file = MemoryStateFile()
        assertEquals(DriveDeviceState(), FileDriveStateStore(file).load())
        file.text = "{not json"
        assertEquals(DriveDeviceState(), FileDriveStateStore(file).load())
        file.text = "{\"v\":2,\"deviceId\":\"x\"}"
        assertEquals(DriveDeviceState(), FileDriveStateStore(file).load())
        file.text = "   "
        assertEquals(DriveDeviceState(), FileDriveStateStore(file).load())
    }

    @Test
    fun aFailedWriteThrowsAndKeepsTheOldState() = blocking {
        val file = MemoryStateFile()
        val store = FileDriveStateStore(file)
        store.save(device)
        file.failWrites = true
        assertFailsWith<IOException> { store.save(device.copy(rootId = "other")) }
        assertEquals("root", store.load().rootId)
    }

    @Test
    fun theSyncAndPhotoStatesRoundTrip() = blocking {
        val sync = DriveSyncState(syncFolderId = "sf", lastSeq = 7, peers = mapOf("p1" to SyncPeer(5, "m1")), failures = 2, notBefore = 99)
        val photo = PhotoState(photosFolderId = "pf", refs = mapOf("ph1" to PhotoRef("f1", "ab12")))
        val syncFile = MemoryStateFile("s")
        val photoFile = MemoryStateFile("p")
        FileSyncStateStore(syncFile).save(sync)
        FilePhotoStateStore(photoFile).save(photo)
        assertEquals(sync, FileSyncStateStore(syncFile).load())
        assertEquals(photo, FilePhotoStateStore(photoFile).load())
    }

    // ---- the pins ----

    @Test
    fun aWatermarkChangesOnlyFromTheValueTheCallerSawAndASecondWriterLoses() {
        val file = MemoryStateFile("trust/keys")
        val a = FileKeysWatermarkStore(file)
        val b = FileKeysWatermarkStore(file)
        val first = KeysWatermark(1, 1L, ByteArray(16) { 1 }, ByteArray(32) { 2 })
        val second = KeysWatermark(1, 2L, ByteArray(16) { 1 }, ByteArray(32) { 3 })
        assertNull(a.load())
        assertTrue(a.compareAndSet(null, first))
        // b still believes there is no pin: refused, and the pin a wrote stays.
        assertFalse(b.compareAndSet(null, second))
        assertEquals(1L, b.load()!!.revision)
        assertTrue(b.compareAndSet(b.load(), second))
        assertEquals(2L, a.load()!!.revision)
        assertTrue(a.load()!!.bodyHash.contentEquals(ByteArray(32) { 3 }))
    }

    @Test
    fun theControlWatermarkFollowsTheSameRule() {
        val file = MemoryStateFile("trust/control")
        val store = FileControlWatermarkStore(file)
        val first = ControlWatermark(1L, ByteArray(32) { 4 }, null)
        assertTrue(store.compareAndSet(null, first))
        val next = ControlWatermark(2L, ByteArray(32) { 4 }, 5L)
        assertFalse(store.compareAndSet(null, next))
        assertTrue(store.compareAndSet(first, next))
        assertEquals(5L, store.load()!!.backupsDeletedAt)
    }

    @Test
    fun aDamagedPinReadsAsNoPin() {
        val file = MemoryStateFile("trust/keys")
        file.text = "{\"v\":1,\"epoch\":1,\"revision\":1,\"keyId\":\"zz\",\"bodyHash\":\"00\"}"
        assertNull(FileKeysWatermarkStore(file).load())
    }

    @Test
    fun aFolderIdThatCouldNamePathsIsHashedIntoTheFileName() {
        assertEquals("keys-watermark-abc_DEF-1.json", folderFileName("keys-watermark", "abc_DEF-1"))
        val hashed = folderFileName("keys-watermark", "../../etc/passwd")
        assertTrue(hashed.startsWith("keys-watermark-h."))
        assertFalse(hashed.contains('/'))
        assertNotEquals(hashed, folderFileName("keys-watermark", "../../etc/passwd2"))
        assertFailsWith<IllegalArgumentException> { folderFileName("keys-watermark", " ") }
    }

    // ---- the deletion store ----

    private val pending = PendingDeletion(
        "op-1", DeletionLevel.L2, DeletionAction.OneBackup("file-1"), "root",
        listOf(DeletionItem("f1", ItemKind.BACKUP, 10, 0)), 1, 5L,
    )

    @Test
    fun aPendingDeletionSurvivesANewInstanceAndClears() = blocking {
        val pendingFile = MemoryStateFile("d/p")
        val markerFile = MemoryStateFile("d/m")
        val store = FileDeletionStore(pendingFile, markerFile)
        assertNull(store.pending())
        store.savePending(pending)
        assertEquals(pending, FileDeletionStore(pendingFile, markerFile).pending())
        store.clearPending()
        assertNull(store.pending())
        assertFalse(pendingFile.exists())
        store.clearPending() // already gone: no error
    }

    @Test
    fun aFinishedDeletionRecordsTheMarkerAndForgetsTheFolderOnlyWhenAsked() = blocking {
        val driveFile = MemoryStateFile("d/drive")
        val drive = FileDriveStateStore(driveFile)
        drive.save(device)
        val sync = FileSyncStateStore(MemoryStateFile("d/sync"))
        sync.save(DriveSyncState(syncFolderId = "sf", lastSeq = 3))
        val photos = FilePhotoStateStore(MemoryStateFile("d/photos"))
        photos.save(PhotoState(photosFolderId = "pf"))
        val store = FileDeletionStore(MemoryStateFile("d/p2"), MemoryStateFile("d/m2"), FileFolderForgetter(drive, sync, photos))
        val marker = DeletedMarker(DeletionLevel.L3, 9L, "everything")
        store.recordFinished(marker, forgetFolder = false)
        assertEquals("root", drive.load().rootId)
        assertEquals(marker, store.marker())
        store.recordFinished(marker, forgetFolder = true)
        // The folder is forgotten, the device id is kept (it is this phone's, not the folder's).
        assertNull(drive.load().rootId)
        assertEquals("dev-1", drive.load().deviceId)
        assertEquals(DriveSyncState(), sync.load())
        assertEquals(PhotoState(), photos.load())
    }

    // ---- the preferences and the lock memory ----

    @Test
    fun thePreferencesReadWhatWasPutAndParseAnUnchangedFileOnce() {
        val file = MemoryStateFile("prefs")
        val prefs = FileDrivePrefs(file)
        assertFalse(prefs.exists())
        assertNull(prefs.get("a"))
        prefs.put("a", "1")
        prefs.put("b", "2")
        assertTrue(prefs.exists())
        val before = prefs.parses
        assertEquals("1", prefs.get("a"))
        assertEquals("2", prefs.get("b"))
        assertEquals("1", prefs.get("a"))
        assertEquals(before + 1, prefs.parses, "three reads of an unchanged file parse it once")
        assertEquals("1", FileDrivePrefs(file).get("a"))
    }

    @Test
    fun aPreferenceWriteThatFailsIsDroppedAndDoesNotNotify() {
        val file = MemoryStateFile("prefs")
        val prefs = FileDrivePrefs(file)
        var told = 0
        prefs.onChange = { _, _ -> told++ }
        file.failWrites = true
        prefs.put("a", "1")
        assertNull(prefs.get("a"))
        assertEquals(0, told)
        file.failWrites = false
        prefs.put("a", "1")
        assertEquals(1, told)
        prefs.engaged = true
        assertTrue(prefs.engaged)
    }

    @Test
    fun aDamagedPreferencesFileReadsAsNothingSet() {
        val file = MemoryStateFile("prefs")
        file.text = "[1,2"
        assertNull(FileDrivePrefs(file).get("a"))
        assertFalse(FileDrivePrefs(file).engaged)
    }

    @Test
    fun theLockStoreKeepsFourFlagsApartAndClearsThemTogether() {
        val file = MemoryStateFile("lock")
        val store = FileDriveLockStore(file)
        assertFalse(store.paused)
        store.paused = true
        store.keyStoreFault = true
        val again = FileDriveLockStore(file)
        assertTrue(again.paused)
        assertTrue(again.keyStoreFault)
        assertFalse(again.needsReenrolment)
        assertFalse(again.keyDropped)
        again.clear()
        assertFalse(FileDriveLockStore(file).paused)
        assertFalse(FileDriveLockStore(file).keyStoreFault)
    }

    @Test
    fun aLockFileFromBeforeTheKeyStoreFaultReadsAsNoFault() {
        val file = MemoryStateFile("lock")
        file.text = "{\"paused\":true,\"needsReenrolment\":true,\"keyDropped\":false}"
        val store = FileDriveLockStore(file)
        assertTrue(store.paused)
        assertFalse(store.keyStoreFault)
    }

    // ---- the lock registry ----

    @Test
    fun oneLockPerPathAndTheSameLockForASlashedSpelling() {
        assertSame(PathLocks.of("x/y.json"), PathLocks.of("x/y.json"))
        assertSame(PathLocks.of("x/y"), PathLocks.of("x/y/"))
        assertNotEquals<PlatformLock>(PathLocks.of("x/y.json"), PathLocks.of("x/z.json"))
    }
}
