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
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.RecoveryKey
import app.doorprints.deviceauth.DeletionContext
import app.doorprints.drive.DriveClient
import app.doorprints.drive.backup.DriveConnection
import app.doorprints.drive.backup.DriveProblem
import app.doorprints.drive.delete.AuthorizationToken
import app.doorprints.drive.photo.DrivePhotos
import app.doorprints.drive.photo.PhotoConfig
import app.doorprints.drive.photo.PhotoStateStore
import app.doorprints.drive.sync.DriveSyncBackend
import app.doorprints.drive.sync.DriveSyncEngine
import app.doorprints.drive.sync.FolderSession
import app.doorprints.drive.sync.LocalRows
import app.doorprints.drive.sync.SyncStateStore
import app.doorprints.deviceauth.DeletionAction as PolicyAction

/** How the person signs in to Google on this platform (the phones' browser and PKCE flow); the token itself stays inside. */
interface DriveSignIn {
    /** Interactive sign-in when there is no grant yet; [SignInResult.SIGNED_IN] at once when there is one. */
    suspend fun signIn(): SignInResult

    /** Forgets this device's grant locally (*Disconnect this device*). Files in Drive stay. */
    suspend fun signOut()

    /** Asks Google to revoke the grant, then forgets it (*Disconnect on all devices*). */
    suspend fun revokeAccess()
}

/** How an interactive Google sign-in ended. */
enum class SignInResult { SIGNED_IN, CANCELLED, DENIED, UNAVAILABLE, OFFLINE, FAILED }

/** The few per-device preferences (plain on/off values, nothing secret). Implementations must not throw; the controller guards anyway. */
interface DrivePrefs {
    /** The stored value, or null. */
    fun get(key: String): String?
    /** Stores [value] under [key]. */
    fun put(key: String, value: String)
}

/** Preferences in memory only: the default of tests and of builds without storage. */
class MemoryDrivePrefs : DrivePrefs {
    private val values = HashMap<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) {
        values[key] = value
    }
}

/**
 * The device-key side of enrolment and revocation: the Kotlin twin of the web `DriveBackupAdapter`'s
 * `approveDevice`, `approveDevicePsk`, `joinFromWrap`, `joinFromPsk` and `revokeDevice`. The implementation is
 * `DriveBackupService` plus the HPKE PSK mode (the enrolment branch, `feat/android-drive-e-enrolment`); the controller
 * only routes to it and never opens a wrap or writes `keys.json` itself.
 */
interface DeviceEnrolment {
    /** List the new device (after the approver's device check) and wrap the folder key for exactly [publicKey]. */
    suspend fun approveDevice(publicKey: ByteArray, name: String, platform: DevicePlatform): EnrolmentApproval

    /** As [approveDevice], but the returned wrap is an HPKE PSK wrap under [psk] (the QR enrolment). */
    suspend fun approveDevicePsk(publicKey: ByteArray, name: String, platform: DevicePlatform, psk: ByteArray): EnrolmentApproval

    /** The newcomer's first pin from the wrap the approver handed over (base mode). */
    suspend fun joinFromWrap(enc: ByteArray, ct: ByteArray, epoch: Int): DriveConnection

    /** The newcomer's first pin from the PSK wrap. */
    suspend fun joinFromPsk(enc: ByteArray, ct: ByteArray, epoch: Int, psk: ByteArray): DriveConnection

    /** Revoke [kid]: a new epoch and a new recovery key. A rollback or fork refusal comes back as an error connection. */
    suspend fun revokeDevice(kid: ByteArray): EnrolmentRevoke
}

/** The approver side of enrolment: the wrap for the newcomer, or why none was made. */
sealed interface EnrolmentApproval {
    /** The newcomer is listed and the folder key is wrapped to its public key ([wrapEnc], [wrapCt]) under [epoch]. */
    class Approved(val connection: DriveConnection.Ready, val wrapEnc: ByteArray, val wrapCt: ByteArray, val epoch: Int) : EnrolmentApproval
    /** Nothing was listed or wrapped; [problem] says why. */
    data class Failed(val problem: DriveProblem) : EnrolmentApproval
}

/** A finished revoke: the folder as it is now, and the new recovery key (shown once by the caller, never stored). */
class EnrolmentRevoke(val connection: DriveConnection, val recoveryKey: RecoveryKey?)

/** The authorisation of a delete or of an L2 action (approve, revoke, disconnect on all devices): the phone's device check. */
interface DeleteAuthorizer {
    /** Whether the phone has a screen lock (without one the policy refuses every L2/L3 action). */
    fun isDeviceLockEnabled(): Boolean

    /**
     * Asks the person (screen lock, fingerprint or face) when [action] needs it. [operationId] is the plan's id for a
     * file deletion (the grant is bound to it); null for approve, revoke and disconnect on all devices.
     */
    suspend fun authorize(action: PolicyAction, ctx: DeletionContext, operationId: String?, promptReason: String): DeleteAuthorization

    /** Drops what was issued (after a run, or when the screen goes away). */
    fun forget()
}

/** The device check's answer to [DeleteAuthorizer.authorize]. */
sealed interface DeleteAuthorization {
    /** [token] is null when no file deletion is bound (an L2 action that is not a deletion). */
    class Granted(val token: AuthorizationToken?) : DeleteAuthorization
    /** The check was refused or failed; nothing is authorised. [reason] is the screen key. */
    data class Refused(val reason: DriveReason) : DeleteAuthorization
}

/** The sync stack built over one opened folder. */
class SyncRig(val backend: DriveSyncBackend, val photos: DrivePhotos, val local: LocalRows)

/** Builds the [SyncRig] for an opened folder session; a seam so the controller's tests can use fakes. */
fun interface SyncRigFactory {
    /** The sync stack for [session]. */
    fun create(session: FolderSession): SyncRig
}

/** The real stack: engine, photos and backend over the folder session (the web's `DriveSyncAdapter.ensureInitialized`). */
class DefaultSyncRigFactory(
    private val drive: DriveClient,
    private val p: CryptoProvider,
    private val syncState: SyncStateStore,
    private val photoState: PhotoStateStore,
    private val local: LocalRows,
    private val clock: () -> Long,
    private val photoConfig: PhotoConfig = PhotoConfig(),
    /** True while a deletion is pending or the folder was deleted: no sync pass then (docs/15 §3.3, §3.4). */
    private val paused: suspend () -> Boolean = { false },
) : SyncRigFactory {
    override fun create(session: FolderSession): SyncRig {
        val photos = DrivePhotos(drive, p, session, photoState, clock, photoConfig)
        val engine = DriveSyncEngine(drive, p, session, syncState, local, clock, paused, photos)
        return SyncRig(DriveSyncBackend(engine, local, clock, session.deviceId, photos), photos, local)
    }
}
