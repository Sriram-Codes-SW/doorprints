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

import app.doorprints.deviceauth.RefusalReason
import app.doorprints.drive.backup.DriveBackup
import app.doorprints.drive.backup.DriveProblem
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.ItemKind
import app.doorprints.drive.delete.Refusal

/**
 * What Settings > Google Drive shows (S4b-BL-117; docs/15 §5.7, §9.4, §3.2). The controller ([DriveSettingsController])
 * moves between these; the screens only draw them, so every transition is a plain unit test. The website has the same
 * model in `drive-settings.service.ts`.
 */
enum class DriveStage {
    /** No OAuth client id was supplied at build time: the whole section is not shown. */
    HIDDEN,

    /** Not connected: the *Connect Google Drive* button. */
    DISCONNECTED,

    /** A phone without a screen lock (docs/15 §10.3): *Open settings*, nothing is connected. */
    NEEDS_SCREEN_LOCK,

    /** Signing in or reading the folder: a spinner. */
    WORKING,

    /** Signed in and Drive has no Doorprints folder: explain, then make it with a recovery key. */
    FIRST_CONNECT,

    /** The new recovery key, shown once. */
    RECOVERY_KEY,

    /** The folder has backups from other devices and this one is not enrolled (QR enrolment is S4b-BL-126). */
    NEEDS_ENROLMENT,

    /** This device's key no longer opens the key list: the recovery key is the way back. */
    NEEDS_RECOVERY_KEY,

    /** The folder this device used is gone or in Drive's bin: nothing is re-created without a tick. */
    FOLDER_GONE,

    READY,

    /** The screen lock is gone (or could not be read): Drive is paused, nothing is uploaded or deleted. */
    PAUSED,

    /** A step failed; [DriveUiState.message] says what in plain words. Nothing was changed. */
    PROBLEM,
}

/** Every sentence the controller can ask the screen to say. One string key per entry in each language, tested. */
enum class DriveMessage {
    // Connect
    SIGNIN_FAILED, SIGNIN_OFFLINE, SIGNIN_NOT_AVAILABLE, SIGNIN_WRONG_SCOPE, SIGNIN_STATE, SIGNIN_STORE,
    DISCONNECTED_BY_GOOGLE, DISCONNECTED_DONE,

    // Drive and the key set
    OFFLINE, QUOTA, RATE_LIMITED, SERVER, DRIVE, CORRUPT,
    KEYS_ROLLED_BACK, KEYS_UNTRUSTED, KEYS_UNREADABLE, WRONG_RECOVERY_KEY, NO_RECOVERY_KEY, DEVICE_REVOKED,
    CONTROL_ROLLED_BACK, CONTROL_INVALID, FOLDER_WITHOUT_KEYS, FOLDER_EXISTS, CRYPTO_UNAVAILABLE,
    BACKUP_REFUSED, BACKUP_GONE, SOURCE_FAILED, RECOVERY_GROUPS_WRONG,

    // Backups
    BACKUP_DONE, SHRINK_HOLD, IMPORT_READY,

    // Pause
    PAUSED_NO_LOCK, PAUSED_UNKNOWN,

    // Deleting
    NOTHING_DELETED, DELETE_NO_LOCK, DELETE_USE_PHONE, DELETE_OFFLINE, DELETE_STALE, DELETE_NOTHING_TO_DELETE,
    DELETE_OTHER_PENDING, DELETE_DRIVE_ERROR, DELETE_DONE_BACKUP, DELETE_DONE_ALL, DELETE_DONE_EVERYTHING,
    DELETE_STOPPED, DELETE_LOCK_LOST, DELETE_DENIED, DELETE_CANCELLED, DELETE_FOLDER_KEPT,
}

/** What the controller is busy with; the screen disables the buttons and shows a spinner. */
enum class DriveBusy { CONNECTING, READING, BACKING_UP, LISTING, IMPORTING, DELETING, DISCONNECTING }

enum class RecoveryStatus {
    /** Not asked yet or not connected. */
    UNKNOWN,

    /** The person skipped it after the warning: Settings says "No recovery key". */
    SKIPPED,

    /** Shown but the person never confirmed saving it (the app was closed first). */
    UNCONFIRMED,

    /** Saved, and two groups were typed back. */
    SAVED,
}

