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
package app.doorprints.data

import app.doorprints.drive.photo.PhotoRestoreReason

/**
 * What a *Restore* from *Recently deleted* did about the candidate's photos (S4b-BL-293), for the words after it: how many
 * photos it had ([photosTotal], 0 when none or when nobody recorded any), how many are back ([photosBack]), how many of those
 * came from the person's own Google Drive ([photosFromDrive]), and why the others did not ([reasons], each once). The
 * candidate itself is always restored when this is returned. Web twin: `Restored` in `delete-undo.service.ts`.
 */
data class BinRestore(
    val photosTotal: Int = 0,
    val photosBack: Int = 0,
    val photosFromDrive: Int = 0,
    val reasons: List<PhotoRestoreReason> = emptyList(),
) {
    companion object {
        /** A restore with nothing to say about photos. */
        val PLAIN = BinRestore()
    }
}
