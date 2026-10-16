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
package app.doorprints.ui

import app.doorprints.data.BinRestore
import app.doorprints.drive.photo.PhotoRestoreReason
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * The words after *Restore* in *Recently deleted* (S4b-BL-293), the twin of the website's `RecentlyDeleted.words`: plain
 * "Restored name" when no photo is in question or all the photos that were there came back without Google Drive; all of them back
 * from Google Drive; some back and why the others are not; none back and why. Each reason once, in the app language's own
 * list pattern. [name] is the candidate's name; no kind is named, so a school reads like a house.
 */
suspend fun binRestoreMessage(name: String, r: BinRestore): String {
    if (r.photosTotal == 0 || (r.reasons.isEmpty() && r.photosFromDrive == 0)) return formatPositional(getString(Res.string.delete_restored), name)
    if (r.reasons.isEmpty()) return formatPositional(getString(Res.string.delete_photos_all_drive), name, r.photosTotal.toString())
    val why = joinedListText(r.reasons.map { getString(whyOf(it)) })
    return if (r.photosBack == 0) {
        formatPositional(getString(Res.string.delete_photos_none), name, why)
    } else {
        formatPositional(getString(Res.string.delete_photos_some), name, r.photosBack.toString(), r.photosTotal.toString(), why)
    }
}

/** The words for each reason a photo did not come back. */
internal fun whyOf(reason: PhotoRestoreReason): StringResource = when (reason) {
    PhotoRestoreReason.NOT_CONNECTED -> Res.string.delete_why_not_connected
    PhotoRestoreReason.OFFLINE -> Res.string.delete_why_offline
    PhotoRestoreReason.PAST_WINDOW -> Res.string.delete_why_past_window
    PhotoRestoreReason.MISSING -> Res.string.delete_why_missing
    PhotoRestoreReason.UNREADABLE -> Res.string.delete_why_unreadable
}