/** The key as shown once (grouped, `DP7K-3QXW-…`), and the two groups asked back. Never stored. */
class RecoveryKeyStep(
    val display: String,
    /** 1-based positions of the groups asked back. */
    val askGroups: List<Int>,
    val wrongTries: Int = 0,
) {
    override fun toString() = "RecoveryKeyStep(…)"
    fun copy(wrongTries: Int = this.wrongTries) = RecoveryKeyStep(display, askGroups, wrongTries)
}

data class BackupRow(val id: String, val createdAt: Long, val houses: Int, val sizeBytes: Long?, val isNewest: Boolean)

data class ReadyView(
    val backups: List<BackupRow> = emptyList(),
    /** This device's last verified upload, else the newest listed backup; null when there is none. */
    val lastBackupAt: Long? = null,
    /** Unfinished uploads and files that are not backups, counted only. */
    val unfinished: Int = 0,
    val ignored: Int = 0,
    val missingNewer: Boolean = false,
    val shrinkHoldBackupId: String? = null,
    /** A deletion that was stopped half way and can be finished (*Try again*): the files left. */
    val pendingDeletionLeft: Int? = null,
    val storageUsedBytes: Long? = null,
    val storageLimitBytes: Long? = null,
)

/** The three delete menus' choices. */
sealed interface DriveDeleteChoice {
    data class OneBackup(val id: String) : DriveDeleteChoice
    data object OlderBackups : DriveDeleteChoice
    data object AllBackups : DriveDeleteChoice
    data object Everything : DriveDeleteChoice

    fun toAction(): DeletionAction = when (this) {
        is OneBackup -> DeletionAction.OneBackup(id)
        OlderBackups -> DeletionAction.OlderBackups
        AllBackups -> DeletionAction.AllBackups
        Everything -> DeletionAction.Everything
    }
}

enum class DeletePhase { CONFIRM, AUTHORIZING, DELETING }

