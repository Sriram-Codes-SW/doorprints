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

package app.doorprints.drive.backup

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.CryptoException
import app.doorprints.crypto.DpxException
import app.doorprints.crypto.KeysException
import app.doorprints.crypto.OpenedKeys
import app.doorprints.crypto.RecoveryKey
import app.doorprints.drive.DriveCode
import app.doorprints.drive.DriveException

/*
 * The typed results of the Drive backups (S4b-BL-116) that the screens show (S4b-BL-117 builds them): where the
 * folder stands on this device, one backup run, one listing, one download. Web twin: `drive-backup-results.ts`.
 */

/** Why something did not happen, in the words a screen needs; the underlying kind is kept for support. */
data class DriveProblem(
    val kind: Kind,
    val driveKind: DriveException.Kind? = null,
    val keysKind: KeysException.Kind? = null,
    val controlKind: ControlException.Kind? = null,
    val dpxKind: DpxException.Kind? = null,
    /**
     * [DriveCode] of an exception nobody expected (only with [Kind.SOURCE_FAILED]): the step and the class name, never a
     * message. Null for every typed failure.
     */
    val code: String? = null,
) {
    /** The reasons a Drive backup action did not happen; [DriveProblem.of] maps every typed failure to one of them. */
    enum class Kind {
        /** No network (or a captive portal). Tried again at the next trigger. */
        OFFLINE,

        /** Google refused the grant: "Google Drive disconnected. Connect again?" (docs/15 §5.5). */
        UNAUTHORIZED,

        /** The person's Drive is full (docs/15 §5.2): one notice, nothing local is touched. */
        QUOTA_EXCEEDED,

        /** Drive asked us to slow down, and the five tries ran out. */
        RATE_LIMITED,

        /** Drive's 5xx after five tries. */
        SERVER,

        /** Any other answer Drive gave (no access, a conflict, a request it refused). */
        DRIVE,

        /** Drive's checksum is not what was written or read (`checksumMismatch`, `noChecksum`). */
        CORRUPT,

        /** `keys.json` is older than this device already accepted (Drive's *Manage versions*): nothing was changed. */
        KEYS_ROLLED_BACK,

        /** `keys.json` does not lead to the key this device trusts (a forged or forked list): "belongs to another key set". */
        KEYS_UNTRUSTED,

        /** `keys.json` is not readable (damaged, a newer format, a broken rule). */
        KEYS_UNREADABLE,

        /** The typed recovery key is not this folder's (or does not read: a wrong symbol, a check mismatch). */
        WRONG_RECOVERY_KEY,

        /** The folder has no recovery key (the person skipped it). */
        NO_RECOVERY_KEY,

        /** This device's key is in the revoked list: join again as a new device. */
        DEVICE_REVOKED,

        /** `doorprints.json` is older than this device already accepted. */
        CONTROL_ROLLED_BACK,

        /** `doorprints.json` failed its MAC or its format. */
        CONTROL_INVALID,

        /** The folder has no `keys.json` and this device did not make it: nothing to join, nothing written. */
        FOLDER_WITHOUT_KEYS,

        /** A folder exists already: connect to it (join or recovery key), never make a second key set over it. */
        FOLDER_EXISTS,

        /** A backup file failed its checks (`dpx/1` refused it, or its metadata no longer verifies). */
        BACKUP_REFUSED,

        /** The backup is no longer in the folder (deleted, in the bin, moved). */
        BACKUP_GONE,

        /** This device could not write its backup ZIP. */
        SOURCE_FAILED,

        /** The platform has no crypto provider yet (the iPhone until S4b-BL-131). */
        CRYPTO_UNAVAILABLE,
    }

    /** Waiting and trying again can help. */
    val retryable: Boolean get() = kind == Kind.OFFLINE || kind == Kind.RATE_LIMITED || kind == Kind.SERVER

    /** How the schedule treats it ([BackupSchedule.Failure]). */
    val scheduleFailure: BackupSchedule.Failure
        get() = when (kind) {
            Kind.OFFLINE, Kind.RATE_LIMITED, Kind.SERVER, Kind.DRIVE, Kind.CORRUPT, Kind.SOURCE_FAILED, Kind.BACKUP_GONE -> BackupSchedule.Failure.RETRYABLE
            Kind.QUOTA_EXCEEDED -> BackupSchedule.Failure.QUOTA
            Kind.UNAUTHORIZED -> BackupSchedule.Failure.UNAUTHORIZED
            else -> BackupSchedule.Failure.BLOCKED
        }

    companion object {
        /** True for the exceptions Doorprints throws on purpose; anything else is a surprise (and gets a [DriveCode]). */
        fun isTyped(e: Throwable): Boolean =
            e is DriveException || e is KeysException || e is ControlException || e is DpxException || e is CryptoException

        /** The [DriveCode] of [e] at [step], or null when [e] is a typed failure the screen already explains. */
        fun codeOf(e: Throwable, step: String): String? = if (isTyped(e)) null else DriveCode.of(step, e)

        /** [of] with the step the failure happened in: an unexpected exception carries its [DriveCode]. */
        fun of(e: Throwable, step: String): DriveProblem = of(e).let { if (it.kind == Kind.SOURCE_FAILED) it.copy(code = codeOf(e, step)) else it }

        fun of(e: Throwable): DriveProblem = when (e) {
            is DriveException -> DriveProblem(
                when (e.kind) {
                    DriveException.Kind.OFFLINE -> Kind.OFFLINE
                    DriveException.Kind.UNAUTHORIZED -> Kind.UNAUTHORIZED
                    DriveException.Kind.QUOTA_EXCEEDED -> Kind.QUOTA_EXCEEDED
                    DriveException.Kind.RATE_LIMITED -> Kind.RATE_LIMITED
                    DriveException.Kind.SERVER -> Kind.SERVER
                    DriveException.Kind.CORRUPT -> Kind.CORRUPT
                    else -> Kind.DRIVE
                },
                driveKind = e.kind,
            )
            is KeysException -> DriveProblem(
                when (e.kind) {
                    KeysException.Kind.ROLLED_BACK -> Kind.KEYS_ROLLED_BACK
                    KeysException.Kind.PIN_MISMATCH, KeysException.Kind.FORK_DETECTED, KeysException.Kind.MAC_INVALID,
                    KeysException.Kind.RECOVERY_ANCHOR_INVALID, KeysException.Kind.CHAIN_BROKEN, KeysException.Kind.NOT_PINNED,
                    KeysException.Kind.REVISION_JUMP,
                    -> Kind.KEYS_UNTRUSTED
                    KeysException.Kind.RECOVERY_MISMATCH -> Kind.WRONG_RECOVERY_KEY
                    KeysException.Kind.NO_RECOVERY -> Kind.NO_RECOVERY_KEY
                    KeysException.Kind.REVOKED -> Kind.DEVICE_REVOKED
                    else -> Kind.KEYS_UNREADABLE
                },
                keysKind = e.kind,
            )
            is ControlException -> DriveProblem(
                if (e.kind == ControlException.Kind.ROLLED_BACK) Kind.CONTROL_ROLLED_BACK else Kind.CONTROL_INVALID,
                controlKind = e.kind,
            )
            is DpxException -> DriveProblem(Kind.BACKUP_REFUSED, dpxKind = e.kind)
            is CryptoException -> DriveProblem(
                when {
                    e.kind == CryptoException.Kind.UNAVAILABLE -> Kind.CRYPTO_UNAVAILABLE
                    e is app.doorprints.crypto.RecoveryKeyException -> Kind.WRONG_RECOVERY_KEY
                    else -> Kind.BACKUP_REFUSED
                },
            )
            else -> DriveProblem(Kind.SOURCE_FAILED)
        }
    }
}

