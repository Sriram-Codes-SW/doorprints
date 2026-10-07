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

import app.doorprints.drive.backup.BackupSchedule
import app.doorprints.drive.backup.DriveProblem
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.StopReason
import app.doorprints.drive.sync.SkipReason

/**
 * Where the Connect screen stands (the Kotlin twin of the web's `ConnectState`, `drive-connect.service.ts`). The state
 * never holds the recovery key: it travels once, in [ConnectResult.recoveryKey].
 */
enum class ConnectState {
    /** No Google client id in this build: Drive is not offered. */
    UNAVAILABLE,
    DISCONNECTED,
    CONNECTING,

    /** This device trusted the folder before, but its own key no longer opens the key list (revoked, not enrolled). */
    NEEDS_RECOVERY_KEY,

    /** A key set this device has no pin for: join by QR code, 8-digit code or the recovery key. Nothing is written. */
    NEEDS_ENROLMENT,

    /** The folder was just made: the recovery key is on screen once. Backups already work in this state. */
    FIRST_CONNECT_SHOW_RECOVERY_KEY,
    READY,
    ERROR,
}

/**
 * Every user-facing failure of the controller: a dictionary key the screen translates, never an exception message.
 * [key] is the same key the website uses where the website has one (docs/ops/android-drive-controller-notes.md).
 */
enum class DriveReason(val key: String) {
    // The connect card.
    NOT_CONFIGURED("driveConnect.notConfigured"),
    FOLDER_GONE("driveConnect.folderGone"),
    FAILED("driveConnect.failed"),
    CONNECTION_IN_PROGRESS("driveConnect.connectionInProgress"),
    NO_BACKUP_SOURCE("driveConnect.noBackupSource"),
    NO_BACKUPS("driveConnect.noBackups"),
    NOT_CONNECTED("driveBackups.error.notConnected"),
    BACKUP_NOT_FOUND("driveBackups.error.backupNotFound"),
    RETRIEVE_FAILED("driveBackups.error.retrieveFailed"),
    JOIN_INVALID_FORMAT("driveJoin.errorInvalidFormat"),
    JOIN_WRONG_KEY("driveJoin.errorWrongKey"),
    ENROL_BAD_MESSAGE("driveEnrol.badMessage"),

    // A Drive problem ([DriveProblem.Kind]).
    OFFLINE("driveProblem.OFFLINE"),
    UNAUTHORIZED("driveProblem.UNAUTHORIZED"),
    QUOTA_EXCEEDED("driveProblem.QUOTA_EXCEEDED"),
    RATE_LIMITED("driveProblem.RATE_LIMITED"),
    SERVER("driveProblem.SERVER"),
    DRIVE("driveProblem.DRIVE"),
    CORRUPT("driveProblem.CORRUPT"),
    KEYS_ROLLED_BACK("driveProblem.KEYS_ROLLED_BACK"),
    KEYS_UNTRUSTED("driveProblem.KEYS_UNTRUSTED"),
    KEYS_UNREADABLE("driveProblem.KEYS_UNREADABLE"),
    NO_RECOVERY_KEY("driveProblem.NO_RECOVERY_KEY"),
    DEVICE_REVOKED("driveProblem.DEVICE_REVOKED"),
    CONTROL_ROLLED_BACK("driveProblem.CONTROL_ROLLED_BACK"),
    CONTROL_INVALID("driveProblem.CONTROL_INVALID"),
    FOLDER_WITHOUT_KEYS("driveProblem.FOLDER_WITHOUT_KEYS"),
    FOLDER_EXISTS("driveProblem.FOLDER_EXISTS"),
    BACKUP_REFUSED("driveProblem.BACKUP_REFUSED"),
    SOURCE_FAILED("driveProblem.SOURCE_FAILED"),
    CRYPTO_UNAVAILABLE("driveProblem.CRYPTO_UNAVAILABLE"),

    // Sign-in (the phones' browser flow).
    SIGNIN_CLOSED("driveProblem.SIGNIN_CLOSED"),
    SIGNIN_DENIED("driveProblem.SIGNIN_DENIED"),
    SIGNIN_UNAVAILABLE("driveProblem.SIGNIN_UNAVAILABLE"),
    CONNECT_FAILED("driveProblem.CONNECT_FAILED"),

