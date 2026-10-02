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

package app.doorprints.drive

import android.content.Context
import app.doorprints.crypto.ByteSource
import app.doorprints.data.AndroidRepository
import app.doorprints.drive.backup.BackupPayload
import app.doorprints.drive.backup.BackupSource
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
import java.util.UUID

/**
 * The Full backup ZIP for Google Drive (docs/15 §1.4): the same `doorprints-backup` file *Save a copy* writes, without
 * photo bytes (photos follow in S4b-BL-128), written to a private cache file first because the encryption reads it as
 * a stream. The file is deleted when the service closes the payload.
 */
class AndroidDriveBackupSource(private val context: Context, private val repository: AndroidRepository) : BackupSource {
    override suspend fun open(): BackupPayload = withContext(Dispatchers.IO) {
        val bundle = ExportBuilder.bundle(repository, ExportBuilder.defaults(context).copy(photos = PhotoScope.NONE))
        val dir = File(context.cacheDir, "drive-backup").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "${UUID.randomUUID()}.zip")
        try {
            BufferedOutputStream(file.outputStream()).use { out ->
                Exporters.write(
                    bundle = bundle, format = ExportFormat.BACKUP, out = out,
                    photoFile = { id -> repository.photoFile(id) }, appVersion = ExportBuilder.appVersion(context),
                )
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        val input = FileInputStream(file)
        BackupPayload(
            source = ByteSource { buffer, offset, length -> input.read(buffer, offset, length) },
            format = BackupData.of(bundle).format,
            houses = bundle.houses.size,
            close = {
                runCatching { input.close() }
                file.delete()
            },
        )
    }
}
