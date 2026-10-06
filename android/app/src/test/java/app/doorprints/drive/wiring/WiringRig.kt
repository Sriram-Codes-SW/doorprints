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

import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.crypto.P256PrivateKey
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.InMemoryFakeDrive
import app.doorprints.drive.backup.DeviceIdentity
import app.doorprints.drive.backup.DriveBackupService
import app.doorprints.drive.backup.DriveConnection
import app.doorprints.drive.store.DriveFileStores
import java.io.File

/** A device of one person on a shared fake Drive, over the app's own file stores in [dir] (not in-memory fakes of them). */
class WiringRig(val server: FakeDriveServer, dir: File, name: String = "Pixel 8", val p: CryptoProvider = JvmCryptoProvider) {
    val drive = InMemoryFakeDrive(server)
    val stores = DriveFileStores(dir)
    val identity = object : DeviceIdentity {
        override val key: P256PrivateKey = p.p256Generate()
        override val name = name
        override val platform = DevicePlatform.ANDROID
    }
    val service = DriveBackupService(drive, p, identity, stores.driveState, stores.trust, { server.clock.now() }, { 330 })
    val enrolment = BackupDeviceEnrolment(service)

    suspend fun created(): DriveConnection.Ready = service.createFolder(withRecoveryKey = true).connection as DriveConnection.Ready
}
