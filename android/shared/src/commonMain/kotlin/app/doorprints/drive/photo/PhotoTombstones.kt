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

import app.doorprints.drive.connect.SyncRig

/*
 * Where the 30-day clean-up (docs/15 §5.1, S4b-BL-293) gets its photo tombstones, and the seam that lets the backup service
 * ask for them before a folder session exists. Web twin: `DriveSyncAdapter.photoTombstones()` and the memoized factory in
 * `backup-factory.ts`.
 */

/** The deleted photos of this device's own sync file (its photos and the tombstones it took from the others); none without an engine. */
suspend fun SyncRig.ownPhotoTombstones(): List<GonePhoto> = engine?.ownPhotoRows()?.mapNotNull(PhotoRestoreRules::goneOfRow).orEmpty()

/**
 * The tombstone source the backup service holds. The service is built before the controller (which owns the folder session),
 * so it gets this seam instead of a closure over a variable: [bind] is called once, with the controller's read, and until then
 * (or with no folder open) the source is empty, so the clean-up sweeps nothing. A second [bind] is refused.
 */
class PhotoTombstoneSource {
    private var read: (suspend () -> List<GonePhoto>)? = null

    fun bind(read: suspend () -> List<GonePhoto>) {
        check(this.read == null) { "the photo tombstone source is bound once" }
        this.read = read
    }

    suspend fun read(): List<GonePhoto> = read?.invoke().orEmpty()
}