    // Deleting ([app.doorprints.drive.delete.Refusal]).
    DELETE_OFFLINE("driveDelete.reason.OFFLINE"),
    DELETE_NOT_AUTHORIZED("driveDelete.reason.NOT_AUTHORIZED"),
    DELETE_AUTHORIZATION_TOO_WEAK("driveDelete.reason.AUTHORIZATION_TOO_WEAK"),
    DELETE_AUTHORIZATION_STALE("driveDelete.reason.AUTHORIZATION_STALE"),
    DELETE_AUTHORIZATION_OTHER_OPERATION("driveDelete.reason.AUTHORIZATION_OTHER_OPERATION"),
    DELETE_STALE_PLAN("driveDelete.reason.STALE_PLAN"),
    DELETE_ROOT_NOT_FOUND("driveDelete.reason.ROOT_NOT_FOUND"),
    DELETE_NOT_A_BACKUP("driveDelete.reason.NOT_A_BACKUP"),
    DELETE_NOTHING_TO_DELETE("driveDelete.reason.NOTHING_TO_DELETE"),
    DELETE_OTHER_DELETION_PENDING("driveDelete.reason.OTHER_DELETION_PENDING"),
    DELETE_NOTHING_PENDING("driveDelete.reason.NOTHING_PENDING"),
    DELETE_DRIVE_ERROR("driveDelete.reason.DRIVE_ERROR"),

    // The device check (screen lock, fingerprint, face) that guards the level 2 and 3 actions.
    NO_DEVICE_LOCK("driveDelete.reason.NO_DEVICE_LOCK"),
    AUTH_CANCELLED("driveDelete.reason.AUTH_CANCELLED"),
    AUTH_TIMED_OUT("driveDelete.reason.AUTH_TIMED_OUT"),
    AUTH_FAILED("driveDelete.reason.AUTH_FAILED"),
    AUTH_LOCK_NOT_SET("driveDelete.reason.AUTH_LOCK_NOT_SET"),
    AUTH_NOT_AVAILABLE("driveDelete.reason.AUTH_NOT_AVAILABLE"),
    AUTH_LOCKED_OUT("driveDelete.reason.AUTH_LOCKED_OUT"),
    AUTH_PAUSED_NO_LOCK("driveDelete.reason.AUTH_PAUSED_NO_LOCK"),
    AUTH_PAUSED_UNKNOWN("driveDelete.reason.AUTH_PAUSED_UNKNOWN"),
    ;

    companion object {
        /** The screen key of a Drive problem (the twin of the web's `problemToMsg`). */
        fun of(kind: DriveProblem.Kind): DriveReason = when (kind) {
            DriveProblem.Kind.OFFLINE -> OFFLINE
            DriveProblem.Kind.UNAUTHORIZED -> UNAUTHORIZED
            DriveProblem.Kind.QUOTA_EXCEEDED -> QUOTA_EXCEEDED
            DriveProblem.Kind.RATE_LIMITED -> RATE_LIMITED
            DriveProblem.Kind.SERVER -> SERVER
            DriveProblem.Kind.DRIVE -> DRIVE
            DriveProblem.Kind.CORRUPT -> CORRUPT
            DriveProblem.Kind.KEYS_ROLLED_BACK -> KEYS_ROLLED_BACK
            DriveProblem.Kind.KEYS_UNTRUSTED -> KEYS_UNTRUSTED
            DriveProblem.Kind.KEYS_UNREADABLE -> KEYS_UNREADABLE
            DriveProblem.Kind.WRONG_RECOVERY_KEY -> JOIN_WRONG_KEY
            DriveProblem.Kind.NO_RECOVERY_KEY -> NO_RECOVERY_KEY
            DriveProblem.Kind.DEVICE_REVOKED -> DEVICE_REVOKED
            DriveProblem.Kind.CONTROL_ROLLED_BACK -> CONTROL_ROLLED_BACK
            DriveProblem.Kind.CONTROL_INVALID -> CONTROL_INVALID
            DriveProblem.Kind.FOLDER_WITHOUT_KEYS -> FOLDER_WITHOUT_KEYS
            DriveProblem.Kind.FOLDER_EXISTS -> FOLDER_EXISTS
            DriveProblem.Kind.BACKUP_REFUSED -> BACKUP_REFUSED
            DriveProblem.Kind.BACKUP_GONE -> BACKUP_NOT_FOUND
            DriveProblem.Kind.SOURCE_FAILED -> SOURCE_FAILED
            DriveProblem.Kind.CRYPTO_UNAVAILABLE -> CRYPTO_UNAVAILABLE
        }

        /** The screen key of anything thrown: a typed failure maps, anything else is [FAILED] (never the message). */
        fun of(e: Throwable): DriveReason = of(DriveProblem.of(e).kind).let {
            // DriveProblem.of turns an unknown throwable into SOURCE_FAILED; outside a backup run that is the generic failure.
            if (DriveProblem.isTyped(e)) it else FAILED
        }
    }
}

