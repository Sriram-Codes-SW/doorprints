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

package app.doorprints.export

import app.doorprints.shared.export.ArchiveOpen
import app.doorprints.shared.export.BackupArchive
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.BackupManifest
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.FileRandomSource
import app.doorprints.shared.export.jvmArchiveTools
import java.io.Closeable
import java.io.File

/** Either an open, validated backup or the reason it was refused. */
sealed interface BackupOpen {
    data class Ok(val reader: BackupReader) : BackupOpen
    data class Failed(val problem: BackupProblem) : BackupOpen
}

/**
 * Reads a staged backup (S4-04): the ZIP the apps write, or a bare `data.json`, what the server's `GET /api/export`
 * downloads (docs/schemas/README.md section 2). Since S4b-BL-76 this is the common [BackupArchive] the iPhone reads with,
 * over the staged file, with the JVM's SHA-256 and `Inflater` ([jvmArchiveTools]): the same checks before a single row is
 * written (format, entry count, zip slip, sizes counted as they are inflated, the shapes, the SHA-256 of `data.json`) and
 * the same answers, which `BackupReaderParityTest` pinned against the `java.util.zip` reader this replaced on every case
 * of `docs/schemas/import-vectors.json` before the switch. A photo's hash is checked when its bytes are read.
 */
class BackupReader private constructor(
    private val source: FileRandomSource,
    /** The common reader, for `ArchiveImports`. */
    val archive: BackupArchive,
) : Closeable {
    val manifest: BackupManifest? get() = archive.manifest
    val data: BackupData get() = archive.data

    /** Names of the `photos/…` entries actually present in the archive. */
    val photoEntries: Set<String> get() = archive.photoEntries

    /** A photo's verified bytes, or null (missing, too large, failing its SHA-256): skipped, never fatal. */
    @Synchronized
    fun photoBytes(entry: String): ByteArray? = archive.photoBytes(entry)

    override fun close() = source.close()

    companion object {
        fun open(file: File): BackupOpen {
            // A staged copy that is gone (a failed import deletes it) is a storage problem, not a bad file:
            // "not a Doorprints backup" would send the user looking for the wrong fix.
            if (!file.exists()) return BackupOpen.Failed(BackupProblem.READ_FAILED)
            val source = try {
                FileRandomSource(file)
            } catch (_: Exception) {
                return BackupOpen.Failed(BackupProblem.READ_FAILED)
            }
            return when (val opened = BackupArchive.open(source, jvmArchiveTools)) {
                is ArchiveOpen.Ok -> BackupOpen.Ok(BackupReader(source, opened.archive))
                is ArchiveOpen.Failed -> {
                    runCatching { source.close() }
                    BackupOpen.Failed(opened.problem)
                }
            }
        }
    }
}
