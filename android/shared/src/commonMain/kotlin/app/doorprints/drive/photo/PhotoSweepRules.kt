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
package app.doorprints.drive.photo

/*
 * The 30-day clean-up (docs/15 §5.1): which deleted photos' encrypted files leave the person's own Google Drive. The
 * rules only, pure. A deleted photo's file is kept so *Recently deleted* > *Restore* can bring the photo back
 * (S4b-BL-293), and it is taken away only once it can no longer be wanted. Web twin: `photo-sweep-rules.ts`.
 */

object PhotoSweepRules {
    /**
     * How long a deleted photo's file is kept before the clean-up may take it, in days (docs/15 §1.4, §5.1). It is the
     * restore window itself, not a second 30: the clean-up must never take a file before *Recently deleted* > *Restore*
     * stops promising it, so the two are one number by construction rather than by agreement.
     */
    const val SWEEP_WINDOW_DAYS = PhotoRestoreRules.RESTORE_WINDOW_DAYS
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * The Drive file ids the clean-up may move to Drive's bin: a deleted photo's file leaves Drive only when its tombstone
     * is older than [SWEEP_WINDOW_DAYS] **and** no kept backup refers to it.
     *
     * The second half needs no download. A backup never exports tombstones (`backup-export.ts`: "tombstones are never
     * exported"), so a backup holds only the photos live when it was made: a backup made **after** a photo's deletion
     * cannot hold that photo's file. Every backup that stays was made after the deletion exactly when the tombstone is
     * older than the **oldest** staying backup's authenticated `createdAt` — so [oldestKeptBackupAt] (null when nothing
     * stays, when nothing can refer) is all this needs, and the backups themselves are never read.
     *
     * A photo with no Drive file named is skipped: there is nothing to take. Each file id is returned once.
     */
    fun photoFilesToSweep(photos: List<GonePhoto>, oldestKeptBackupAt: Long?, now: Long): List<String> {
        val older = now - SWEEP_WINDOW_DAYS * DAY_MS
        val out = LinkedHashSet<String>()
        for (photo in photos) {
            val file = photo.ref?.driveFileId ?: continue
            if (photo.deletedAt >= older) continue
            if (oldestKeptBackupAt != null && photo.deletedAt >= oldestKeptBackupAt) continue
            out += file
        }
        return out.toList()
    }
}
