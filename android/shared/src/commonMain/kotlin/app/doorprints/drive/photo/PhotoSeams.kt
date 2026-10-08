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

package app.doorprints.drive.photo

/*
 * What the photos over Drive (S4b-BL-128, docs/15 §5.1, §11) keep and report, as plain values and two small interfaces.
 * Web twin: `drive-photo-seams.ts`, the same names.
 */

/** `appProperties kind` of a photo file (docs/15 §5.1; `DeletionRules` classes it as ours only inside the `photos` folder). */
const val KIND_PHOTO = "photo"

/** `appProperties photoId`: the photo's random id and nothing else about it (no house, no name, no date): a lookup hint only. */
const val PROP_PHOTO_ID = "photoId"

/**
 * Where a photo's bytes are in Drive: the file ([driveFileId]) and the SHA-256 of the **plaintext** ([sha256], lowercase
 * hex), which is what `dpx/1` needs to open a `photo/1` file (`CHECKSUM_REQUIRED`). The pair travels in the photo's row of
 * the authenticated `sync/1` file.
 */
data class PhotoRef(val driveFileId: String, val sha256: String)

/** A photo this device could not read, remembered so every pass does not try again (until [PhotoConfig.badRetryMs] passed). */
data class PhotoBad(val fileId: String, val reason: PhotoSkipReason, val at: Long)

/** The device's own photo bookkeeping, sealed on the device by the platform; never in a backup. */
data class PhotoState(
    val photosFolderId: String? = null,
    /** Photo id to its file: uploaded here, or learned from an authenticated sync file of another device. */
    val refs: Map<String, PhotoRef> = emptyMap(),
    val bad: Map<String, PhotoBad> = emptyMap(),
)

/** Where [PhotoState] lives. One writer at a time (the sync's single worker or tab lock). */
interface PhotoStateStore {
    /** The saved state, or an empty one on first use. */
    suspend fun load(): PhotoState
    /** Replaces the saved state with [state]. */
    suspend fun save(state: PhotoState)
}

/** The part of [DrivePhotos] the sync engine uses: what it writes into the photo rows, and what it learns from other files. */
interface PhotoRefs {
    /** The references learned or made so far, by photo id. */
    suspend fun refs(): Map<String, PhotoRef>

    /** Refs read from authenticated rows of other devices' files; they fill gaps and replace only a ref known to be dead. */
    suspend fun learn(found: Map<String, PhotoRef>)
}

/** Why one photo was skipped; every skip is reported, none aborts the sync, none deletes anything. */
enum class PhotoSkipReason {
    /** Bigger than [PhotoConfig.maxPlaintextBytes] (before or after reading); stays on this device only. */
    TOO_LARGE,

    /** Nothing to upload. */
    EMPTY,

    /** An id the app would not use as a file name. */
    BAD_ID,

    /** The row carries no Drive file yet: the other device has not uploaded it (Wi-Fi). Not an error; tried again. */
    NO_REFERENCE,

    /** The `Photos` folder is not in the Drive folder. */
    NO_FOLDER,

    /** The referenced file is gone or not readable by this app. */
    UNREADABLE,

    /** The referenced file is in the bin, not complete, not `kind=photo`, or outside the verified `Photos` folder. */
    NOT_OURS,

    /** Not a `dpx/1` file: a plain file planted in the folder. */
    NOT_ENCRYPTED,

    /** A `dpx/1` file that does not authenticate, or of another kind. */
    BAD_ENVELOPE,

    /** Authentic but not the photo its row names (swapped), or altered after the row was written. */
    CHECKSUM_MISMATCH,

    /** Its bytes are not the SHA-256 Drive reports. */
    DOWNLOAD_CORRUPT,

    /** Written by a revoked device after its revoke, or under an epoch it never had. */
    REVOKED_WRITER,
    OLD_EPOCH_AFTER_REVOKE,
    UNKNOWN_WRITER,

    /** Written under an epoch this device's key list does not have (yet). */
    NEWER_EPOCH,

    /** A newer `dpx` or `photo` version than this app reads. */
    UNSUPPORTED_VERSION,
}

/** A skipped photo. [detail] is a code (never file content). */
data class SkippedPhoto(val photoId: String, val reason: PhotoSkipReason, val detail: String? = null)

/** The outcome of one upload. */
sealed interface PhotoUploadResult {
    /** A new file was written, checked against Drive's checksum, and completed. */
    data class Uploaded(val ref: PhotoRef) : PhotoUploadResult

    /** This photo was already in Drive (same plaintext SHA-256, file intact): nothing was sent. */
    data class Unchanged(val ref: PhotoRef) : PhotoUploadResult

    /** A file of this photo found in Drive (another device, or an earlier try) was verified and adopted: nothing was sent. */
    data class Adopted(val ref: PhotoRef) : PhotoUploadResult

    /** Not uploaded, for good ([PhotoSkipReason]): the photo stays on this device. */
    data class Skipped(val reason: PhotoSkipReason) : PhotoUploadResult
}

/** Limits and timings; the tests make them small. */
class PhotoConfig(
    /** The largest photo, in bytes (docs/15 §5.2: photos are about 0.2 to 2 MB after the app's resize). */
    val maxPlaintextBytes: Int = 32 * 1024 * 1024,
    /** Resumable upload chunk (a multiple of 256 KiB). */
    val chunkSize: Int = app.doorprints.drive.DriveClient.DEFAULT_CHUNK,
    /** Files examined per photo when looking for one that is already in Drive. */
    val maxCandidates: Int = 4,
    /** How long a photo that failed a check is left alone. */
    val badRetryMs: Long = 24L * 60 * 60 * 1000,
    /** How long the opened key list is trusted before `keys.json` is read again. */
    val keysFreshMs: Long = 2L * 60 * 1000,
) {
    /** The largest file: the photo plus the `dpx/1` header and one tag per 64 KiB chunk. */
    val maxFileBytes: Long get() = maxPlaintextBytes.toLong() + maxPlaintextBytes / 4096 + 16 * 1024
}