/**
 * Where the Doorprints folder stands for this device ([DriveBackupService.connect]). [kind] is the plain state for a
 * screen; the enrolment and recovery screens are S4b-BL-117 and S4b-BL-126's.
 */
sealed interface DriveConnection {
    val kind: Kind

    /** The plain states of the Drive folder for one device. */
    enum class Kind { NO_FOLDER, FOLDER_GONE, NEEDS_ENROLMENT, NEEDS_RECOVERY_KEY, READY, ERROR }

    /** Nothing of Doorprints in this Drive: [DriveBackupService.createFolder] makes the folder and the key set. */
    data object NoFolder : DriveConnection {
        override val kind get() = Kind.NO_FOLDER
    }

    /**
     * The folder this device used is gone or in the bin (docs/15 §3.4): never re-created silently; the screen asks
     * (*Start again* is [DriveBackupService.createFolder]).
     */
    data object FolderGone : DriveConnection {
        override val kind get() = Kind.FOLDER_GONE
    }

    /**
     * The folder has a key set this device has no pin for (a new phone, a cleared browser): "This Google Drive already
     * has Doorprints backups" (docs/15 §9.3). Nothing is written until it joins by QR code
     * ([DriveBackupService.openWithFolderKey], S4b-BL-126) or the recovery key ([DriveBackupService.openWithRecoveryKey]).
     * [recoveryAvailable] is read from the unverified list: a hint for the screen only.
     */
    data class NeedsEnrolment(val recoveryAvailable: Boolean) : DriveConnection {
        override val kind get() = Kind.NEEDS_ENROLMENT
    }

