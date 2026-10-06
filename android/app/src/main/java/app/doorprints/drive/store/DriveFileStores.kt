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

package app.doorprints.drive.store

import app.doorprints.drive.backup.DriveStateStore
import app.doorprints.drive.backup.FolderTrustStores
import app.doorprints.drive.delete.DeletionStore
import app.doorprints.drive.photo.PhotoStateStore
import app.doorprints.drive.sync.SyncStateStore
import java.io.File

/**
 * Every persisted seam of the Drive backup, sync and deletion on Android, over files in one folder [dir]. Pass
 * `Context.noBackupFilesDir/drive`: the files are per device (a device id, a pin) and must not be restored onto another
 * phone by Android's own backup. No secret is kept here: the device's private key is `DeviceIdentity`'s (Keystore).
 */
class DriveFileStores(private val dir: File) {
    val driveState: DriveStateStore = FileDriveStateStore(File(dir, "drive-state.json"))
    val sync: SyncStateStore = FileSyncStateStore(File(dir, "sync-state.json"))
    val photos: PhotoStateStore = FilePhotoStateStore(File(dir, "photo-state.json"))
    val trust: FolderTrustStores = FileFolderTrustStores(File(dir, "trust"))
    val deletion: DeletionStore = FileDeletionStore(File(dir, "deletion"), FileFolderForgetter(driveState, sync, photos))
}
