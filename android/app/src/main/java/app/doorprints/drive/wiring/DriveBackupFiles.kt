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

package app.doorprints.drive.wiring

import android.content.Context
import app.doorprints.crypto.ByteSource
import app.doorprints.data.Repository
import app.doorprints.drive.backup.BackupPayload
import app.doorprints.drive.backup.BackupSource
import app.doorprints.drive.backup.StagingSink
import app.doorprints.export.ExportBuilder
import app.doorprints.export.Exporters
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.ExportFormat
import app.doorprints.shared.export.PhotoScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * The *Full backup* ZIP of *Save a copy* as the Drive backup's payload (docs/15 §5.2): built by the same writer, **without
 * photos** (they travel on their own as `photo/1` files, so the backup stays small, §1.3), into a temporary file in the
 * cache that [BackupPayload.close] deletes. [dir] is that folder.
 */
class AndroidDriveBackupSource(
    private val context: Context,
    private val repository: Repository,
    private val photoFile: (String) -> File,
    private val dir: File = File(context.cacheDir, "drive-backup"),
) : BackupSource {
    override suspend fun open(): BackupPayload = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val options = ExportBuilder.defaults(context).copy(photos = PhotoScope.NONE)
        val bundle = ExportBuilder.bundle(repository, options)
        val format = BackupData.of(bundle).format
        val file = File(dir, "backup-${UUID.randomUUID()}.zip")
        try {
            BufferedOutputStream(FileOutputStream(file)).use { out ->
                Exporters.write(bundle, ExportFormat.BACKUP, out, photoFile, ExportBuilder.appVersion(context))
            }
            val input = FileInputStream(file)
            BackupPayload(ByteSource { buffer, offset, length -> input.read(buffer, offset, length) }, format, bundle.houses.size) {
                try {
                    input.close()
                } finally {
                    file.delete()
                }
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    /** Anything an earlier run left behind (a crash between the write and the close). */
    fun sweep() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /**
         * The source for the Drive graph, **swept once as it is made**: the graph is built once per process before any backup
         * opens, so every file in the folder is a leftover of an earlier process (a plain ZIP of the person's houses).
         */
        fun swept(context: Context, repository: Repository, photoFile: (String) -> File, dir: File = File(context.cacheDir, "drive-backup")): AndroidDriveBackupSource =
            AndroidDriveBackupSource(context, repository, photoFile, dir).also { it.sweep() }
    }
}

/**
 * Where a backup opened from Drive is written before the existing import preview takes it: a file in `cache/imports/`,
 * the folder `Imports.cleanOldStaging` already sweeps after six hours. It holds the decrypted ZIP, so [discard] (called
 * by the service on any refusal) deletes it, and so does the caller once the Import screen has copied it.
 */
class DriveImportFile(dir: File) : StagingSink {
    val file: File = File(dir.apply { mkdirs() }, "drive-${UUID.randomUUID()}.zip")
    private var out: FileOutputStream? = null

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val stream = out ?: FileOutputStream(file).also { out = it }
        stream.write(buffer, offset, length)
    }

    /** Closes the file so the import can read it all. */
    fun finish() {
        out?.close()
        out = null
    }

    override fun discard() {
        try {
            out?.close()
        } catch (_: java.io.IOException) {
            // Deleted next.
        }
        out = null
        file.delete()
    }
}

/** Where the Drive import staging files live and which `file:` addresses are theirs. */
object DriveImportStaging {
    private const val PREFIX = "drive-"
    private const val SUFFIX = ".zip"

    /**
     * Deletes [source] when it is a [DriveImportFile] in [dir] (a `file:` address of `drive-<id>.zip` directly in that folder)
     * and returns true; any other address (a picked document, another app's file, another folder) is left alone. Called once
     * the Import screen has **copied** the file: it holds the decrypted backup, so it must not wait for the six-hour sweep.
     */
    fun discardIfStaged(dir: File, source: android.net.Uri): Boolean {
        if (source.scheme != "file") return false
        val file = File(source.path ?: return false)
        val name = file.name
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return false
        if (file.canonicalFile.parentFile != dir.canonicalFile) return false
        return file.delete()
    }
}
