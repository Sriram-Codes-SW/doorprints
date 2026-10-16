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
import app.doorprints.crypto.kidOf
import app.doorprints.deviceauth.AuthPlatform
import app.doorprints.deviceauth.DriveGate
import app.doorprints.deviceauth.LockLostDetector
import app.doorprints.deviceauth.RunDecision
import app.doorprints.drive.DriveClient
import app.doorprints.drive.backup.BackupSource
import app.doorprints.drive.backup.DriveBackupService
import app.doorprints.drive.backup.MemoryScratchSpace
import app.doorprints.drive.backup.ScratchSpace
import app.doorprints.drive.connect.DefaultSyncRigFactory
import app.doorprints.drive.connect.DriveConnectController
import app.doorprints.drive.connect.DriveSignIn
import app.doorprints.drive.connect.OperationBoundAuth
import app.doorprints.drive.connect.PhoneDeletionAuthorizer
import app.doorprints.drive.delete.DriveDeletionService
import app.doorprints.drive.device.DeviceKeyBackend
import app.doorprints.drive.device.DeviceKeyCryptoProvider
import app.doorprints.drive.device.DeviceLockActions
import app.doorprints.drive.device.DeviceLockDetectors
import app.doorprints.drive.device.KeystoreDeviceIdentity
import app.doorprints.drive.photo.NetworkState
import app.doorprints.drive.store.DriveFileStores
import app.doorprints.drive.store.stateFileAt
import app.doorprints.drive.sync.DriveSyncBackend
import app.doorprints.drive.sync.LocalRows
import app.doorprints.drive.sync.SyncDeviceIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Everything the Drive object graph needs from the platform, as seams, so the JVM tests build the real graph over fakes. */
class DriveDeps(
    /**
     * The folder of the per-device files that the platform's own backup must never copy to another phone: Android
     * `Context.noBackupFilesDir/drive`; the iPhone a `drive` folder of the app's data, flagged `NSURLIsExcludedFromBackupKey`.
     */
    val dir: String,
    /** The platform's software provider ([app.doorprints.crypto.platformCryptoProvider]); wrapped here, never used bare. */
    val crypto: CryptoProvider,
    val keyBackend: DeviceKeyBackend,
    val deviceName: String,
    /** What the device list shows this phone as (it is written into `keys.json` for the other devices). */
    val platform: DevicePlatform = DevicePlatform.ANDROID,
    val drive: DriveClient,
    val signIn: DriveSignIn?,
    /** The device check (screen lock, fingerprint, face) and the keyguard test. */
    val deviceAuth: OperationBoundAuth,
    /**
     * The lock detector, built with the "is the device key still usable" probe the graph supplies and the function it calls
     * when that probe failed **with the screen lock still there** (a key store fault, which has its own words).
     */
    val lock: (keyUsable: () -> Boolean, onKeyFault: () -> Unit) -> LockLostDetector,
    val network: NetworkState,
    /** The phone's rows for Drive sync, given the id this device writes them under. */
    val localRows: (deviceId: () -> String) -> LocalRows,
    val backupSource: BackupSource?,
    /**
     * One Drive sync pass over [DriveSyncBackend] with `photosAllowed` (the controller's photo gate: Wi-Fi only by default):
     * the repository's loop (`CommonRepository.sync`), which applies the pulled rows; null for a plain commit that does not.
     */
    val syncPass: (suspend (backend: DriveSyncBackend, photosAllowed: Boolean) -> Unit)? = null,
    /**
     * Called when Drive stops being in use (Disconnect, or the folder gone), **before** the server takes over again: marks
     * every row for upload to the server and resets its pull cursors (`CommonRepository.resetForServer`), because the rows
     * that went only to Drive are marked clean and would never reach the server otherwise (review of PR 142, item 3).
     * Throwing keeps Drive "in use"; the next change of state tries again.
     */
    val handBack: suspend () -> Unit = {},
    /** False when this build cannot sign in to Google (no Play services): the card says Drive is not available. */
    val configured: Boolean,
    val clock: () -> Long,
    val utcOffsetMinutes: () -> Int,
    val scope: CoroutineScope,
    val scratch: ScratchSpace = MemoryScratchSpace,
)

/** The assembled graph. [crypto] is the one provider every Drive piece got (see [DriveAssembly]). */
class DriveGraph(
    val controller: DriveConnectController,
    /** The folder was deleted elsewhere and the person has not answered ([DriveConnectController.folderGone]); the card asks. */
    val folderGone: kotlinx.coroutines.flow.StateFlow<Boolean>,
    val crypto: CryptoProvider,
    val identity: KeystoreDeviceIdentity,
    val gate: DriveGate,
    val authorizer: PhoneDeletionAuthorizer,
    val backup: DriveBackupService,
    val deletion: DriveDeletionService,
    val prefs: FileDrivePrefs,
    val lockStore: FileDriveLockStore,
    val detector: LockLostDetector,
)

