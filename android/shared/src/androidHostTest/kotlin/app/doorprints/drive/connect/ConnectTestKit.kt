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

import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.RecoveryKey
import app.doorprints.deviceauth.AuthPlatform
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DriveGate
import app.doorprints.deviceauth.FakeDeviceAuth
import app.doorprints.deviceauth.FakeLock
import app.doorprints.deviceauth.RecordingActions
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.backup.DeviceIdentity
import app.doorprints.drive.backup.DriveConnection
import app.doorprints.drive.backup.DriveProblem
import app.doorprints.drive.backup.Payload
import app.doorprints.drive.backup.Rig
import app.doorprints.drive.delete.DeletedMarker
import app.doorprints.drive.delete.DeletionStore
import app.doorprints.drive.delete.DriveDeletionService
import app.doorprints.drive.delete.PendingDeletion
import app.doorprints.drive.photo.Metering
import app.doorprints.drive.photo.NetworkConditions
import app.doorprints.drive.photo.NetworkState
import app.doorprints.drive.photo.PhotoState
import app.doorprints.drive.photo.PhotoStateStore
import app.doorprints.drive.sync.DriveSyncState
import app.doorprints.drive.sync.MemLocal
import app.doorprints.drive.sync.SyncStateStore
import app.doorprints.drive.backup.StagingSink
import app.doorprints.shared.sync.DriveMerge

/** An in-memory [DeletionStore] (the device's list of what is left to delete). */
class MemDeletionStore : DeletionStore {
    var current: PendingDeletion? = null
    var marker: DeletedMarker? = null
    var forgotten = false
    override suspend fun pending() = current
    override suspend fun savePending(pending: PendingDeletion) {
        current = pending
    }
    override suspend fun clearPending() {
        current = null
    }
    override suspend fun marker() = marker
    override suspend fun recordFinished(marker: DeletedMarker, forgetFolder: Boolean) {
        this.marker = marker
        if (forgetFolder) forgotten = true
    }
}

class FakeSignIn(var next: SignInResult = SignInResult.SIGNED_IN) : DriveSignIn {
    var signIns = 0
    var signOuts = 0
    var revokes = 0
    var revokeBoom = false
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override suspend fun signIn(): SignInResult {
        signIns++
        gate?.await()
        return next
    }
    override suspend fun signOut() {
        signOuts++
    }
    override suspend fun revokeAccess() {
        revokes++
        if (revokeBoom) error("revoke failed: secret-token")
    }
}

/** What the enrolment seam was asked, and what it answers (a test sets [approval], [join], [revoke]). */
class FakeEnrolment : DeviceEnrolment {
    var approval: EnrolmentApproval = EnrolmentApproval.Failed(DriveProblem(DriveProblem.Kind.DRIVE))
    var join: DriveConnection = DriveConnection.NoFolder
    var revoke: EnrolmentRevoke = EnrolmentRevoke(DriveConnection.NoFolder, null)
    val calls = mutableListOf<String>()
    var lastPsk: ByteArray? = null
    var lastKey: ByteArray? = null
    var lastName: String? = null
    var lastPlatform: DevicePlatform? = null
    var lastEpoch: Int? = null

    override suspend fun approveDevice(publicKey: ByteArray, name: String, platform: DevicePlatform): EnrolmentApproval {
        calls += "approve"; lastKey = publicKey; lastName = name; lastPlatform = platform
        return approval
    }
    override suspend fun approveDevicePsk(publicKey: ByteArray, name: String, platform: DevicePlatform, psk: ByteArray): EnrolmentApproval {
        calls += "approvePsk"; lastKey = publicKey; lastName = name; lastPlatform = platform; lastPsk = psk
        return approval
    }
    override suspend fun joinFromWrap(enc: ByteArray, ct: ByteArray, epoch: Int): DriveConnection {
        calls += "joinWrap"; lastEpoch = epoch
        return join
    }
    override suspend fun joinFromPsk(enc: ByteArray, ct: ByteArray, epoch: Int, psk: ByteArray): DriveConnection {
        calls += "joinPsk"; lastEpoch = epoch; lastPsk = psk
        return join
    }
    override suspend fun revokeDevice(kid: ByteArray): EnrolmentRevoke {
        calls += "revoke"; lastKey = kid
        return revoke
    }
}

