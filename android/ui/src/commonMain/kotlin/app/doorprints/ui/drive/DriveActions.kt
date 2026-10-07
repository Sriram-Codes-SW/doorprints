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

package app.doorprints.ui.drive

import app.doorprints.crypto.DevicePlatform
import app.doorprints.drive.connect.BackUpDone
import app.doorprints.drive.connect.BackupList
import app.doorprints.drive.connect.ConnectResult
import app.doorprints.drive.connect.ConnectState
import app.doorprints.drive.connect.DeleteConfirmInfo
import app.doorprints.drive.connect.DeleteRun
import app.doorprints.drive.connect.DriveConnectController
import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.connect.EnrolmentWrap
import app.doorprints.drive.connect.ListedDevice
import app.doorprints.drive.connect.Outcome
import app.doorprints.drive.connect.RevokeDone
import app.doorprints.drive.connect.SyncInfo
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionPlan
import app.doorprints.drive.photo.PhotoNetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The part of [DriveConnectController] the screens use, as an interface: the screens' state holder ([DriveHolder]) is
 * tested against a fake of this, and the app wraps the real controller in [ControllerDriveActions]. Android-only
 * classes never appear here (the sign-in, the device check and the database live behind the controller's seams).
 *
 * The delete's device check and the delete itself are one call each ([runDelete], [resumeDelete]): the controller
 * hands out a grant that only it can make, and a grant is good for one use within 60 seconds, so asking and running
 * belong together.
 */
interface DriveActions {
    val state: StateFlow<ConnectState>
    val enrolmentNotice: StateFlow<DriveReason?>

    /**
     * True while the app knows the Drive folder was deleted elsewhere and the person has not answered (docs/15 section
     * 3.4): the card then asks *Start again* or *Disconnect*. The app keeps Drive engaged until the person answers, and
     * never creates a folder by itself. Default: never.
     */
    val folderGone: StateFlow<Boolean> get() = NoFolderGone

    suspend fun connect(): ConnectResult
    suspend fun createFolder(): ConnectResult
    suspend fun openWithRecoveryKey(text: String): ConnectResult
    fun confirmRecoveryKeySaved()
    fun skipRecoveryKeyWithWarning()
    suspend fun disconnect()
    suspend fun disconnectAll(promptReason: String): Outcome<Unit>
    suspend fun accountEmail(): String?

    suspend fun listBackups(): Outcome<BackupList>
    suspend fun backUpNow(): Outcome<BackUpDone>
    suspend fun confirmShrink(backupId: String)
    fun autoBackupEnabled(): Boolean
    fun setAutoBackup(enabled: Boolean)

    suspend fun syncNow(confirmShrink: Boolean): SyncInfo
    fun syncStatus(): SyncInfo
    fun photosWifiOnly(): Boolean
    fun setPhotosWifiOnly(wifiOnly: Boolean)
    fun uploadPhotosNowOverMobile()
    suspend fun pendingPhotoBytes(): Long?
    fun photoStatus(pendingPhotos: Int): PhotoNetworkStatus

    fun devicePublicKey(): ByteArray
    fun listedDevices(): List<ListedDevice>
    suspend fun approveJoinedDevicePsk(publicKey: ByteArray, name: String, platform: DevicePlatform, psk: ByteArray, promptReason: String): Outcome<EnrolmentWrap>
    suspend fun joinFromPsk(wrapEnc: String, wrapCt: String, epoch: Int, psk: ByteArray): ConnectResult
    suspend fun revokeListedDevice(kidHex: String, promptReason: String): Outcome<RevokeDone>

    suspend fun deletePlan(action: DeletionAction): Outcome<DeletionPlan>
    suspend fun deleteConfirmInfo(action: DeletionAction): Outcome<DeleteConfirmInfo>

    /** The device check, then the delete. */
    suspend fun runDelete(plan: DeletionPlan, promptReason: String): Outcome<DeleteRun>

    /** The device check, then the rest of an interrupted delete. */
    suspend fun resumeDelete(promptReason: String): Outcome<DeleteRun>
}

/** The [DriveActions.folderGone] of an app that has nothing to say about it. */
val NoFolderGone: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

/** [DriveActions] over the one shared controller (both phones). */
class ControllerDriveActions(
    private val c: DriveConnectController,
    override val folderGone: StateFlow<Boolean> = NoFolderGone,
) : DriveActions {
    override val state get() = c.state
    override val enrolmentNotice get() = c.enrolmentNotice

    override suspend fun connect() = c.connect()
    override suspend fun createFolder() = c.createFolder(withRecoveryKey = true)
    override suspend fun openWithRecoveryKey(text: String) = c.openWithRecoveryKey(text)
    override fun confirmRecoveryKeySaved() = c.confirmRecoveryKeySaved()
    override fun skipRecoveryKeyWithWarning() = c.skipRecoveryKeyWithWarning()
    override suspend fun disconnect() = c.disconnect()
    override suspend fun disconnectAll(promptReason: String) = c.disconnectAll(promptReason)
    override suspend fun accountEmail() = c.accountEmail()

    override suspend fun listBackups() = c.listBackups()
    override suspend fun backUpNow() = c.backUpNow()
    override suspend fun confirmShrink(backupId: String) = c.confirmShrink(backupId)
    override fun autoBackupEnabled() = c.autoBackupEnabled()
    override fun setAutoBackup(enabled: Boolean) = c.setAutoBackup(enabled)

    override suspend fun syncNow(confirmShrink: Boolean) = c.syncNow(confirmShrink)
    override fun syncStatus() = c.syncStatus()
    override fun photosWifiOnly() = !c.photoSettings().uploadOnMobileData
    override fun setPhotosWifiOnly(wifiOnly: Boolean) = c.setPhotosWifiOnly(wifiOnly)
    override fun uploadPhotosNowOverMobile() {
        c.uploadPhotosNowOverMobile()
    }
    override suspend fun pendingPhotoBytes() = c.pendingPhotoBytes()
    override fun photoStatus(pendingPhotos: Int) = c.photoStatus(pendingPhotos)

    override fun devicePublicKey() = c.devicePublicKey()
    override fun listedDevices() = c.listedDevices()
    override suspend fun approveJoinedDevicePsk(publicKey: ByteArray, name: String, platform: DevicePlatform, psk: ByteArray, promptReason: String) =
        c.approveJoinedDevicePsk(publicKey, name, platform, psk, promptReason)
    override suspend fun joinFromPsk(wrapEnc: String, wrapCt: String, epoch: Int, psk: ByteArray) = c.joinFromPsk(wrapEnc, wrapCt, epoch, psk)
    override suspend fun revokeListedDevice(kidHex: String, promptReason: String) = c.revokeListedDevice(kidHex, promptReason)

    override suspend fun deletePlan(action: DeletionAction) = c.deletePlan(action)
    override suspend fun deleteConfirmInfo(action: DeletionAction) = c.deleteConfirmInfo(action)

    override suspend fun runDelete(plan: DeletionPlan, promptReason: String): Outcome<DeleteRun> =
        when (val grant = c.authorizeDelete(plan, promptReason)) {
            is Outcome.Failed -> grant
            is Outcome.Ok -> c.executeDelete(plan, grant.value)
        }

    override suspend fun resumeDelete(promptReason: String): Outcome<DeleteRun> =
        when (val grant = c.authorizeResume(promptReason)) {
            is Outcome.Failed -> grant
            is Outcome.Ok -> c.resumeDelete(grant.value)
        }
}

// ---- Seams the app layer implements -------------------------------------------------------------------------------

/**
 * Scanning a QR code: a platform camera seam (CameraX or ML Kit on Android, AVFoundation on iOS). The screens only
 * ask for one scan and show the text; there is no camera code in :ui. [isAvailable] false hides *Scan a QR code* and
 * the screen offers paste instead.
 */
interface QrScanner {
    val isAvailable: Boolean

    /** Opens the camera until a code is read or the person leaves. */
    suspend fun scan(): QrScan
}

sealed interface QrScan {
    data class Scanned(val text: String) : QrScan
    data object Cancelled : QrScan
    data object NoCamera : QrScan

    /** The person (or the system) refused the camera for this app: the screen says so and offers the app's Settings (S4b-BL-141). */
    data object Denied : QrScan
}

/** No camera: the screens offer paste only. */
object NoQrScanner : QrScanner {
    override val isAvailable = false
    override suspend fun scan(): QrScan = QrScan.NoCamera
}

/**
 * Copying a secret (the recovery key, an enrolment message) to the clipboard. The app marks the clip sensitive so the
 * system does not show it in the clipboard preview or keep it in the history (Android 13 and later:
 * `ClipDescription.EXTRA_IS_SENSITIVE`; iOS: a local-only, expiring pasteboard item). :ui is common code, so the screens
 * only call this; without an implementation a plain copy is made.
 */
interface ClipboardSeam {
    fun copySensitive(text: String)
}

/**
 * The text of the two enrolment messages (the `dp1.` QR payload and the reply), made and read outside :ui: it needs the
 * HPKE PSK mode from the enrolment branch. The 8-digit code is the same on both screens ([NewcomerOffer.code],
 * [ApproverOffer.code]), so the person can compare it instead of scanning.
 */
interface EnrolmentCodec {
    /** A new offer for this device's [publicKey]: the text for the QR code, the code, and the secret that opens the reply. */
    fun newOffer(publicKey: ByteArray, deviceName: String): NewcomerOffer

    /** The approver reads the newcomer's offer (scanned or pasted), or null when it is not one. */
    fun parseOffer(text: String): ApproverOffer?

    /** The reply the approver shows (as a QR code and as text) for the wrap the controller made. */
    fun encodeReply(wrap: EnrolmentWrap): String

    /** The newcomer reads the approver's reply for [offer], or null when it is not one. */
    fun parseReply(text: String, offer: NewcomerOffer): ReplyWrap?
}

/** [psk] is secret: it never reaches a string. */
class NewcomerOffer(val qrText: String, val code: String, val psk: ByteArray) {
    override fun toString() = "NewcomerOffer(<secret>)"
}

class ApproverOffer(val publicKey: ByteArray, val deviceName: String, val platform: DevicePlatform, val psk: ByteArray, val code: String) {
    override fun toString() = "ApproverOffer($deviceName, <secret>)"
}

class ReplyWrap(val enc: String, val ct: String, val epoch: Int)