/** The confirmation of docs/15 §3.2: what goes, the box, the 5 seconds. */
data class DeleteDialog(
    val choice: DriveDeleteChoice,
    val level: DeletionLevel,
    val counts: Map<ItemKind, Int>,
    val totalFiles: Int,
    val totalBytes: Long,
    val needsTick: Boolean,
    val delaySeconds: Int,
    val openedAtMs: Long,
    val ticked: Boolean = false,
    val phase: DeletePhase = DeletePhase.CONFIRM,
    /** Something Doorprints did not make is in the folder: it is left alone (docs/15 §3.3). */
    val foreignKept: Int = 0,
) {
    /** Whole seconds left of the delay at [nowMs], for the button's label. */
    fun secondsLeft(nowMs: Long): Int {
        val left = delaySeconds * 1000L - (nowMs - openedAtMs)
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    fun confirmEnabled(nowMs: Long): Boolean =
        phase == DeletePhase.CONFIRM && (!needsTick || ticked) && nowMs - openedAtMs >= delaySeconds * 1000L
}

data class DriveUiState(
    val stage: DriveStage = DriveStage.HIDDEN,
    val busy: DriveBusy? = null,
    val message: DriveMessage? = null,
    /** The Drive account, once known. */
    val account: String? = null,
    /** For [DriveStage.RECOVERY_KEY]. */
    val recoveryKey: RecoveryKeyStep? = null,
    /** NEEDS_ENROLMENT / NEEDS_RECOVERY_KEY: the folder has a recovery key to type (a hint from the unverified list). */
    val recoveryAvailable: Boolean = false,
    val recoveryStatus: RecoveryStatus = RecoveryStatus.UNKNOWN,
    val ready: ReadyView? = null,
    val autoBackup: Boolean = false,
    val photosWifiOnly: Boolean = true,
    /** The one-off *Upload photos now over mobile data* button: a placeholder until S4b-BL-128. */
    val mobileDataOneOffAvailable: Boolean = false,
    val dialog: DeleteDialog? = null,
) {
    val isConnected: Boolean get() = stage == DriveStage.READY || stage == DriveStage.PAUSED
}

/** One-off things the screen does, not state. */
sealed interface DriveEvent {
    /** *Save a copy first*: open *Save a copy* (a Full backup to this device) and come back. */
    data object OpenSaveCopy : DriveEvent

    /** *Open settings* for the phone's screen lock. */
    data object OpenLockSettings : DriveEvent

    /** The decrypted backup is ready for the existing *Import a backup* preview. */
    data object OpenImportPreview : DriveEvent
}

internal fun messageOf(p: DriveProblem): DriveMessage = when (p.kind) {
    DriveProblem.Kind.OFFLINE -> DriveMessage.OFFLINE
    DriveProblem.Kind.UNAUTHORIZED -> DriveMessage.DISCONNECTED_BY_GOOGLE
    DriveProblem.Kind.QUOTA_EXCEEDED -> DriveMessage.QUOTA
    DriveProblem.Kind.RATE_LIMITED -> DriveMessage.RATE_LIMITED
    DriveProblem.Kind.SERVER -> DriveMessage.SERVER
    DriveProblem.Kind.DRIVE -> DriveMessage.DRIVE
    DriveProblem.Kind.CORRUPT -> DriveMessage.CORRUPT
    DriveProblem.Kind.KEYS_ROLLED_BACK -> DriveMessage.KEYS_ROLLED_BACK
    DriveProblem.Kind.KEYS_UNTRUSTED -> DriveMessage.KEYS_UNTRUSTED
    DriveProblem.Kind.KEYS_UNREADABLE -> DriveMessage.KEYS_UNREADABLE
    DriveProblem.Kind.WRONG_RECOVERY_KEY -> DriveMessage.WRONG_RECOVERY_KEY
    DriveProblem.Kind.NO_RECOVERY_KEY -> DriveMessage.NO_RECOVERY_KEY
    DriveProblem.Kind.DEVICE_REVOKED -> DriveMessage.DEVICE_REVOKED
    DriveProblem.Kind.CONTROL_ROLLED_BACK -> DriveMessage.CONTROL_ROLLED_BACK
    DriveProblem.Kind.CONTROL_INVALID -> DriveMessage.CONTROL_INVALID
    DriveProblem.Kind.FOLDER_WITHOUT_KEYS -> DriveMessage.FOLDER_WITHOUT_KEYS
    DriveProblem.Kind.FOLDER_EXISTS -> DriveMessage.FOLDER_EXISTS
    DriveProblem.Kind.BACKUP_REFUSED -> DriveMessage.BACKUP_REFUSED
    DriveProblem.Kind.BACKUP_GONE -> DriveMessage.BACKUP_GONE
    DriveProblem.Kind.SOURCE_FAILED -> DriveMessage.SOURCE_FAILED
    DriveProblem.Kind.CRYPTO_UNAVAILABLE -> DriveMessage.CRYPTO_UNAVAILABLE
}

internal fun messageOf(r: Refusal): DriveMessage = when (r) {
    Refusal.OFFLINE -> DriveMessage.DELETE_OFFLINE
    Refusal.NOT_AUTHORIZED, Refusal.AUTHORIZATION_TOO_WEAK, Refusal.AUTHORIZATION_OTHER_OPERATION -> DriveMessage.DELETE_DENIED
    Refusal.AUTHORIZATION_STALE, Refusal.STALE_PLAN -> DriveMessage.DELETE_STALE
    Refusal.ROOT_NOT_FOUND -> DriveMessage.DELETE_NOTHING_TO_DELETE
    Refusal.NOT_A_BACKUP, Refusal.NOTHING_TO_DELETE, Refusal.NOTHING_PENDING -> DriveMessage.DELETE_NOTHING_TO_DELETE
    Refusal.OTHER_DELETION_PENDING -> DriveMessage.DELETE_OTHER_PENDING
    Refusal.DRIVE_ERROR -> DriveMessage.DELETE_DRIVE_ERROR
}

internal fun messageOf(r: RefusalReason): DriveMessage = when (r) {
    RefusalReason.NO_DEVICE_LOCK -> DriveMessage.DELETE_NO_LOCK
    RefusalReason.USE_PHONE -> DriveMessage.DELETE_USE_PHONE
    RefusalReason.OFFLINE -> DriveMessage.DELETE_OFFLINE
}

internal fun rowOf(b: DriveBackup, newest: Boolean) = BackupRow(b.fileId, b.createdAt, b.houses, b.size, newest)