    /**
     * This device trusted the folder before, but its own key no longer opens the list ([reason]: not listed, revoked,
     * or its wrap does not open; unauthenticated, so nothing is cleaned up on it). The way back is the recovery key or
     * joining again.
     */
    data class NeedsRecoveryKey(val recoveryAvailable: Boolean, val reason: KeysException.Kind) : DriveConnection {
        override val kind get() = Kind.NEEDS_RECOVERY_KEY
    }

    /** The folder is open: backups can be listed, written and imported. */
    class Ready(val folder: ReadyFolder) : DriveConnection {
        override val kind get() = Kind.READY
    }

    /** The folder could not be read or trusted; [problem] says why. */
    data class Error(val problem: DriveProblem) : DriveConnection {
        override val kind get() = Kind.ERROR
    }
}

/** An opened folder: its ids, the trusted key list and the control file. Only the services make one. */
class ReadyFolder internal constructor(
    val rootId: String,
    val keysId: String,
    val controlId: String,
    /** Null until the first backup makes `Backups/`. */
    val backupsId: String?,
    val keys: OpenedKeys,
    val control: ControlBody,
)

/** [DriveBackupService.createFolder]'s result: [recoveryKey] is shown **once** by the caller and never stored. */
class CreateOutcome(val connection: DriveConnection, val recoveryKey: RecoveryKey?)

/** One backup that passed every check of [BackupListing]: complete, its metadata authenticated, its writer accepted. */
class DriveBackup(
    val fileId: String,
    /** Drive's name: for the person's eyes only (the app goes by the metadata, docs/15 §5.8). */
    val name: String,
    val createdAt: Long,
    val houses: Int,
    val epoch: Int,
    writerKid: ByteArray,
    /** The SHA-256 of the `dpx/1` file, lowercase hex: Drive's `sha256Checksum`, bound by the metadata's MAC. */
    val sha256: String,
    val size: Long?,
) {
    private val kid = writerKid.copyOf()
    val writerKid: ByteArray get() = kid.copyOf()
    override fun toString() = "DriveBackup($fileId, createdAt=$createdAt, houses=$houses, epoch=$epoch, kid=${Bytes.hex(kid)})"
}

/** Why a file in `Backups/` is not a backup. */
enum class IgnoredReason {
    /** No metadata, or not canonical: not written by Doorprints (or edited by hand). */
    NOT_A_BACKUP,

    /** Drive has not computed its checksum yet (asked again at the next listing). */
    NO_CHECKSUM,

    /** Under an epoch newer than the key list this device read: read `keys.json` again first. */
    NEWER_EPOCH,

    /** The metadata's MAC does not verify (planted, moved, edited, or another folder's). */
    MAC_INVALID,

    /** Written by a revoked device, or under an old epoch after a revoke, or by a writer the list does not know. */
    WRITER_REFUSED,

    /** Made before *Delete all backups* (`doorprints.json` `backupsDeletedAt`): brought back from the bin. */
    DELETED_BEFORE,

    /** Another copy of a backup already listed (the same bytes). */
    DUPLICATE,
}

/**
 * The backups of one folder ([DriveBackupService.listBackups]), newest first by the authenticated `createdAt`.
 * [unfinished] verified but never marked complete (a run stopped after its upload); [junk] partial files that do not
 * verify; [missingNewer]: the newest backup this device has seen is not here any more (deleted by hand or by someone
 * else: reported, never acted on).
 */
class BackupListing(
    val backups: List<DriveBackup>,
    val unfinished: List<DriveBackup>,
    val duplicates: List<String>,
    val junk: List<app.doorprints.drive.DriveFile>,
    val ignored: List<Pair<String, IgnoredReason>>,
    val missingNewer: Boolean,
) {
    val newest: DriveBackup? get() = backups.firstOrNull()
}

/** What retention did after a backup: trashed ids, completed unfinished uploads, and the shrink guard's hold. */
class TidyReport(val trashed: List<String>, val completed: List<String>, val hold: ShrinkHold?, val problem: DriveProblem?)

/** One backup run ([DriveBackupService.backUp]). */
sealed interface BackupOutcome {
    /** The backup is in Drive, checked; [tidy] says what retention did (its own failure does not undo the backup). */
    class Done(val backup: DriveBackup, val tidy: TidyReport, val missingNewer: Boolean) : BackupOutcome

    /** Nothing new is in Drive (a partial file may be left; it never counts, and is cleaned up later). */
    class Failed(val problem: DriveProblem) : BackupOutcome
}

/** One download for *Import a backup* > *From Google Drive* ([DriveImportService.download]). */
sealed interface ImportDownload {
    /** The decrypted ZIP is in the staging sink, every chunk and checksum proven; hand it to the import path. */
    class Verified(val backup: DriveBackup, val format: String, val plaintextSize: Long) : ImportDownload

    /** Refused; the staging sink was discarded. */
    class Refused(val problem: DriveProblem) : ImportDownload
}