/** One phone: a real backup service over the shared fake Drive, the real deletion service and phone gate, fakes at the edges. */
class Phone(
    val server: FakeDriveServer, name: String, configured: Boolean = true, withSource: Boolean = true,
    crypto: app.doorprints.crypto.CryptoProvider = app.doorprints.crypto.JvmCryptoProvider,
) {
    val rig = Rig(server, name, crypto)
    val p = rig.p
    val deviceAuth = ProofingAuth { server.clock.now() }
    val lock = FakeLock()
    val gate = DriveGate(AuthPlatform.PHONE, deviceAuth, lock, RecordingActions()) { server.clock.now() }
    val authorizer = PhoneDeletionAuthorizer(gate, deviceAuth) { server.clock.now() }
    val store = MemDeletionStore()
    val signIn = FakeSignIn()
    val enrolment = FakeEnrolment()
    val prefs = MemoryDrivePrefs()
    val payload = Payload.of(70_000, 3)
    var online = true
    var metering = Metering.UNMETERED
    val network = NetworkState { NetworkConditions(online, metering) }
    val kidHex = app.doorprints.crypto.Bytes.hex(app.doorprints.crypto.kidOf(p, rig.identity.key.publicKey))
    val local = MemLocal(kidHex)
    var syncState = DriveSyncState()
    var photoState = PhotoState()
    val deletion = DriveDeletionService(rig.drive, authorizer, store, { online }, server.clock::now)
    var applyPulled = true
    val rigs = DefaultSyncRigFactory(
        rig.drive, p,
        object : SyncStateStore {
            override suspend fun load() = syncState
            override suspend fun save(state: DriveSyncState) { syncState = state }
        },
        object : PhotoStateStore {
            override suspend fun load() = photoState
            override suspend fun save(state: PhotoState) { photoState = state }
        },
        local, server.clock::now, paused = { store.current != null },
    )

    /** The app's sync loop in miniature: the Drive pass, then the rows it took applied by the Drive rule. */
    val driver: suspend (app.doorprints.drive.sync.DriveSyncBackend) -> Unit = { backend ->
        backend.commitPushes()
        if (applyPulled) backend.lastResult?.report?.take?.forEach { r ->
            if (DriveMerge.takesIncoming(local.rows[r.kind to r.key]?.stamp, r.stamp)) local.put(r, false)
        }
        local.dirty.clear()
    }

    fun controller(
        signedIn: DriveSignIn? = signIn, source: Boolean = true, configured: Boolean = true, driver: Boolean = true,
        customSource: app.doorprints.drive.backup.BackupSource? = null,
        customDriver: (suspend (app.doorprints.drive.sync.DriveSyncBackend) -> Unit)? = null,
    ) = DriveConnectController(
        rig.service, rig.drive, p, rig.identity as DeviceIdentity, rig.trust, enrolment, deletion, store, authorizer, rigs, network, prefs,
        server.clock::now, configured, customSource ?: if (source) payload.source() else null, signedIn,
        customDriver ?: if (driver) this.driver else null,
    )

    val c: DriveConnectController = controller()
}

class MemStaging : StagingSink {
    private val out = java.io.ByteArrayOutputStream()
    var discards = 0
    override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)
    override fun discard() { discards++; out.reset() }
    fun bytes(): ByteArray = out.toByteArray()
}

fun freshRecoveryKey(p: app.doorprints.crypto.CryptoProvider): String = RecoveryKey.generate(p).display

fun <T> Outcome<T>.ok(): T = (this as? Outcome.Ok)?.value ?: error("expected Ok but was $this")
fun Outcome<*>.reason(): DriveReason = (this as? Outcome.Failed)?.reason ?: error("expected Failed but was $this")

/**
 * A scripted device check that also signs what it was bound to, as `ProverDeviceAuth` does on the phone: a pass leaves
 * a distinct 64-hex proof (never the same twice) at the moment it passed; [signs] false is a check that passes without
 * signing anything, which the authorizer must not accept for L2/L3.
 */
class ProofingAuth(var signs: Boolean = true, private val clock: () -> Long) : FakeDeviceAuth(), OperationBoundAuth {
    var bound: String? = null
    val boundHistory = mutableListOf<String?>()
    var proofs = 0

    /** When the pass is stamped, if not at the moment of the ask (a prompt that took its time). */
    var passedAt: Long? = null
    private var proved: OperationProofValue? = null

    override fun bindNext(operationId: String?) {
        bound = operationId
        boundHistory += operationId
        proved = null
    }

    override fun takeProof(): OperationProofValue? = proved.also { proved = null }

    override suspend fun authenticate(reason: String, level: app.doorprints.deviceauth.DeleteLevel): AuthResult {
        val result = super.authenticate(reason, level)
        if (result == AuthResult.SUCCESS && signs) proved = OperationProofValue(passedAt ?: clock(), "%064x".format(++proofs))
        return result
    }
}
