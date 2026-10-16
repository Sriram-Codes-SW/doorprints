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

package app.doorprints.drive.backup

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.Dpx
import app.doorprints.crypto.DpxException
import app.doorprints.crypto.RevokedEpochRule
import app.doorprints.drive.DriveClient
import app.doorprints.drive.DriveException
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.downloadTo
import app.doorprints.drive.driveSha256
import app.doorprints.shared.export.BackupFormat
import app.doorprints.shared.export.hexOf
import kotlinx.coroutines.CancellationException

/**
 * *Import a backup* > *From Google Drive* (S4b-BL-116; docs/15 §1.1 G2, §1.4 item 8): the list comes from
 * [DriveBackupService.listBackups] (only checked backups, newest first by their authenticated time); [download] fetches
 * the chosen one, proves it, and writes the decrypted ZIP into the same staged file a picked file goes to. From there
 * the existing import runs unchanged (Android's `Imports.preview` and `ImportWorker`, `ArchiveImports` on the iPhone,
 * the website's `openBackup` and import page): its checks, preview (*Merge*, *Keep mine*, *Add everything as new
 * copies*) and undo. No import logic is repeated here. Web twin: `drive-import.service.ts`.
 *
 * **Fail closed**: the metadata is read and verified again (the file may have changed since the listing), the bytes
 * must have the checksum the metadata's MAC covers, the `dpx/1` header must name the metadata's epoch and writer, and
 * every chunk must authenticate. On any refusal the staging sink is discarded: nothing reaches the import.
 */
class DriveImportService(
    private val drive: DriveClient,
    private val p: CryptoProvider,
    private val scratch: ScratchSpace = MemoryScratchSpace,
) {
    /**
     * Fetches [backup], proves it (see the class notes) and streams the decrypted ZIP into [staging]. Returns
     * [ImportDownload.Verified] only when every check passed; otherwise [staging] is discarded and the result is
      * [ImportDownload.Refused]. [maxPlaintext] caps the decrypted size. The scratch copy of the ciphertext is always
      * deleted.
     */
    suspend fun download(
        folder: ReadyFolder,
        backup: DriveBackup,
        staging: StagingSink,
        maxPlaintext: Long = DriveBackupService.MAX_PLAINTEXT,
    ): ImportDownload {
        val file = scratch.create()
        try {
            val f = drive.getFile(backup.fileId)
            if (f.trashed || (folder.backupsId != null && folder.backupsId !in f.parents)) return refused(staging, DriveProblem.Kind.BACKUP_GONE)
            val (meta, mac) = BackupMeta.read(f) ?: return refused(staging, DriveProblem.Kind.BACKUP_REFUSED)
            val deletedAt = folder.control.backupsDeletedAt
            if (f.appProperties[DriveLayout.STATE] != DriveLayout.STATE_COMPLETE ||
                Bytes.hex(meta.ciphertextSha256) != backup.sha256 || meta.createdAt != backup.createdAt ||
                meta.epoch != backup.epoch || !meta.writerKid.contentEquals(backup.writerKid) ||
                (deletedAt != null && meta.createdAt <= deletedAt) ||
                meta.epoch > folder.keys.epoch || !BackupMeta.verify(p, folder.keys, meta, mac) ||
                RevokedEpochRule.check(folder.keys.body, meta.epoch, meta.writerKid, meta.createdAt) != RevokedEpochRule.Verdict.ACCEPT
            ) {
                return refused(staging, DriveProblem.Kind.BACKUP_REFUSED)
            }
            val size = f.size?.takeIf { it > 0 } ?: return refused(staging, DriveProblem.Kind.BACKUP_REFUSED)
            val hash = driveSha256()
            drive.downloadTo(f.id, size) { bytes ->
                hash.update(bytes)
                file.write(bytes, 0, bytes.size)
            }
            if (file.size != size || hexOf(hash.digest()) != backup.sha256) {
                throw DriveException(DriveException.Kind.CORRUPT, reason = "checksumMismatch")
            }
            val dpx = Dpx(p)
            val inner = dpx.readHeader(file.source()).inner
            if (!BackupFormat.accepts(inner)) throw DpxException(DpxException.Kind.INNER_MISMATCH, "not a backup")
            val read = dpx.decrypt(
                folder.keys, inner, file.source(), staging, maxPlaintext,
                expectedCiphertextSha256 = meta.ciphertextSha256,
            ) { h ->
                if (h.epoch != meta.epoch || !h.kid.contentEquals(meta.writerKid)) {
                    throw DpxException(DpxException.Kind.HEADER_INVALID, "header and metadata differ")
                }
            }
            return ImportDownload.Verified(backup, inner, read.plaintextSize)
        } catch (e: CancellationException) {
            staging.discard()
            throw e
        } catch (e: Exception) {
            staging.discard()
            return ImportDownload.Refused(DriveProblem.of(e))
        } finally {
            file.delete()
        }
    }

    private fun refused(staging: StagingSink, kind: DriveProblem.Kind): ImportDownload.Refused {
        staging.discard()
        return ImportDownload.Refused(DriveProblem(kind))
    }
}
