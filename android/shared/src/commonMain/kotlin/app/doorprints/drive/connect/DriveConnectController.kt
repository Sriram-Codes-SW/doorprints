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

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.KeysException
import app.doorprints.crypto.RecoveryKey
import app.doorprints.crypto.kidOf
import app.doorprints.deviceauth.AuthPlatform
import app.doorprints.deviceauth.DeletionContext
import app.doorprints.deviceauth.DeletionDecision
import app.doorprints.deviceauth.DeletionPolicy
import app.doorprints.deviceauth.Factor
import app.doorprints.deviceauth.RefusalReason
import app.doorprints.drive.DriveClient
import app.doorprints.drive.DriveException
import app.doorprints.drive.backup.BackupSchedule
import app.doorprints.drive.backup.BackupSource
import app.doorprints.drive.backup.DeviceIdentity
import app.doorprints.drive.backup.DriveBackup
import app.doorprints.drive.backup.DriveBackupService
import app.doorprints.drive.backup.DriveConnection
import app.doorprints.drive.backup.BackupOutcome
import app.doorprints.drive.backup.FolderTrustStores
import app.doorprints.drive.backup.ImportDownload
import app.doorprints.drive.backup.ReadyFolder
import app.doorprints.drive.backup.StagingSink
import app.doorprints.drive.delete.DeletedMarker
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.DeletionOutcome
import app.doorprints.drive.delete.DeletionPlan
import app.doorprints.drive.delete.DeletionStore
import app.doorprints.drive.delete.DriveDeletionService
import app.doorprints.drive.delete.PendingDeletion
import app.doorprints.drive.delete.PlanResult
import app.doorprints.drive.delete.Refusal
import app.doorprints.drive.photo.NetworkState
import app.doorprints.drive.photo.OneOffGrant
import app.doorprints.drive.photo.PhotoNetworkStatus
import app.doorprints.drive.photo.PhotoSettings
import app.doorprints.drive.photo.PhotoUploadGate
import app.doorprints.drive.sync.DriveSyncBackend
import app.doorprints.drive.sync.DriveSyncNotYet
import app.doorprints.drive.sync.FolderSession
import app.doorprints.drive.sync.SyncPassResult
import app.doorprints.shared.sync.SyncKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import app.doorprints.deviceauth.DeletionAction as PolicyAction

/**
 * The one controller both phones' Drive screens use (S4b-BL-117, with -116, -118 and -119 as the screens see them): the
 * Kotlin twin of the website's `DriveConnectService`, `DeleteFlow` and adapters. It only **routes** to the core
 * ([DriveBackupService], [DriveSyncBackend], [DriveDeletionService], [DeviceEnrolment]); every trust rule (the first
 * connect, "a folder is adopted only when pinned", the recovery key shown once, rollback refusal) is enforced there and
 * is neither repeated nor bypassed here.
 *
 * What differs from the website, on purpose: the factor of a level 2 or 3 action is the **device check**
 * ([DeleteAuthorizer], the phone's screen lock); there is no recovery-key factor and no passkey, so [deleteFactor] never
 * offers either and [authorizeDelete] has no key parameter.
 *
 * Every failure is a [DriveReason] (a dictionary key), never an exception message. The recovery key is returned once
 * and kept nowhere (not in [state], not in a field). Cancellation is never swallowed.
 */
