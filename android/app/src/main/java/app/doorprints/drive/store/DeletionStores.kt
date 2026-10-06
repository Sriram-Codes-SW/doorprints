package app.doorprints.drive.store

import app.doorprints.drive.backup.DriveStateStore
import app.doorprints.drive.delete.DeletedMarker
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionItem
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.DeletionStore
import app.doorprints.drive.delete.ItemKind
import app.doorprints.drive.delete.PendingDeletion
import app.doorprints.drive.photo.PhotoState
import app.doorprints.drive.photo.PhotoStateStore
import app.doorprints.drive.sync.DriveSyncState
import app.doorprints.drive.sync.SyncStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** What a finished "delete everything" must forget on this device: the folder ids (docs/15 §3.4). */
fun interface FolderForgetter {
    suspend fun forgetFolder()
}

/**
 * Drops the Drive folder from the three device stores: the ids of the folder and its parts, the sync and photo
 * bookkeeping (all of it describes files that are gone). The device id stays: it names this device, not the folder.
 */
class FileFolderForgetter(
    private val drive: DriveStateStore,
    private val sync: SyncStateStore,
    private val photos: PhotoStateStore,
) : FolderForgetter {
    override suspend fun forgetFolder() {
        val d = drive.load()
        drive.save(app.doorprints.drive.backup.DriveDeviceState(deviceId = d.deviceId))
        sync.save(DriveSyncState())
        photos.save(PhotoState())
    }
}

/**
 * [DeletionStore] in two files of [dir]: `deletion-pending.json` (the list written before the first delete, shrunk as
 * files go) and `deletion-marker.json` (what "nothing comes back by itself" remembers). [recordFinished] writes the
 * marker first and only then forgets the folder, so a stop in between is finished by the next call (both steps repeat
 * safely). A damaged file reads as absent. Web twin: the `deletion-pending` store of `drive-db.ts`, which does not yet
 * forget the folder.
 */
class FileDeletionStore(dir: File, private val forgetter: FolderForgetter? = null) : DeletionStore {
    private val pendingFile = File(dir, "deletion-pending.json").atomic()
    private val markerFile = File(dir, "deletion-marker.json").atomic()

    override suspend fun pending(): PendingDeletion? = withContext(Dispatchers.IO) {
        readState(pendingFile, PendingDto.serializer(), { it.v }) { it.toPending() }
    }

    override suspend fun savePending(pending: PendingDeletion) = withContext(Dispatchers.IO) {
        writeState(pendingFile, PendingDto.serializer(), PendingDto.of(pending))
    }

    override suspend fun clearPending() = withContext(Dispatchers.IO) {
        synchronized(PathLocks.of(pendingFile.file)) {
            if (pendingFile.file.exists() && !pendingFile.file.delete()) throw java.io.IOException("cannot remove the pending list")
        }
    }

    override suspend fun marker(): DeletedMarker? = withContext(Dispatchers.IO) {
        readState(markerFile, MarkerDto.serializer(), { it.v }) { DeletedMarker(DeletionLevel.valueOf(it.level), it.atMs, it.action) }
    }

    override suspend fun recordFinished(marker: DeletedMarker, forgetFolder: Boolean) {
        withContext(Dispatchers.IO) {
            writeState(markerFile, MarkerDto.serializer(), MarkerDto(STATE_VERSION, marker.level.name, marker.atMs, marker.action))
        }
        if (forgetFolder) forgetter?.forgetFolder()
    }

    @Serializable
    private class MarkerDto(val v: Int, val level: String, val atMs: Long, val action: String)

    @Serializable
    private class ActionDto(val type: String, val fileId: String? = null)

    @Serializable
    private class ItemDto(val id: String, val kind: String, val bytes: Long, val phase: Int)

    @Serializable
    private class PendingDto(
        val v: Int,
        val operationId: String,
        val level: String,
        val action: ActionDto,
        val rootId: String,
        val items: List<ItemDto>,
        val total: Int,
        val createdAtMs: Long,
    ) {
        fun toPending() = PendingDeletion(
            operationId, DeletionLevel.valueOf(level), actionOf(action), rootId,
            items.map { i -> DeletionItem(i.id, ItemKind.entries.first { it.code == i.kind }, i.bytes, i.phase) },
            total, createdAtMs,
        )

        companion object {
            fun of(p: PendingDeletion) = PendingDto(
                STATE_VERSION, p.operationId, p.level.name, dtoOf(p.action), p.rootId,
                p.items.map { ItemDto(it.id, it.kind.code, it.bytes, it.phase) }, p.total, p.createdAtMs,
            )

            private fun dtoOf(a: DeletionAction) = when (a) {
                is DeletionAction.OneBackup -> ActionDto("oneBackup", a.fileId)
                DeletionAction.OlderBackups -> ActionDto("olderBackups")
                DeletionAction.AllBackups -> ActionDto("allBackups")
                DeletionAction.Everything -> ActionDto("everything")
            }

            private fun actionOf(a: ActionDto): DeletionAction = when (a.type) {
                "oneBackup" -> DeletionAction.OneBackup(requireNotNull(a.fileId))
                "olderBackups" -> DeletionAction.OlderBackups
                "allBackups" -> DeletionAction.AllBackups
                "everything" -> DeletionAction.Everything
                else -> throw IllegalArgumentException("unknown action")
            }
        }
    }
}
