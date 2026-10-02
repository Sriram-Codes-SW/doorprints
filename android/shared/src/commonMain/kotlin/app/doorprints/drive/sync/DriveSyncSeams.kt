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

package app.doorprints.drive.sync

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.KeysGuard
import app.doorprints.crypto.OpenedKeys
import app.doorprints.shared.api.PhotoChangeDto
import app.doorprints.shared.sync.SyncFiles
import app.doorprints.shared.sync.SyncRow

/*
 * What the Drive sync (S4b-BL-118, docs/15 §5.1, §7 phase 5) needs from the rest of the app, as small interfaces, so the
 * engine is common code and the tests run on fakes. Web twin: `drive-sync-seams.ts`.
 */

/** The inner format of a sync file inside `dpx/1` (the plaintext's own format is [SyncFiles.FORMAT]). */
const val SYNC_INNER = "sync/1"

/** `appProperties kind` of a sync file (docs/15 §5.1). */
const val KIND_SYNC = "sync"

/** The id a device writes sync files under: the hex of its key id, so the authenticated `kid` of a file names it. */
object SyncDeviceIds {
    fun of(kid: ByteArray): String = Bytes.hex(kid)
}

/**
 * An opened Drive folder, handed in by the connect ticket (S4b-BL-117/-126): the folder's id, this device's key id, the
 * list of keys opened **through the device's pin** ([guard]; nothing in Drive is read before a pin exists), and
 * [reopen], which reads `keys.json` again and opens it through the same guard (so a revoke or a new epoch is seen at
 * the start of every pass). No keys error of [reopen] triggers anything here: it ends the pass and is reported.
 */
class FolderSession(
    val rootId: String,
    val deviceKid: ByteArray,
    keys: OpenedKeys,
    val guard: KeysGuard,
    private val reopen: (suspend () -> OpenedKeys)? = null,
) {
    var keys: OpenedKeys = keys
        private set

    /** This device's id in sync files and `appProperties device`. */
    val deviceId: String = SyncDeviceIds.of(deviceKid)

    suspend fun refresh(): OpenedKeys {
        reopen?.let { keys = it() }
        return keys
    }
}

/**
 * This device's rows, read for one pass: **every** house, visit, record and photo row it holds, tombstones included,
 * as [SyncRow]s whose `by` is the device that made that version (this device's [FolderSession.deviceId] for a row
 * whose writer the store does not keep yet). The platform builds them from its stores ([SyncRows]).
 */
interface LocalRows {
    suspend fun all(): List<SyncRow>

    /** One photo row (live or not) by id, for the tombstone of a photo deleted here and for a metadata push. */
    suspend fun photo(photoId: String): PhotoChangeDto?
}

/** What a device remembers of another device's sync file. */
data class SyncPeer(
    /** The highest authenticated `seq` accepted from it: a lower one is a rollback. */
    val highestSeq: Long = 0,
    /** SHA-256 (hex) of the last file of this device that was fully merged here (nothing left to apply, hold or confirm). */
    val mergedChecksum: String? = null,
)

/** The device's own Drive-sync bookkeeping, sealed on the device by the platform; never in a backup. */
data class DriveSyncState(
    val syncFolderId: String? = null,
    /** The highest `seq` this device has used (reserved before an upload, so a retry never reuses one). */
    val lastSeq: Long = 0,
    /** The `seq` of the last file confirmed by read-back. */
    val confirmedSeq: Long = 0,
    val lastFileId: String? = null,
    val lastChecksum: String? = null,
    /** SHA-256 of the rows of the last confirmed file: unchanged rows are not written again. */
    val lastRowsHash: String? = null,
    /** Counts the passes that handed rows to the loop: their `syncVersion`. */
    val generation: Long = 0,
    val peers: Map<String, SyncPeer> = emptyMap(),
    val failures: Int = 0,
    /** No pass before this time (epoch ms) unless forced: the backoff after failures. */
    val notBefore: Long = 0,
)

