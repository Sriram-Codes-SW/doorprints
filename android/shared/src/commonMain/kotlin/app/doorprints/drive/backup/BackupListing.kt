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

import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.OpenedKeys
import app.doorprints.crypto.RevokedEpochRule
import app.doorprints.drive.DriveClient
import app.doorprints.drive.DriveException
import app.doorprints.drive.DriveFile
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.DriveQuery
import app.doorprints.drive.listAll

/** The file names of docs/15 §5.1 (local time; the names are for the person, the app goes by the metadata). */
object BackupNames {
    const val PARTIAL_PREFIX = "partial-"
    const val MIME = "application/octet-stream"

    /** `Doorprints-backup-2026-10-02-0930.dpx` for [createdAt] at [utcOffsetMinutes]. */
    fun of(createdAt: Long, utcOffsetMinutes: Int): String {
        val local = createdAt + utcOffsetMinutes * 60_000L
        val day = local.floorDiv(86_400_000L)
        val (y, m, d) = BackupRetention.civil(day)
        val minutes = (local - day * 86_400_000L) / 60_000L
        fun two(v: Long) = v.toString().padStart(2, '0')
        return "Doorprints-backup-$y-${two(m)}-${two(d)}-${two(minutes / 60)}${two(minutes % 60)}.dpx"
    }
}

/**
 * Lists `Backups/` and keeps only what is a backup (S4b-BL-116; notes §0): `state=complete`, the metadata canonical and
 * its MAC verified over Drive's own checksum under a folder key of the opened list, the writer accepted by
 * [RevokedEpochRule] (with the authenticated `createdAt`), made after `backupsDeletedAt`, and one per content.
 */
internal class BackupLister(private val drive: DriveClient, private val p: CryptoProvider) {

    suspend fun list(folder: ReadyFolder, backupsId: String?, knownIds: List<String>, newestSeenAt: Long?): BackupListing {
        if (backupsId == null) return BackupListing(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false)
        val files = drive.listAll(DriveQuery(parentId = backupsId, appProperties = mapOf(DriveLayout.KIND to BackupMeta.KIND_BACKUP))).toMutableList()
        // A listing may lag behind this device's own write (docs/15 §7.1): ask for those by id.
        for (id in knownIds) {
            if (files.any { it.id == id }) continue
            val f = try {
                drive.getFile(id)
            } catch (e: DriveException) {
                if (e.kind == DriveException.Kind.NOT_FOUND) null else throw e
            }
            if (f != null && !f.trashed && backupsId in f.parents) files += f
        }
        return classify(files, folder.keys, folder.control.backupsDeletedAt, newestSeenAt)
    }

    fun classify(files: List<DriveFile>, keys: OpenedKeys, deletedAt: Long?, newestSeenAt: Long?): BackupListing {
        val complete = mutableListOf<DriveBackup>()
        val partialValid = mutableListOf<DriveBackup>()
        val junk = mutableListOf<DriveFile>()
        val ignored = mutableListOf<Pair<String, IgnoredReason>>()
        for (f in files.sortedWith(compareBy<DriveFile> { it.createdTime }.thenBy { it.id })) {
            if (f.isFolder || f.trashed) continue
            val partial = f.appProperties[DriveLayout.STATE] != DriveLayout.STATE_COMPLETE
            if (f.sha256Checksum == null) {
                ignored += f.id to IgnoredReason.NO_CHECKSUM
                continue
            }
            val read = BackupMeta.read(f)
            if (read == null) {
                if (partial) junk += f else ignored += f.id to IgnoredReason.NOT_A_BACKUP
                continue
            }
            val (meta, mac) = read
            if (meta.epoch > keys.epoch) {
                ignored += f.id to IgnoredReason.NEWER_EPOCH
                continue
            }
            if (!BackupMeta.verify(p, keys, meta, mac)) {
                if (partial) junk += f else ignored += f.id to IgnoredReason.MAC_INVALID
                continue
            }
            if (RevokedEpochRule.check(keys.body, meta.epoch, meta.writerKid, meta.createdAt) != RevokedEpochRule.Verdict.ACCEPT) {
                ignored += f.id to IgnoredReason.WRITER_REFUSED
                continue
            }
            if (deletedAt != null && meta.createdAt <= deletedAt) {
                ignored += f.id to IgnoredReason.DELETED_BEFORE
                continue
            }
            val b = DriveBackup(f.id, f.name, meta.createdAt, meta.houses, meta.epoch, meta.writerKid, f.sha256Checksum.lowercase(), f.size)
            if (partial) partialValid += b else complete += b
        }
        val seen = HashSet<String>()
        val backups = mutableListOf<DriveBackup>()
        val duplicates = mutableListOf<String>()
        for (b in complete) if (seen.add(b.sha256)) backups += b else duplicates += b.fileId
        val unfinished = mutableListOf<DriveBackup>()
        for (b in partialValid) if (seen.add(b.sha256)) unfinished += b else duplicates += b.fileId
        ignored += duplicates.map { it to IgnoredReason.DUPLICATE }
        backups.sortWith(newestFirst)
        val newest = backups.firstOrNull()?.createdAt
        val missing = newestSeenAt != null && (newest == null || newest < newestSeenAt)
        return BackupListing(backups, unfinished, duplicates, junk, ignored, missing)
    }

    companion object {
        val newestFirst: Comparator<DriveBackup> = compareByDescending<DriveBackup> { it.createdAt }.thenBy { it.fileId }
    }
}