class DriveConnectController(
    private val backup: DriveBackupService,
    private val drive: DriveClient,
    private val p: CryptoProvider,
    private val identity: DeviceIdentity,
    private val trust: FolderTrustStores,
    private val enrolment: DeviceEnrolment,
    private val deletion: DriveDeletionService,
    private val deletionStore: DeletionStore,
    private val authorizer: DeleteAuthorizer,
    private val syncRigs: SyncRigFactory,
    private val network: NetworkState,
    private val prefs: DrivePrefs,
    private val clock: () -> Long,
    /** False when this build has no Google client id: the state stays [ConnectState.UNAVAILABLE]. */
    private val configured: Boolean,
    /** The Full backup ZIP for one run; without it *Back up now* is refused ([DriveReason.NO_BACKUP_SOURCE]). */
    private val backupSource: BackupSource? = null,
    private val signIn: DriveSignIn? = null,
    /**
     * What one *Sync now* runs against the Drive backend. Null: the controller calls `commitPushes()` itself (a Drive
     * pass, as on the website). The app passes the repository's sync (which calls `commitPushes()` and applies the
     * pulled rows), so *Sync now* and the repository are one path.
     */
    private val syncDriver: (suspend (DriveSyncBackend) -> Unit)? = null,
) {
    private val _state = MutableStateFlow(if (configured) ConnectState.DISCONNECTED else ConnectState.UNAVAILABLE)

    /** Where the screen stands. Never carries the recovery key. */
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<DriveReason?>(null)

    /** [DriveReason.DEVICE_REVOKED] when this device was revoked (instead of the new-device sentence), else null. */
    val enrolmentNotice: StateFlow<DriveReason?> = _notice.asStateFlow()

    private val ops = Mutex()
    private var ready: ReadyFolder? = null
    private var session: FolderSession? = null
    private var rig: SyncRig? = null
    private var rigSession: FolderSession? = null
    private var recoveryKeyShown = false
    private var lastSync: SyncInfo? = null
    private var memoryAutoBackup = false
    private var photoSettings: PhotoSettings = PhotoSettings(uploadOnMobileData = recall(KEY_PHOTOS_MOBILE) == "1")
    private val photoGate = PhotoUploadGate(network, { photoSettings }, clock)

    /** The newest Drive connection's folder is open (also while the recovery key is on screen). */
    val isReady: Boolean get() = ready != null

    fun hasShownRecoveryKey(): Boolean = recoveryKeyShown

    // ==================== Connect ====================

    /** Signs in when needed, then reads where the folder stands. Writes nothing (a missing control file excepted). */
    suspend fun connect(): ConnectResult {
        if (!configured) return ConnectResult(ConnectState.UNAVAILABLE, error = DriveReason.NOT_CONFIGURED)
        if (_state.value == ConnectState.CONNECTING) return ConnectResult(ConnectState.CONNECTING, error = DriveReason.CONNECTION_IN_PROGRESS)
        val before = _state.value
        _state.value = ConnectState.CONNECTING
        try {
            signIn?.let { s ->
                val result = s.signIn()
                if (result != SignInResult.SIGNED_IN) {
                    // The person closing the browser is not an error: the card goes back to where it was.
                    val back = if (result == SignInResult.CANCELLED) before.takeIf { it != ConnectState.CONNECTING } ?: ConnectState.DISCONNECTED else ConnectState.ERROR
                    _state.value = back
                    return ConnectResult(back, error = signInReason(result))
                }
            }
            return ops.withLock { handle(backup.connect()) }
        } catch (e: CancellationException) {
            _state.value = before
            throw e
        } catch (e: Exception) {
            return fail(e)
        }
    }

    suspend fun refresh(): ConnectResult = connect()

    /**
     * First connect to a Drive with no Doorprints folder, or *Start again* after [DriveReason.FOLDER_GONE]. With
     * [withRecoveryKey] the key comes back in [ConnectResult.recoveryKey] to be shown once (state
     * [ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY]); false is the person's *Skip* after the warning.
     */
    suspend fun createFolder(withRecoveryKey: Boolean = true): ConnectResult = try {
        ops.withLock {
            val out = backup.createFolder(withRecoveryKey)
            recoveryKeyShown = false
            val key = out.recoveryKey
            if (key != null && out.connection is DriveConnection.Ready) {
                setReady(out.connection)
                // The heading is only true once the key is in this result.
                _state.value = ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY
                ConnectResult(ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY, recoveryKey = key.display)
            } else {
                handle(out.connection)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fail(e)
    }

    /** Join by the typed key. A key that does not read (typo, check character) is refused here, before Drive is asked. */
    suspend fun openWithRecoveryKey(text: String): ConnectResult {
        val key = try {
            RecoveryKey.parse(text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return ConnectResult(_state.value, error = DriveReason.JOIN_INVALID_FORMAT)
        }
        return try {
            ops.withLock { handle(backup.openWithRecoveryKey(key)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** The person says the recovery key is saved: on to [ConnectState.READY]. Only meaningful while the key is on screen. */
    fun confirmRecoveryKeySaved() = leaveRecoveryKeyScreen()

    /** The person skipped saving it after the warning. */
    fun skipRecoveryKeyWithWarning() = leaveRecoveryKeyScreen()

    private fun leaveRecoveryKeyScreen() {
        if (_state.value != ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY) return
        recoveryKeyShown = true
        _state.value = if (ready != null) ConnectState.READY else ConnectState.NEEDS_ENROLMENT
    }

    /** *Disconnect this device*: forgets the session and this device's Google grant. Every file in Drive stays. */
    suspend fun disconnect() {
        clearConnection()
        _state.value = ConnectState.DISCONNECTED
        try {
            signIn?.signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The local session has ended either way.
        }
    }

    /** *Disconnect on all devices* (L2, device check): revoke Google's grant, then disconnect here. Files in Drive stay. */
    suspend fun disconnectAll(promptReason: String): Outcome<Unit> {
        authorizePolicy(PolicyAction.DISCONNECT_ALL_DEVICES, promptReason)?.let { return Outcome.Failed(it) }
        try {
            signIn?.revokeAccess()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The local session still ends. The token is never logged.
        }
        disconnect()
        return Outcome.Ok(Unit)
    }

    private fun handle(connection: DriveConnection): ConnectResult {
        setReady(connection)
        return when (connection) {
            is DriveConnection.Ready -> {
                _notice.value = null
                _state.value = ConnectState.READY
                ConnectResult(ConnectState.READY)
            }
            is DriveConnection.NeedsRecoveryKey -> {
                _state.value = ConnectState.NEEDS_RECOVERY_KEY
                _notice.value = if (connection.reason == KeysException.Kind.REVOKED) DriveReason.DEVICE_REVOKED else null
                ConnectResult(ConnectState.NEEDS_RECOVERY_KEY)
            }
            is DriveConnection.NeedsEnrolment -> {
                _notice.value = null
                _state.value = ConnectState.NEEDS_ENROLMENT
                ConnectResult(ConnectState.NEEDS_ENROLMENT)
            }
            DriveConnection.FolderGone -> {
                _notice.value = null
                _state.value = ConnectState.DISCONNECTED
                ConnectResult(ConnectState.DISCONNECTED, error = DriveReason.FOLDER_GONE)
            }
            DriveConnection.NoFolder -> {
                _notice.value = null
                _state.value = ConnectState.DISCONNECTED
                ConnectResult(ConnectState.DISCONNECTED)
            }
            is DriveConnection.Error -> {
                val reason = DriveReason.of(connection.problem.kind)
                val onJoin = _state.value == ConnectState.NEEDS_ENROLMENT || _state.value == ConnectState.NEEDS_RECOVERY_KEY
                val kind = connection.problem.kind
                // A wrong key (or no key) is said on the join form: leaving it would destroy the form.
                if (onJoin && (kind == app.doorprints.drive.backup.DriveProblem.Kind.WRONG_RECOVERY_KEY ||
                        kind == app.doorprints.drive.backup.DriveProblem.Kind.NO_RECOVERY_KEY)
                ) {
                    ConnectResult(_state.value, error = reason)
                } else {
                    _notice.value = null
                    _state.value = ConnectState.ERROR
                    ConnectResult(ConnectState.ERROR, error = reason)
                }
            }
        }
    }

    private fun fail(e: Throwable): ConnectResult {
        _state.value = ConnectState.ERROR
        return ConnectResult(ConnectState.ERROR, error = DriveReason.of(e))
    }

    private fun setReady(connection: DriveConnection) {
        if (connection is DriveConnection.Ready) {
            val folder = connection.folder
            ready = folder
            session = FolderSession(
                folder.rootId, kidOf(p, identity.key.publicKey), folder.keys, app.doorprints.crypto.KeysGuard(p, trust.keys(folder.rootId)),
            ) {
                // Re-reads keys.json through this device's key and the pin (a device that joined, or a new epoch, since).
                when (val again = backup.connect()) {
                    is DriveConnection.Ready -> again.folder.keys
                    is DriveConnection.Error -> throw again.problem.driveKind?.let { DriveException(it) }
                        ?: KeysException(again.problem.keysKind ?: KeysException.Kind.NOT_ENROLLED, "keys.json no longer opens")
                    is DriveConnection.NeedsRecoveryKey -> throw KeysException(again.reason, "keys.json no longer opens")
                    else -> throw KeysException(KeysException.Kind.NOT_ENROLLED, "keys.json no longer opens")
                }
            }
        } else {
            clearConnection()
        }
    }

    private fun clearConnection() {
        ready = null
        session = null
        rig = null
        rigSession = null
        lastSync = null
    }

    private fun signInReason(r: SignInResult): DriveReason = when (r) {
        SignInResult.SIGNED_IN -> DriveReason.FAILED
        SignInResult.CANCELLED -> DriveReason.SIGNIN_CLOSED
        SignInResult.DENIED -> DriveReason.SIGNIN_DENIED
        SignInResult.UNAVAILABLE -> DriveReason.SIGNIN_UNAVAILABLE
        SignInResult.OFFLINE -> DriveReason.OFFLINE
        SignInResult.FAILED -> DriveReason.CONNECT_FAILED
    }

    // ==================== Backups ====================

    suspend fun listBackups(): Outcome<BackupList> {
        val folder = ready ?: return Outcome.Failed(DriveReason.NOT_CONNECTED)
        return try {
            val listing = backup.listBackups(folder)
            Outcome.Ok(BackupList(listing.backups.map(::summaryOf), listing.missingNewer))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(DriveReason.of(e))
        }
    }

    suspend fun lastBackup(): Outcome<BackupSummary> = when (val l = listBackups()) {
        is Outcome.Failed -> l
        is Outcome.Ok -> l.value.backups.firstOrNull()?.let { Outcome.Ok(it) } ?: Outcome.Failed(DriveReason.NO_BACKUPS)
    }

    suspend fun backUpNow(): Outcome<BackUpDone> = ops.withLock { backUpLocked() }

    private suspend fun backUpLocked(): Outcome<BackUpDone> {
        val folder = ready ?: return Outcome.Failed(DriveReason.NOT_CONNECTED)
        val source = backupSource ?: return Outcome.Failed(DriveReason.NO_BACKUP_SOURCE)
        return try {
            when (val out = backup.backUp(folder, source)) {
                is BackupOutcome.Failed -> Outcome.Failed(DriveReason.of(out.problem.kind))
                is BackupOutcome.Done -> Outcome.Ok(BackUpDone(summaryOf(out.backup), out.tidy.hold?.backupId, out.missingNewer))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(DriveReason.of(e))
        }
    }

    /** The person confirmed the drop the shrink guard held: pruning goes on at the next run. */
    suspend fun confirmShrink(backupId: String) = backup.confirmShrink(backupId)

    /**
     * Downloads, opens and proves [backupId] and writes the ZIP into [staging] (hand it to the existing import preview;
     * nothing is imported here). On any refusal the sink is discarded.
     */
    suspend fun importFromDrive(backupId: String, staging: StagingSink): Outcome<ImportedBackup> {
        val folder = ready ?: return Outcome.Failed(DriveReason.NOT_CONNECTED)
        return try {
            val item = backup.listBackups(folder).backups.firstOrNull { it.fileId == backupId }
                ?: return Outcome.Failed(DriveReason.BACKUP_NOT_FOUND)
            when (val r = backup.imports.download(folder, item, staging)) {
                is ImportDownload.Refused -> {
                    staging.discard()
                    Outcome.Failed(DriveReason.of(r.problem.kind))
                }
                is ImportDownload.Verified -> Outcome.Ok(ImportedBackup(summaryOf(item), r.format, r.plaintextSize))
            }
        } catch (e: CancellationException) {
            staging.discard()
            throw e
        } catch (e: Exception) {
            staging.discard()
            Outcome.Failed(DriveReason.of(e))
        }
    }

    /** Backs up when the schedule says one is due and *Automatic backup* is on (daily; back-off after a failure). */
    suspend fun runDueBackup(): DueBackupResult {
        if (ready == null) return DueBackupResult.NotRan(BackupSchedule.Reason.NOT_READY)
        if (!autoBackupEnabled()) return DueBackupResult.NotRan(BackupSchedule.Reason.DISABLED)
        return try {
            ops.withLock {
                val decision = backup.schedule(enabled = true, ready = true)
                if (!decision.backup) {
                    DueBackupResult.NotRan(decision.reason)
                } else {
                    when (val out = backUpLocked()) {
                        is Outcome.Ok -> DueBackupResult.Ran(decision.reason)
                        is Outcome.Failed -> DueBackupResult.Failed(out.reason)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DueBackupResult.Failed(DriveReason.of(e))
        }
    }

    fun autoBackupEnabled(): Boolean = when (recall(KEY_AUTO_BACKUP)) {
        "1" -> true
        "0" -> false
        else -> memoryAutoBackup
    }

    fun setAutoBackup(enabled: Boolean) {
        memoryAutoBackup = enabled
        remember(KEY_AUTO_BACKUP, if (enabled) "1" else "0")
    }

    private fun summaryOf(b: DriveBackup) = BackupSummary(b.fileId, b.createdAt, b.houses, b.size, b.name)

    // ==================== Sync and photos ====================

    /** One sync pass. [confirmShrink] is the person's yes to the shrink guard after [SyncState.NEEDS_CONFIRMATION]. */
    suspend fun syncNow(confirmShrink: Boolean = false): SyncInfo {
        val s = session ?: return SyncInfo(SyncState.ERROR, lastSync?.lastSyncAt, error = DriveReason.NOT_CONNECTED)
        return ops.withLock {
            val r = if (rig != null && rigSession === s) rig!! else syncRigs.create(s).also { rig = it; rigSession = s }
            val backend = r.backend
            val before = backend.lastResult
            val info = try {
                if (confirmShrink) backend.confirmShrink()
                syncDriver?.invoke(backend) ?: backend.commitPushes()
                resultInfo(backend.lastResult)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveException) {
                val now = backend.lastResult
                when {
                    e.kind == DriveException.Kind.CANCELLED && e.reason == "paused" -> SyncInfo(SyncState.PAUSED, lastSync?.lastSyncAt)
                    e.kind == DriveException.Kind.RATE_LIMITED && now !== before && now is SyncPassResult.Waiting ->
                        SyncInfo(SyncState.WAITING, lastSync?.lastSyncAt)
                    e.kind == DriveException.Kind.OFFLINE -> SyncInfo(SyncState.OFFLINE, lastSync?.lastSyncAt, error = DriveReason.OFFLINE)
                    else -> SyncInfo(SyncState.ERROR, lastSync?.lastSyncAt, error = DriveReason.of(e))
                }
            } catch (e: DriveSyncNotYet) {
                SyncInfo(SyncState.ERROR, lastSync?.lastSyncAt, error = DriveReason.FAILED)
            } catch (e: Exception) {
                SyncInfo(SyncState.ERROR, lastSync?.lastSyncAt, error = DriveReason.of(e))
            }
            lastSync = info
            info
        }
    }

    private fun resultInfo(result: SyncPassResult?): SyncInfo = when (result) {
        null -> SyncInfo(SyncState.ERROR, lastSync?.lastSyncAt, error = DriveReason.FAILED)
        is SyncPassResult.Waiting -> SyncInfo(SyncState.WAITING, lastSync?.lastSyncAt)
        SyncPassResult.Paused -> SyncInfo(SyncState.PAUSED, lastSync?.lastSyncAt)
        is SyncPassResult.NeedsConfirmation ->
            SyncInfo(SyncState.NEEDS_CONFIRMATION, clock(), result.report.skipped.map { it.reason }, housesToDelete = result.housesToDelete, liveHouses = result.liveHouses)
        is SyncPassResult.Done -> {
            val skipped = result.report.skipped.map { it.reason }
            SyncInfo(if (skipped.isEmpty()) SyncState.SYNCED else SyncState.SKIPPED_FILES, clock(), skipped)
        }
    }

    /** The last result, or [SyncState.NOT_RUN] before the first pass of this connection. */
    fun syncStatus(): SyncInfo = lastSync ?: SyncInfo(SyncState.NOT_RUN, null)

    /** The photo setting: false = Wi-Fi only (the default). */
    fun photoSettings(): PhotoSettings = photoSettings

    fun setPhotosWifiOnly(wifiOnly: Boolean) {
        photoSettings = PhotoSettings.fromWifiOnly(wifiOnly)
        remember(KEY_PHOTOS_MOBILE, if (photoSettings.uploadOnMobileData) "1" else "0")
    }

    /** *Upload photos now over mobile data*: a 30-minute exception to the Wi-Fi rule. */
    fun uploadPhotosNowOverMobile(): OneOffGrant = photoGate.grantOneOff()

    /** Whether photo bytes may travel now (pass it as `photosAllowed` to the repository's sync). */
    fun photosAllowed(): Boolean = photoGate.photosAllowed()

    fun photoStatus(pendingPhotos: Int): PhotoNetworkStatus = photoGate.status(pendingPhotos)

    /** Bytes of live photos known here and not yet in Drive; 0 before connect; null when the store fails. */
    suspend fun pendingPhotoBytes(): Long? {
        val r = rig ?: return 0
        return try {
            val refs = r.photos.refs()
            var pending = 0L
            for (row in r.local.all()) {
                if (row.kind == SyncKind.PHOTOS && !row.stamp.deleted && row.key !in refs) {
                    pending += (row.json["sizeBytes"] as? JsonPrimitive)?.longOrNull ?: 0L
                }
            }
            pending
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Builds the sync stack for the open folder (so [pendingPhotoBytes] has an answer before the first pass). */
    fun prepareSync() {
        val s = session ?: return
        if (rig == null || rigSession !== s) {
            rig = syncRigs.create(s)
            rigSession = s
        }
    }

    // ==================== Devices and enrolment ====================

    /** This device's public key, for the QR code and the 8-digit request. */
    fun devicePublicKey(): ByteArray = identity.key.publicKey.copyOf()

    fun listedDevices(): List<ListedDevice> {
        val folder = ready ?: return emptyList()
        val mine = Bytes.hex(kidOf(p, identity.key.publicKey))
        return folder.keys.body.devices.map { ListedDevice(Bytes.hex(it.kid), it.name, it.platform.wire, Bytes.hex(it.kid) == mine) }
    }

    /** The Google account from Drive's `about`, or null when Drive cannot say. */
    suspend fun accountEmail(): String? = try {
        drive.about().email.trim().takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** After the 8-digit codes match: device check (L2), then list the new device and wrap the folder key for exactly [publicKey]. */
    suspend fun approveJoinedDevice(publicKey: ByteArray, name: String, platform: DevicePlatform, promptReason: String): Outcome<EnrolmentWrap> {
        authorizePolicy(PolicyAction.APPROVE_DEVICE, promptReason)?.let { return Outcome.Failed(it) }
        return approved { enrolment.approveDevice(publicKey, name, platform) }
    }

    /** After the QR text (`dp1.`, parsed by the caller into key and PSK): device check, then a PSK wrap of the folder key. */
    suspend fun approveJoinedDevicePsk(publicKey: ByteArray, name: String, platform: DevicePlatform, psk: ByteArray, promptReason: String): Outcome<EnrolmentWrap> {
        authorizePolicy(PolicyAction.APPROVE_DEVICE, promptReason)?.let { return Outcome.Failed(it) }
        return approved { enrolment.approveDevicePsk(publicKey, name, platform, psk) }
    }

    private suspend fun approved(block: suspend () -> EnrolmentApproval): Outcome<EnrolmentWrap> = try {
        ops.withLock {
            when (val a = block()) {
                is EnrolmentApproval.Failed -> Outcome.Failed(DriveReason.of(a.problem.kind))
                is EnrolmentApproval.Approved -> {
                    setReady(a.connection)
                    Outcome.Ok(EnrolmentWrap(Bytes.b64(a.wrapEnc), Bytes.b64(a.wrapCt), a.epoch))
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failed(DriveReason.of(e))
    }

    /** The newcomer opens the approver's wrap (base64 text from the code screen) and pins this device. */
    suspend fun joinFromWrap(wrapEnc: String, wrapCt: String, epoch: Int): ConnectResult {
        val enc = Bytes.unb64(wrapEnc)
        val ct = Bytes.unb64(wrapCt)
        if (enc == null || ct == null) return ConnectResult(_state.value, error = DriveReason.ENROL_BAD_MESSAGE)
        return joined { enrolment.joinFromWrap(enc, ct, epoch) }
    }

    /** The newcomer opens the PSK wrap (QR enrolment) and pins this device. */
    suspend fun joinFromPsk(wrapEnc: String, wrapCt: String, epoch: Int, psk: ByteArray): ConnectResult {
        val enc = Bytes.unb64(wrapEnc)
        val ct = Bytes.unb64(wrapCt)
        if (enc == null || ct == null) return ConnectResult(_state.value, error = DriveReason.ENROL_BAD_MESSAGE)
        return joined { enrolment.joinFromPsk(enc, ct, epoch, psk) }
    }

    private suspend fun joined(block: suspend () -> DriveConnection): ConnectResult = try {
        ops.withLock { handle(block()) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fail(e)
    }

    /**
     * Revoke one listed device (L2, device check): a new epoch and a new recovery key, shown once by the caller. A
     * refusal by the core (rollback, fork) leaves the folder open as it was and says why.
     */
    suspend fun revokeListedDevice(kidHex: String, promptReason: String): Outcome<RevokeDone> {
        authorizePolicy(PolicyAction.REVOKE_DEVICE, promptReason)?.let { return Outcome.Failed(it) }
        val kid = try {
            Bytes.unhex(kidHex)
        } catch (_: Exception) {
            return Outcome.Failed(DriveReason.FAILED)
        }
        return try {
            ops.withLock {
                val out = enrolment.revokeDevice(kid)
                val connection = out.connection
                val key = out.recoveryKey
                if (connection !is DriveConnection.Ready || key == null) {
                    Outcome.Failed(if (connection is DriveConnection.Error) DriveReason.of(connection.problem.kind) else DriveReason.FAILED)
                } else {
                    setReady(connection)
                    Outcome.Ok(RevokeDone(key.display))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(DriveReason.of(e))
        }
    }

    // ==================== Deleting (docs/15 §10) ====================

    /** What the delete would remove (counts and bytes only). */
    suspend fun deletePlan(action: DeletionAction): Outcome<DeletionPlan> {
        val folder = ready ?: return Outcome.Failed(DriveReason.NOT_CONNECTED)
        return try {
            when (val r = deletion.preflight(folder.rootId, action)) {
                is PlanResult.Ready -> Outcome.Ok(r.plan)
                is PlanResult.Refused -> Outcome.Failed(reasonOf(r.reason))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(DriveReason.of(e))
        }
    }

    /** The policy's answer for [action] now: level, factor, whether a tick box and a delay are asked. */
    suspend fun deleteConfirmInfo(action: DeletionAction): Outcome<DeleteConfirmInfo> =
        when (val d = decide(action, contextFor(action))) {
            is DeletionDecision.Refused -> Outcome.Failed(reasonOf(d.reason))
            is DeletionDecision.Allowed -> {
                val factor = factorOf(d.requirements.factor) ?: return Outcome.Failed(DriveReason.AUTH_NOT_AVAILABLE)
                Outcome.Ok(
                    DeleteConfirmInfo(
                        PhoneDeletionAuthorizer.levelOf(d.requirements.level), factor, d.requirements.tickBox, d.requirements.delaySeconds * 1000L,
                    ),
                )
            }
        }

    /** [DeleteFactor.NONE] or [DeleteFactor.DEVICE_AUTH]; null when the action is refused. Never a passkey, never the recovery key. */
    suspend fun deleteFactor(action: DeletionAction): DeleteFactor? = try {
        when (val d = decide(action, contextFor(action))) {
            is DeletionDecision.Refused -> null
            is DeletionDecision.Allowed -> factorOf(d.requirements.factor)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** The device check for [plan] (bound to its operation id, valid 60 seconds, one use). */
    suspend fun authorizeDelete(plan: DeletionPlan, promptReason: String): Outcome<DeleteGrant> =
        authorizeFor(plan.action, plan.operationId, plan.level, promptReason)

    /** The device check for the interrupted delete (the pending list names its action, level and operation). */
    suspend fun authorizeResume(promptReason: String): Outcome<DeleteGrant> {
        val pending = try {
            deletionStore.pending()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return Outcome.Failed(DriveReason.DELETE_NOTHING_PENDING)
        return authorizeFor(pending.action, pending.operationId, pending.level, promptReason)
    }

    private suspend fun authorizeFor(action: DeletionAction, operationId: String, level: DeletionLevel, promptReason: String): Outcome<DeleteGrant> {
        return try {
            val ctx = contextFor(action, backupsLeftOverride = if (action is DeletionAction.OneBackup) (if (level == DeletionLevel.L1) 2 else 1) else null)
            when (val d = decide(action, ctx)) {
                is DeletionDecision.Refused -> Outcome.Failed(reasonOf(d.reason))
                is DeletionDecision.Allowed -> {
                    if (factorOf(d.requirements.factor) == null) return Outcome.Failed(DriveReason.AUTH_NOT_AVAILABLE)
                    when (val a = authorizer.authorize(policyOf(action), ctx, operationId, promptReason)) {
                        is DeleteAuthorization.Refused -> Outcome.Failed(a.reason)
                        is DeleteAuthorization.Granted -> Outcome.Ok(DeleteGrant(a.token))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(DriveReason.FAILED)
        }
    }

    /** Runs [plan]. [grant] may be null only for a level 1 delete (the service refuses otherwise). */
    suspend fun executeDelete(plan: DeletionPlan, grant: DeleteGrant?): Outcome<DeleteRun> =
        ranOutcome { deletion.delete(plan, grant?.token) }

    /** Continues an interrupted delete with a fresh [grant] from [authorizeResume]. */
    suspend fun resumeDelete(grant: DeleteGrant?): Outcome<DeleteRun> = ranOutcome { deletion.resume(grant?.token) }

    private suspend fun ranOutcome(run: suspend () -> DeletionOutcome): Outcome<DeleteRun> = try {
        when (val o = run()) {
            is DeletionOutcome.Refused -> Outcome.Failed(reasonOf(o.reason))
            is DeletionOutcome.Ran -> {
                // *Delete everything* took the folder: this device no longer has one open.
                if (o.finished && o.marker?.level == DeletionLevel.L3) {
                    clearConnection()
                    _state.value = ConnectState.DISCONNECTED
                }
                Outcome.Ok(DeleteRun(o.finished, o.report.left.size, o.total, o.stopped))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failed(DriveReason.of(e))
    } finally {
        authorizer.forget()
    }

    /** An interrupted delete that [resumeDelete] can finish, or null. */
    suspend fun pendingDeletion(): PendingDeletion? = deletionStore.pending()

    /** Set once a *Delete everything* or *Delete all backups* finished here: the reconnect path asks first. */
    suspend fun deletedMarker(): DeletedMarker? = deletionStore.marker()

    // ==================== Policy plumbing ====================

    private fun policyOf(action: DeletionAction): PolicyAction = when (action) {
        is DeletionAction.OneBackup -> PolicyAction.DELETE_ONE_BACKUP
        DeletionAction.OlderBackups, DeletionAction.AllBackups -> PolicyAction.DELETE_ALL_BACKUPS
        DeletionAction.Everything -> PolicyAction.DELETE_EVERYTHING
    }

    private fun decide(action: DeletionAction, ctx: DeletionContext) = DeletionPolicy.decide(policyOf(action), ctx)

    private fun factorOf(f: Factor): DeleteFactor? = when (f) {
        Factor.NONE -> DeleteFactor.NONE
        Factor.DEVICE_AUTH -> DeleteFactor.DEVICE_AUTH
        // The phones have no passkey, and the recovery key is the website's last resort only (docs/15 §10.4a).
        Factor.PASSKEY -> null
    }

    private suspend fun contextFor(action: DeletionAction, backupsLeftOverride: Int? = null): DeletionContext =
        phoneContext(backupsLeftOverride ?: if (action is DeletionAction.OneBackup) backupsLeft() else null)

    private fun phoneContext(backupsLeft: Int?) = DeletionContext(
        AuthPlatform.PHONE, authorizer.isDeviceLockEnabled(), webPrf = false, online = network.current().online, backupsLeft = backupsLeft,
    )

    /** Complete backups in the folder now; null when unknown (the policy then fails closed to level 2). */
    private suspend fun backupsLeft(): Int? = when (val l = listBackups()) {
        is Outcome.Ok -> l.value.backups.size
        is Outcome.Failed -> null
    }

    private suspend fun authorizePolicy(action: PolicyAction, promptReason: String): DriveReason? {
        val ctx = phoneContext(null)
        return when (val d = DeletionPolicy.decide(action, ctx)) {
            is DeletionDecision.Refused -> reasonOf(d.reason)
            is DeletionDecision.Allowed -> {
                if (factorOf(d.requirements.factor) == null) return DriveReason.AUTH_NOT_AVAILABLE
                try {
                    when (val a = authorizer.authorize(action, ctx, null, promptReason)) {
                        is DeleteAuthorization.Refused -> a.reason
                        is DeleteAuthorization.Granted -> null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    DriveReason.FAILED
                } finally {
                    authorizer.forget()
                }
            }
        }
    }

    private fun reasonOf(r: RefusalReason): DriveReason = when (r) {
        RefusalReason.NO_DEVICE_LOCK -> DriveReason.NO_DEVICE_LOCK
        RefusalReason.OFFLINE -> DriveReason.DELETE_OFFLINE
        RefusalReason.USE_PHONE -> DriveReason.AUTH_NOT_AVAILABLE
    }

    private fun reasonOf(r: Refusal): DriveReason = when (r) {
        Refusal.OFFLINE -> DriveReason.DELETE_OFFLINE
        Refusal.NOT_AUTHORIZED -> DriveReason.DELETE_NOT_AUTHORIZED
        Refusal.AUTHORIZATION_TOO_WEAK -> DriveReason.DELETE_AUTHORIZATION_TOO_WEAK
        Refusal.AUTHORIZATION_STALE -> DriveReason.DELETE_AUTHORIZATION_STALE
        Refusal.AUTHORIZATION_OTHER_OPERATION -> DriveReason.DELETE_AUTHORIZATION_OTHER_OPERATION
        Refusal.STALE_PLAN -> DriveReason.DELETE_STALE_PLAN
        Refusal.ROOT_NOT_FOUND -> DriveReason.DELETE_ROOT_NOT_FOUND
        Refusal.NOT_A_BACKUP -> DriveReason.DELETE_NOT_A_BACKUP
        Refusal.NOTHING_TO_DELETE -> DriveReason.DELETE_NOTHING_TO_DELETE
        Refusal.OTHER_DELETION_PENDING -> DriveReason.DELETE_OTHER_DELETION_PENDING
        Refusal.NOTHING_PENDING -> DriveReason.DELETE_NOTHING_PENDING
        Refusal.DRIVE_ERROR -> DriveReason.DELETE_DRIVE_ERROR
    }

    private fun recall(key: String): String? = try {
        prefs.get(key)
    } catch (_: Exception) {
        null
    }

    private fun remember(key: String, value: String) {
        try {
            prefs.put(key, value)
        } catch (_: Exception) {
            // Storage refused: the in-memory choice lasts until the app closes.
        }
    }

    companion object {
        const val KEY_AUTO_BACKUP = "doorprints.drive.autoBackup"
        const val KEY_PHOTOS_MOBILE = "doorprints.drive.photosOnMobile"
    }
}

/** The proof that the person passed the device check for one delete. Opaque; hand it to `executeDelete` or `resumeDelete`. */
class DeleteGrant internal constructor(internal val token: app.doorprints.drive.delete.AuthorizationToken?) {
    override fun toString() = "DeleteGrant"
}