/** Where [DriveSyncState] lives. One writer at a time (one tab, one worker). */
interface SyncStateStore {
    suspend fun load(): DriveSyncState
    suspend fun save(state: DriveSyncState)
}

/** Why a file of another device was not used; every skip is reported, none aborts the pass, none deletes anything. */
enum class SkipReason {
    /** Its `device` is not a device in the authenticated key list (nor a revoked one). */
    UNLISTED_DEVICE,

    /** Written by a revoked device after its revoke, or under an epoch it never had. */
    REVOKED_WRITER,

    /** Written under an epoch older than a revoke, after that revoke. */
    OLD_EPOCH_AFTER_REVOKE,

    /** The authenticated writer is neither listed nor revoked. */
    UNKNOWN_WRITER,

    /** Written under an epoch this device's key list does not have (yet). */
    NEWER_EPOCH,

    /** Not a `dpx/1` file: a plain file planted in the folder (the downgrade case). */
    NOT_ENCRYPTED,

    /** A `dpx/1` file that does not authenticate or is of another kind. */
    BAD_ENVELOPE,

    /** The writer or `deviceId` is not the device whose slot the file is in. */
    WRONG_DEVICE,

    /** Authentic but its content is not a valid `sync/1` file. */
    BAD_FILE,

    /** A newer `sync/<n>` than this app reads. */
    UNSUPPORTED_VERSION,

    TOO_LARGE,

    /** Its bytes are not the SHA-256 Drive reports. */
    DOWNLOAD_CORRUPT,

    /** Drive refused this one file (gone, no access). */
    UNREADABLE,

    /** A lower `seq` than one already accepted from that device: an older Drive revision put back. */
    ROLLED_BACK,

    /** More candidate files for one device than are read in one pass. */
    TOO_MANY_FILES,
}

/** A skipped file. [detail] is a code (never file content). */
data class SkippedFile(val deviceId: String?, val fileId: String?, val reason: SkipReason, val detail: String? = null)

/** What a pass did. */
class SyncReport(
    /** The rows to apply here, at most one per key: the greatest version offered by the other devices' files. */
    val take: List<SyncRow>,
    /** Rows stamped too far ahead of this clock: kept for a later pass. */
    val held: Int,
    /** House deletions waiting for the person's confirmation. */
    val deferred: Int,
    val skipped: List<SkippedFile>,
    /** True when this pass wrote (and confirmed by read-back) a new file of this device. */
    val wrote: Boolean,
    val seq: Long?,
    val peersRead: Int,
    /** The `syncVersion` of [take]. */
    val generation: Long,
)

sealed interface SyncPassResult {
    val report: SyncReport?

    /** The pass finished: this device's file is complete and confirmed (or needed no change). */
    data class Done(override val report: SyncReport) : SyncPassResult

    /**
     * The shrink guard held [housesToDelete] house deletions of [fromDevices] back ("This would delete N of your M
     * houses"): everything else was merged. Run again with `confirmShrink = true` after the person's yes.
     */
    data class NeedsConfirmation(
        override val report: SyncReport,
        val housesToDelete: Int,
        val liveHouses: Int,
        val fromDevices: List<String>,
    ) : SyncPassResult

    /**
     * Not now: a deletion is pending or the folder was deleted (the caller's [DriveSyncEngine] `paused` check, docs/15
     * §3.3, §3.4: sync must not start while a deletion is pending). Nothing was asked of Drive.
     */
    data object Paused : SyncPassResult {
        override val report: SyncReport? get() = null
    }

    /** Backing off after failures: nothing was asked of Drive. */
    data class Waiting(val notBefore: Long) : SyncPassResult {
        override val report: SyncReport? get() = null
    }
}

/** The part of Drive sync that is not built yet (photos: S4b-BL-128). Typed, so the loop and the screens can tell. */
class DriveSyncNotYet(val feature: String, val ticket: String) : Exception("Drive sync: $feature is not built yet ($ticket)")
