package app.doorprints.drive.store

import app.doorprints.drive.backup.BackupSchedule
import app.doorprints.drive.backup.DriveDeviceState
import app.doorprints.drive.backup.DriveStateStore
import app.doorprints.drive.photo.PhotoBad
import app.doorprints.drive.photo.PhotoRef
import app.doorprints.drive.photo.PhotoSkipReason
import app.doorprints.drive.photo.PhotoState
import app.doorprints.drive.photo.PhotoStateStore
import app.doorprints.drive.sync.DriveSyncState
import app.doorprints.drive.sync.SyncPeer
import app.doorprints.drive.sync.SyncStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/*
 * The device's own Drive bookkeeping as JSON files (docs/15 §5.1, §7.1): written whole-or-nothing, never in a backup, no
 * token or key in them (a device id and a Drive file id are not secrets). Web twin: the `state` and `photo-state` stores
 * of `drive-db.ts`. A file that is damaged reads as the empty state, as the web's missing record does: the next pass
 * finds the folder again by `appProperties` (docs/15 §7.1) instead of crashing. Keep them in `Context.noBackupFilesDir`.
 */

/** [DriveDeviceState] in [file]. */
class FileDriveStateStore(file: File) : DriveStateStore {
    private val state = file.atomic()

    override suspend fun load(): DriveDeviceState = withContext(Dispatchers.IO) {
        readState(state, DeviceDto.serializer(), { it.v }) { it.toState() } ?: DriveDeviceState()
    }

    override suspend fun save(state: DriveDeviceState) = withContext(Dispatchers.IO) {
        writeState(this@FileDriveStateStore.state, DeviceDto.serializer(), DeviceDto.of(state))
    }

    @Serializable
    private class DeviceDto(
        val v: Int,
        val deviceId: String? = null,
        val rootId: String? = null,
        val backupsId: String? = null,
        val keysId: String? = null,
        val controlId: String? = null,
        val creatingRootId: String? = null,
        val lastBackupId: String? = null,
        val lastSuccessAt: Long? = null,
        val lastAttemptAt: Long? = null,
        val lastFailure: String? = null,
        val lastVerifyAt: Long? = null,
        val newestSeenAt: Long? = null,
        val confirmedDrops: List<String> = emptyList(),
    ) {
        fun toState() = DriveDeviceState(
            deviceId, rootId, backupsId, keysId, controlId, creatingRootId, lastBackupId, lastSuccessAt, lastAttemptAt,
            lastFailure?.let { name -> BackupSchedule.Failure.entries.firstOrNull { it.name == name } },
            lastVerifyAt, newestSeenAt, confirmedDrops.toSet(),
        )

        companion object {
            fun of(s: DriveDeviceState) = DeviceDto(
                STATE_VERSION, s.deviceId, s.rootId, s.backupsId, s.keysId, s.controlId, s.creatingRootId, s.lastBackupId,
                s.lastSuccessAt, s.lastAttemptAt, s.lastFailure?.name, s.lastVerifyAt, s.newestSeenAt, s.confirmedDrops.sorted(),
            )
        }
    }
}

/** [DriveSyncState] in [file]. */
class FileSyncStateStore(file: File) : SyncStateStore {
    private val state = file.atomic()

    override suspend fun load(): DriveSyncState = withContext(Dispatchers.IO) {
        readState(state, SyncDto.serializer(), { it.v }) { it.toState() } ?: DriveSyncState()
    }

    override suspend fun save(state: DriveSyncState) = withContext(Dispatchers.IO) {
        writeState(this@FileSyncStateStore.state, SyncDto.serializer(), SyncDto.of(state))
    }

    @Serializable
    private class PeerDto(val highestSeq: Long = 0, val mergedChecksum: String? = null)

    @Serializable
    private class SyncDto(
        val v: Int,
        val syncFolderId: String? = null,
        val lastSeq: Long = 0,
        val confirmedSeq: Long = 0,
        val lastFileId: String? = null,
        val lastChecksum: String? = null,
        val lastRowsHash: String? = null,
        val generation: Long = 0,
        val peers: Map<String, PeerDto> = emptyMap(),
        val failures: Int = 0,
        val notBefore: Long = 0,
    ) {
        fun toState() = DriveSyncState(
            syncFolderId, lastSeq, confirmedSeq, lastFileId, lastChecksum, lastRowsHash, generation,
            peers.mapValues { SyncPeer(it.value.highestSeq, it.value.mergedChecksum) }, failures, notBefore,
        )

        companion object {
            fun of(s: DriveSyncState) = SyncDto(
                STATE_VERSION, s.syncFolderId, s.lastSeq, s.confirmedSeq, s.lastFileId, s.lastChecksum, s.lastRowsHash,
                s.generation, s.peers.mapValues { PeerDto(it.value.highestSeq, it.value.mergedChecksum) }, s.failures, s.notBefore,
            )
        }
    }
}

/** [PhotoState] in [file]. */
class FilePhotoStateStore(file: File) : PhotoStateStore {
    private val state = file.atomic()

    override suspend fun load(): PhotoState = withContext(Dispatchers.IO) {
        readState(state, PhotoDto.serializer(), { it.v }) { it.toState() } ?: PhotoState()
    }

    override suspend fun save(state: PhotoState) = withContext(Dispatchers.IO) {
        writeState(this@FilePhotoStateStore.state, PhotoDto.serializer(), PhotoDto.of(state))
    }

    @Serializable
    private class RefDto(val driveFileId: String, val sha256: String)

    @Serializable
    private class BadDto(val fileId: String, val reason: String, val at: Long)

    @Serializable
    private class PhotoDto(
        val v: Int,
        val photosFolderId: String? = null,
        val refs: Map<String, RefDto> = emptyMap(),
        val bad: Map<String, BadDto> = emptyMap(),
    ) {
        /** A "bad" entry whose reason this build does not know is dropped (the photo is tried again), the rest is kept. */
        fun toState() = PhotoState(
            photosFolderId,
            refs.mapValues { PhotoRef(it.value.driveFileId, it.value.sha256) },
            bad.mapNotNull { (id, b) ->
                PhotoSkipReason.entries.firstOrNull { it.name == b.reason }?.let { id to PhotoBad(b.fileId, it, b.at) }
            }.toMap(),
        )

        companion object {
            fun of(s: PhotoState) = PhotoDto(
                STATE_VERSION, s.photosFolderId,
                s.refs.mapValues { RefDto(it.value.driveFileId, it.value.sha256) },
                s.bad.mapValues { BadDto(it.value.fileId, it.value.reason.name, it.value.at) },
            )
        }
    }
}
