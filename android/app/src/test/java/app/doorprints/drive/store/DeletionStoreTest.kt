package app.doorprints.drive.store

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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** DeletionStore: the pending list before the first delete, the marker after, and "forget the folder". */
class DeletionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pending(action: DeletionAction, level: DeletionLevel = DeletionLevel.L2) = PendingDeletion(
        operationId = "op-1", level = level, action = action, rootId = "root",
        items = ItemKind.entries.mapIndexed { i, k -> DeletionItem("id$i", k, i * 10L, i) },
        total = 8, createdAtMs = 1234,
    )

    @Test
    fun nothingStoredGivesNothing() = runBlocking {
        val s = FileDeletionStore(tmp.root)
        assertNull(s.pending())
        assertNull(s.marker())
    }

    @Test
    fun pendingRoundTripsForEveryActionAndSurvivesANewInstance() = runBlocking {
        val actions = listOf(
            DeletionAction.OneBackup("file-9"), DeletionAction.OlderBackups, DeletionAction.AllBackups, DeletionAction.Everything,
        )
        for (a in actions) {
            val p = pending(a, DeletionLevel.L3)
            FileDeletionStore(tmp.root).savePending(p)
            assertEquals(p, FileDeletionStore(tmp.root).pending())
        }
    }

    @Test
    fun savingPendingAgainShrinksTheList() = runBlocking {
        val s = FileDeletionStore(tmp.root)
        val p = pending(DeletionAction.AllBackups)
        s.savePending(p)
        s.savePending(p.copy(items = p.items.drop(5)))
        assertEquals(3, s.pending()!!.items.size)
    }

    @Test
    fun clearPendingRemovesItAndKeepsTheMarker() = runBlocking {
        val s = FileDeletionStore(tmp.root)
        val m = DeletedMarker(DeletionLevel.L2, 5, "allBackups")
        s.savePending(pending(DeletionAction.AllBackups))
        s.recordFinished(m, forgetFolder = false)
        s.clearPending()
        assertNull(FileDeletionStore(tmp.root).pending())
        assertEquals(m, FileDeletionStore(tmp.root).marker())
    }

    @Test
    fun markerRoundTripsForBothLevels() = runBlocking {
        for (m in listOf(DeletedMarker(DeletionLevel.L2, 1, "allBackups"), DeletedMarker(DeletionLevel.L3, 2, "everything"))) {
            FileDeletionStore(tmp.root).recordFinished(m, forgetFolder = false)
            assertEquals(m, FileDeletionStore(tmp.root).marker())
        }
    }

    @Test
    fun corruptPendingAndMarkerReadAsAbsent() = runBlocking {
        val s = FileDeletionStore(tmp.root)
        s.savePending(pending(DeletionAction.AllBackups))
        s.recordFinished(DeletedMarker(DeletionLevel.L2, 1, "allBackups"), false)
        for (f in tmp.root.listFiles()!!) f.writeText("{\"v\":1,\"oper")
        assertNull(s.pending())
        assertNull(s.marker())
    }

    @Test
    fun anUnknownItemKindMakesThePendingAbsentNotHalfRead() = runBlocking {
        val s = FileDeletionStore(tmp.root)
        s.savePending(pending(DeletionAction.AllBackups))
        val f = tmp.root.listFiles()!!.single()
        f.writeText(f.readText().replace("\"backup\"", "\"martian\""))
        assertNull(s.pending())
    }

    @Test
    fun recordFinishedWithForgetFolderDropsTheFolderIdsEverywhereButKeepsTheDeviceId() = runBlocking {
        val drive = FileDriveStateStore(File(tmp.root, "d.json"))
        val sync = FileSyncStateStore(File(tmp.root, "s.json"))
        val photos = FilePhotoStateStore(File(tmp.root, "p.json"))
        drive.save(DriveDeviceState(deviceId = "dev", rootId = "r", backupsId = "b", keysId = "k", controlId = "c", creatingRootId = "cr", lastBackupId = "lb", newestSeenAt = 3, confirmedDrops = setOf("x")))
        sync.save(DriveSyncState(syncFolderId = "sf", lastSeq = 4, peers = mapOf("p" to SyncPeer(1, "m"))))
        photos.save(PhotoState(photosFolderId = "pf", refs = mapOf("a" to PhotoRef("f", "00"))))
        val store = FileDeletionStore(File(tmp.root, "del"), FileFolderForgetter(drive, sync, photos))

        val m = DeletedMarker(DeletionLevel.L3, 9, "everything")
        store.recordFinished(m, forgetFolder = true)

        assertEquals(m, store.marker())
        assertEquals(DriveDeviceState(deviceId = "dev"), drive.load())
        assertEquals(DriveSyncState(), sync.load())
        assertEquals(PhotoState(), photos.load())
    }

    @Test
    fun recordFinishedWithoutForgetFolderKeepsTheFolderIds() = runBlocking {
        val drive = FileDriveStateStore(File(tmp.root, "d.json"))
        val before = DriveDeviceState(deviceId = "dev", rootId = "r", backupsId = "b")
        drive.save(before)
        val store = FileDeletionStore(File(tmp.root, "del"), FileFolderForgetter(drive, FileSyncStateStore(File(tmp.root, "s.json")), FilePhotoStateStore(File(tmp.root, "p.json"))))
        store.recordFinished(DeletedMarker(DeletionLevel.L2, 9, "allBackups"), forgetFolder = false)
        assertEquals(before, drive.load())
    }

    @Test
    fun theMarkerIsWrittenBeforeTheFolderIsForgotten() = runBlocking {
        var markerSeenWhenForgetting: DeletedMarker? = null
        lateinit var store: FileDeletionStore
        store = FileDeletionStore(tmp.root) { markerSeenWhenForgetting = store.marker() }
        val m = DeletedMarker(DeletionLevel.L3, 9, "everything")
        store.recordFinished(m, forgetFolder = true)
        assertEquals(m, markerSeenWhenForgetting)
    }
}
