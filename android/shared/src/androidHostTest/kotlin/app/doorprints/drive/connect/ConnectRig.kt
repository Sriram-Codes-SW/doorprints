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

package app.doorprints.drive.connect

import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.deviceauth.AuthPlatform
import app.doorprints.deviceauth.DriveGate
import app.doorprints.deviceauth.FakeDeviceAuth
import app.doorprints.deviceauth.FakeLock
import app.doorprints.deviceauth.RecordingActions
import app.doorprints.drive.DriveException
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.InMemoryFakeDrive
import app.doorprints.drive.backup.DriveBackupService
import app.doorprints.drive.backup.ImportDownload
import app.doorprints.drive.backup.Payload
import app.doorprints.drive.backup.RecordingStaging
import app.doorprints.drive.backup.StagingSink
import app.doorprints.drive.backup.TestIdentity
import app.doorprints.drive.delete.DriveDeletionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A scripted sign-in: what [connect] answers, and whether it is [configured]. */
class FakeSignIn(
    override var configured: Boolean = true,
    var result: SignInResult = SignInResult.Connected,
) : GoogleSignIn {
    var connected = false
    var connects = 0
    var disconnects = 0
    var forgotten = 0
    override fun isConnected() = connected
    override suspend fun connect(): SignInResult {
        connects++
        if (result == SignInResult.Connected) connected = true
        return result
    }

    override suspend fun disconnect() {
        disconnects++
        connected = false
    }

    override fun forgetLocally() {
        forgotten++
        connected = false
    }

    override suspend fun accessToken(): String = throw DriveException(DriveException.Kind.UNAUTHORIZED)
}

class FakeHandoff : DriveImportHandoff {
    val stagings = mutableListOf<RecordingStaging>()
    val opened = mutableListOf<ImportDownload.Verified>()
    override fun staging(): StagingSink = RecordingStaging().also { stagings += it }
    override suspend fun open(download: ImportDownload.Verified) {
        opened += download
    }
}

/** One device of one person, wired the way the apps wire it, on a shared fake [server]. Test code only. */
class ConnectRig(
    val server: FakeDriveServer = FakeDriveServer(),
    name: String = "Pixel 8",
    val platform: AuthPlatform = AuthPlatform.PHONE,
    val p: CryptoProvider = JvmCryptoProvider,
    val kv: KeyValueStore = MemoryKeyValueStore(),
) {
    val drive = InMemoryFakeDrive(server)
    var identity = TestIdentity(p, name)
    val stateStore = KvDriveStateStore(kv)
    val trust = KvFolderTrustStores(kv)
    val auth = FakeDeviceAuth()
    val lock = FakeLock()
    val actions = RecordingActions()
    val gate = DriveGate(platform, auth, lock, actions, server.clock::now)
    val authorizer = DriveGateAuthorizer(gate, p, server.clock::now)
    val deletionStore = KvDeletionStore(kv, stateStore)
    var online = true
    var webPrf = false
    val signIn = FakeSignIn()
    val handoff = FakeHandoff()
    val prefs = MemoryDriveConnectPrefs()
    var offset = 330
    val authReasons = mutableListOf<DriveDeleteChoice>()

    fun service() = DriveBackupService(drive, p, identity, stateStore, trust, server.clock::now, { offset })
    private val deletion = DriveDeletionService(drive, authorizer, deletionStore, { online }, server.clock::now, saveEvery = 3)
    val backupService = service()

    var houses = 5

    fun deps(signInOverride: GoogleSignIn = signIn, serviceOverride: DriveBackupService = backupService) = DriveDeps(
        signIn = signInOverride, gate = gate, authorizer = authorizer, drive = drive, service = serviceOverride, deletion = deletion,
        deletionStore = deletionStore, stateStore = stateStore,
        backupSource = Payload.of(4000, houses).source(), importHandoff = handoff, prefs = prefs, platform = platform,
        deviceLock = { auth.lockEnabled }, webPrf = { webPrf }, isOnline = { online }, clock = server.clock::now,
        authReason = { authReasons += it; "Confirm it's you to delete" }, randomBytes = { n -> p.randomBytes(n) },
    )

    fun controller(scope: CoroutineScope) = DriveSettingsController(deps(), scope)
}

internal fun <T> CoroutineScope.launchCollect(flow: kotlinx.coroutines.flow.Flow<T>, into: MutableList<T>) {
    launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { flow.collect { into += it } }
}
