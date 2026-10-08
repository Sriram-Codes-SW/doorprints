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

package app.doorprints.drive.ios

import app.doorprints.crypto.ByteSource
import app.doorprints.data.Repository
import app.doorprints.data.toBundle
import app.doorprints.drive.backup.BackupPayload
import app.doorprints.drive.backup.BackupSource
import app.doorprints.drive.backup.StagingSink
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.CopyPhotos
import app.doorprints.shared.export.CopyWriter
import app.doorprints.shared.export.ExportFormat
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.PhotoScope
import app.doorprints.shared.export.PosixFileException
import app.doorprints.shared.export.PosixFileSink
import app.doorprints.shared.export.PosixFileSource
import app.doorprints.shared.export.iosArchiveTools
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The two scratch folders of the iPhone's Drive hand-offs, under `tmp/` (the system may empty it; nothing here is the only
 * copy of anything): the backup being encrypted for upload and the decrypted backup being handed to the Import screen. Both
 * hold a plain ZIP of the person's houses, so they are swept at start and each file is removed as soon as it is used.
 */
@OptIn(ExperimentalForeignApi::class)
object IosDriveFolders {
    private val root = NSTemporaryDirectory().trimEnd('/')

    /** The *Full backup* ZIP an upload reads from. */
    val backup: String get() = made("$root/drive-backup")

    /** The decrypted backup *Import a backup* takes from Drive; the Import screen copies it and deletes it. */
    val importing: String get() = made("$root/drive-import")

    /** True for a file directly inside [importing] (the Import screen then removes it once it has copied it). */
    fun isStagedImport(path: String?): Boolean {
        val name = path?.removePrefix("$root/drive-import/") ?: return false
        return path.startsWith("$root/drive-import/") && name.isNotEmpty() && '/' !in name && name != ".." && name != "."
    }

    /** Removes everything directly inside [folder]. */
    fun sweep(folder: String) {
        val manager = NSFileManager.defaultManager
        manager.contentsOfDirectoryAtPath(folder, error = null)?.forEach { name -> manager.removeItemAtPath("$folder/$name", error = null) }
    }

    /** Removes the file at [path] if there is one. */
    fun remove(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    /** Creates the folder if needed and returns its path. */
    private fun made(path: String): String {
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        return path
    }
}

/** No photos: a Drive backup carries none (the photos go to `Photos/` on their own, docs/15 §11). */
private object NoPhotos : CopyPhotos {
    override fun original(photoId: String): ByteArray? = null

    override fun dataUri(photoId: String): String? = null
}

/**
 * The *Full backup* ZIP of the phone's data, **without photos**, for the Drive backup (docs/15 §5.1; Android's
 * `AndroidDriveBackupSource`): the same rows and the same writer as *Save a copy* (`CopyWriter`, `ExportFormat.BACKUP`), into
 * a private file (0600) in [folder], read back by the encryption through a [ByteSource]; [BackupPayload.close] removes it.
 * A failure removes the half-written file. Leftovers of an earlier process are swept when the source is made ([swept]).
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalForeignApi::class)
class IosDriveBackupSource(
    private val repository: Repository,
    private val language: () -> String,
    private val folder: String = IosDriveFolders.backup,
    private val now: () -> Long = IsoTime::nowMillis,
    private val appVersion: () -> String = { NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "" },
) : BackupSource {

    /**
     * Builds the backup ZIP of the current rows without photos into a private file and returns it as a
     * [BackupPayload]; closing the payload removes the file, and so does a failure.
     */
    override suspend fun open(): BackupPayload = withContext(Dispatchers.Default) {
        val options = ExportOptions(photos = PhotoScope.NONE, language = language(), utcOffsetMinutes = 0, exportedAtMillis = now())
        val bundle = repository.localRows().toBundle(options)
        val format = BackupData.of(bundle).format
        val path = "$folder/backup-${Uuid.random()}.zip"
        try {
            PosixFileSink(path).use { sink -> CopyWriter.write(bundle, ExportFormat.BACKUP, sink, NoPhotos, iosArchiveTools, appVersion()) }
            val input = PosixFileSource(path)
            var at = 0L
            val source = ByteSource { buffer, offset, length ->
                val n = input.readAt(at, buffer, offset, length)
                if (n <= 0) {
                    -1
                } else {
                    at += n
                    n
                }
            }
            BackupPayload(source, format, bundle.houses.size) {
                try {
                    input.close()
                } finally {
                    IosDriveFolders.remove(path)
                }
            }
        } catch (e: Throwable) {
            IosDriveFolders.remove(path)
            throw e
        }
    }

    companion object {
        /** The source for the Drive graph, with anything an earlier run left in [folder] removed first. */
        fun swept(repository: Repository, language: () -> String, folder: String = IosDriveFolders.backup): IosDriveBackupSource =
            IosDriveBackupSource(repository, language, folder).also { IosDriveFolders.sweep(folder) }
    }
}

/**
 * Where *Import a backup > From Google Drive* writes the decrypted ZIP (Android's `DriveImportFile`): a new private file
 * in [folder], made when the first bytes arrive; [finish] closes it for the Import screen, [discard] removes what was
 * written (every refusal does, so an unconfirmed file never reaches the import).
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalForeignApi::class)
class IosDriveImportFile(folder: String = IosDriveFolders.importing) : StagingSink {
    val path: String = "$folder/drive-${Uuid.random()}.zip"
    private var out: PosixFileSink? = null

    /** Appends to the staged file, creating it on the first bytes. */
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val sink = out ?: PosixFileSink(path).also { out = it }
        sink.write(buffer, offset, length)
    }

    /** Closes the file so the import can read it all; false when the disk refused the last bytes. */
    fun finish(): Boolean = try {
        out?.close()
        true
    } catch (_: PosixFileException) {
        false
    } finally {
        out = null
    }

    /** Closes and removes the staged file. */
    override fun discard() {
        try {
            out?.close()
        } catch (_: PosixFileException) {
            // Removed next.
        }
        out = null
        IosDriveFolders.remove(path)
    }
}