/**
 * The one place where the Drive pieces are put together (S4b-BL-117/-118/-127), so the order and the sharing are written
 * once and tested. What it fixes:
 *
 * - **One crypto provider, the device-key one.** `DeviceKeyCryptoProvider(crypto)` goes to the backup service, the
 *   controller and the sync rigs: the plain provider refuses an ECDH with a Keystore key (A2 notes, decision 1).
 * - **The device key knows about the pin.** `folderPinned` comes from the stored pin of the folder in use
 *   ([FolderPinProbe]), so a lost key is "connect again", never a new key under an existing folder.
 * - **One gate, one authorizer.** The deletion service and the controller share one [PhoneDeletionAuthorizer] over one
 *   [DriveGate]: a grant made for the controller is the one the service redeems (one use, 60 s, bound to the operation).
 * - **A removed lock drops local keys only** ([DeviceLockActions] holds no Drive client) and pauses sync before each pass.
 * - **Sync pauses** while a deletion is half done, or while the lock check says so (docs/15 §3.3, §10.3).
 * - **Drive in use** is remembered across restarts ([DriveEngagement]); only the person's *Disconnect* ends it, never a folder found gone, automatic backup starts on at the first connect,
 *   and the pause notice is cleared when the folder is open again with a lock.
 */
object DriveAssembly {
    /** Builds the whole Drive graph over [d] and starts watching the controller to keep the "Drive in use" flag. */
    fun assemble(d: DriveDeps): DriveGraph {
        val p = DeviceKeyCryptoProvider(d.crypto)
        val stores = DriveFileStores(d.dir)
        val pins = FolderPinProbe({ stores.driveState.loadNow().rootId }, "${d.dir}/$TRUST_DIR")
        val identity = KeystoreDeviceIdentity(d.keyBackend, d.deviceName, d.platform, folderPinned = pins::isPinned)
        val lockStore = FileDriveLockStore(stateFileAt("${d.dir}/$LOCK_FILE"))
        val detector = d.lock(DeviceLockDetectors.keyUsable(identity)) { lockStore.keyStoreFault = true }
        val gate = DriveGate(
            AuthPlatform.PHONE, d.deviceAuth, detector,
            DeviceLockActions(discardKey = identity::discard, store = lockStore), d.clock,
        )
        val authorizer = PhoneDeletionAuthorizer(gate, d.deviceAuth, d.clock)
        val backup = DriveBackupService(d.drive, p, identity, stores.driveState, stores.trust, d.clock, d.utcOffsetMinutes, d.scratch)
        val deletion = DriveDeletionService(d.drive, authorizer, stores.deletion, { d.network.current().online }, d.clock)
        val prefs = FileDrivePrefs(stateFileAt("${d.dir}/$PREFS_FILE"))
        val deviceId = { SyncDeviceIds.of(kidOf(p, identity.key.publicKey)) }
        val rigs = DefaultSyncRigFactory(
            d.drive, p, stores.sync, stores.photos, d.localRows(deviceId), d.clock,
            paused = { stores.deletion.pending() != null || gate.beforeRun() != RunDecision.Run },
        )
        lateinit var controller: DriveConnectController
        controller = DriveConnectController(
            backup, d.drive, p, identity, stores.trust, BackupDeviceEnrolment(backup), deletion, stores.deletion, authorizer,
            rigs, d.network, prefs, d.clock, d.configured, d.backupSource, d.signIn,
            syncDriver = d.syncPass?.let { pass -> { backend -> pass(backend, controller.photosAllowed()) } },
        )
        watchEngagement(controller, prefs, lockStore, gate, d.scope, d.handBack)
        return DriveGraph(controller, controller.folderGone, p, identity, gate, authorizer, backup, deletion, prefs, lockStore, detector)
    }

    /** Remembers that Drive is in use ([DriveEngagement]) and forgets the lock pause once the folder is open again with a lock. */
    private fun watchEngagement(controller: DriveConnectController, prefs: FileDrivePrefs, lockStore: FileDriveLockStore, gate: DriveGate, scope: CoroutineScope, handBack: suspend () -> Unit) {
        scope.launch {
            var memory = Engagement(engaged = prefs.engaged)
            // Both flows: a *Disconnect* pressed while the folder is gone leaves the state at DISCONNECTED and only clears the flag.
            combine(controller.state, controller.folderGone, ::Pair).collect { (state, folderGone) ->
                var next = DriveEngagement.next(memory, state, folderGone)
                // Drive stops being the sync target (the person's Disconnect; a folder gone elsewhere keeps it in use): the rows
                // that only went to Drive are sent to the server again first. A failure leaves Drive in use for the next try.
                if (memory.engaged && !next.engaged) {
                    try {
                        handBack()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        next = memory
                    }
                }
                // docs/15 §1.3 "Defaults": automatic backup and sync is on once the person connects Drive (one switch),
                // unless they have already chosen. The controller's own default is off.
                if (next.engaged && !memory.engaged && prefs.get(DriveConnectController.KEY_AUTO_BACKUP) == null) controller.setAutoBackup(true)
                if (next.engaged != memory.engaged) prefs.engaged = next.engaged
                memory = next
                if (state == app.doorprints.drive.connect.ConnectState.READY && gate.canConnect() == app.doorprints.deviceauth.ConnectDecision.Allowed) lockStore.clear()
            }
        }
    }

    const val TRUST_DIR = "trust"
    const val LOCK_FILE = "lock.json"
    const val PREFS_FILE = "prefs.json"
}
