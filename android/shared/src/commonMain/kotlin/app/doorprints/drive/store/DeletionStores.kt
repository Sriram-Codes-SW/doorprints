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

import app.doorprints.drive.backup.DriveDeviceState
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
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** What a finished "delete everything" must forget on this device: the folder ids (docs/15 §3.4). */
fun interface FolderForgetter {
    /** Forgets the folder this device was bound to; safe to repeat. */
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
        drive.save(DriveDeviceState(deviceId = d.deviceId))
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
class FileDeletionStore(
    private val pendingFile: StateFile,
    private val markerFile: StateFile,
    private val forgetter: FolderForgetter? = null,
) : DeletionStore {
    constructor(dir: String, forgetter: FolderForgetter? = null) :
        this(stateFileAt("$dir/deletion-pending.json"), stateFileAt("$dir/deletion-marker.json"), forgetter)

    override suspend fun pending(): PendingDeletion? = withContext(Dispatchers.IO) {
        readState(pendingFile, PendingDto.serializer(), { it.v }) { it.toPending() }
    }

    override suspend fun savePending(pending: PendingDeletion) = withContext(Dispatchers.IO) {
        writeState(pendingFile, PendingDto.serializer(), PendingDto.of(pending))
    }

    override suspend fun clearPending() = withContext(Dispatchers.IO) {
        pendingFile.lock.withLock { pendingFile.delete() }
    }

    override suspend fun marker(): DeletedMarker? = withContext(Dispatchers.IO) {
        readState(markerFile, MarkerDto.serializer(), { it.v }) { DeletedMarker(DeletionLevel.valueOf(it.level), it.atMs, it.action) }
    }

    /**
      * Writes the marker first and forgets the folder second, so an interruption between the two is finished by calling
      * this again.
     */
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