/** A result that is a value or a typed reason. */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    /** [code] is the [app.doorprints.drive.DriveCode] of an unexpected failure (only with [DriveReason.SOURCE_FAILED] or [DriveReason.FAILED]). */
    data class Failed(val reason: DriveReason, val code: String? = null) : Outcome<Nothing>
}

val <T> Outcome<T>.isOk: Boolean get() = this is Outcome.Ok

/** What [DriveConnectController.connect] and its relatives return. The recovery key is here once and nowhere else. */
class ConnectResult(val state: ConnectState, val recoveryKey: String? = null, val error: DriveReason? = null, val code: String? = null) {
    override fun toString() = "ConnectResult($state, error=$error, code=$code, recoveryKey=${if (recoveryKey == null) "none" else "<shown once>"})"
    override fun equals(other: Any?) =
        other is ConnectResult && other.state == state && other.recoveryKey == recoveryKey && other.error == error && other.code == code
    override fun hashCode() = 31 * (31 * (31 * state.hashCode() + (recoveryKey?.hashCode() ?: 0)) + (error?.hashCode() ?: 0)) + (code?.hashCode() ?: 0)
}

data class BackupSummary(val id: String, val createdAt: Long, val houses: Int, val bytes: Long?, val name: String)

data class BackupList(val backups: List<BackupSummary>, val missingNewer: Boolean)

/** [shrinkHoldBackupId] is set when retention held its pruning: ask the person, then [DriveConnectController.confirmShrink]. */
data class BackUpDone(val backup: BackupSummary, val shrinkHoldBackupId: String?, val missingNewer: Boolean)

/** A backup that was downloaded, opened and proven, and is now in the caller's staging sink (hand it to the import preview). */
data class ImportedBackup(val backup: BackupSummary, val format: String, val plaintextSize: Long)

sealed interface DueBackupResult {
    data class Ran(val reason: BackupSchedule.Reason) : DueBackupResult

    /** Nothing to do now: [reason] is `NOT_READY`, `DISABLED`, `NOT_DUE`, `WAIT_RETRY`, ... */
    data class NotRan(val reason: BackupSchedule.Reason) : DueBackupResult
    data class Failed(val reason: DriveReason) : DueBackupResult
}

enum class SyncState { NOT_RUN, SYNCED, WAITING, PAUSED, OFFLINE, NEEDS_CONFIRMATION, SKIPPED_FILES, ERROR }

/**
 * The status line of sync. [needsConfirmation] is the shrink guard: the pass would delete [housesToDelete] of
 * [liveHouses] houses; ask, then `syncNow(confirmShrink = true)`.
 */
data class SyncInfo(
    val state: SyncState,
    val lastSyncAt: Long?,
    val skipped: List<SkipReason> = emptyList(),
    val error: DriveReason? = null,
    val housesToDelete: Int? = null,
    val liveHouses: Int? = null,
    /** A finished pass wrote this device's file or took rows from another device's: something moved (the periodic cadence's reset). */
    val changed: Boolean = false,
    /** The [app.doorprints.drive.DriveCode] of an unexpected failure of the pass (with [error] [DriveReason.FAILED]); else null. */
    val code: String? = null,
) {
    val needsConfirmation: Boolean get() = state == SyncState.NEEDS_CONFIRMATION
}

/** One device listed in the opened folder, for the devices card. */
data class ListedDevice(val kidHex: String, val name: String, val platform: String, val self: Boolean)

/** The factor a delete asks of the person. On the phones it is never a passkey and never the recovery key. */
enum class DeleteFactor { NONE, DEVICE_AUTH }

data class DeleteConfirmInfo(val level: DeletionLevel, val factor: DeleteFactor, val tickBoxRequired: Boolean, val delayMs: Long)

/** A delete that ran: [finished] false means [left] of [total] files are still in Drive ([stopped] says why). */
data class DeleteRun(val finished: Boolean, val left: Int, val total: Int, val stopped: StopReason?)

/** The wrap the enrolled device hands to the newcomer (base64 text for the QR or the code screen). */
data class EnrolmentWrap(val wrapEnc: String, val wrapCt: String, val epoch: Int)

/** The new recovery key of a revoke: shown once by the caller, never stored. */
class RevokeDone(val recoveryKey: String) {
    override fun toString() = "RevokeDone(<shown once>)"
}
