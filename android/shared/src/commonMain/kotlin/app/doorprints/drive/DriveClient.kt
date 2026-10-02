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

package app.doorprints.drive

/** What an upload writes: a new file, or new content (and metadata) for an existing one. */
sealed interface UploadTarget {
    val mimeType: String

    data class New(val file: NewFile) : UploadTarget {
        override val mimeType: String get() = file.mimeType
    }

    data class Existing(
        val fileId: String,
        override val mimeType: String,
        val change: MetadataChange = MetadataChange(),
    ) : UploadTarget
}

/**
 * Google Drive v3 as Doorprints uses it (S4b-BL-115; docs/15 §1.4, §3, §5; web: `DriveClient` in
 * `data/drive/drive-client.ts`), under the one scope `drive.file`, so it sees only the files it made or was handed.
 * Two implementations: [HttpDriveClient] (Ktor) and `InMemoryFakeDrive` (commonTest; the test double every later Drive
 * ticket's tests use); `DriveClientContract` runs the same cases against both.
 *
 * The contract:
 * - **Each call is one Drive request**, made with a token from the [TokenProvider] (a 401 asks it once more, then
 *   [DriveException.Kind.UNAUTHORIZED]) and retried by [retry] when it failed for a reason that can pass (429, 403
 *   rate limits, 5xx, no connection), except [uploadChunk] and [uploadStatus], whose recovery is
 *   [uploadResumable]'s. A retried create can leave two files if the first answer was lost on the way (Drive has no
 *   idempotent create): callers find files by `appProperties` and take the oldest ([ensureFolder]).
 * - **Listing is eventually consistent; [getFile] is not.** A file created a moment ago may be missing from [list]
 *   (Drive's index lags; the fake's `listingLagMs`), so absence in one listing right after a write proves
 *   nothing: a device keeps the ids it wrote and asks [getFile]. Pages come in `createdTime` order, oldest first.
 * - **Failures are [DriveException]s**; a coroutine's cancellation passes through untouched.
 * - **Nothing is logged**: no token, session URI, file name or content leaves this layer.
 */
interface DriveClient {
    /** The retry rule this client uses ([uploadResumable] waits by it too). */
    val retry: DriveRetry

    /** `about.user` and `about.storageQuota`. */
    suspend fun about(): DriveAbout

    /** One page of the files matching [query] (`files.list`, at most [pageSize], Drive's maximum is 1000). */
    suspend fun list(query: DriveQuery, pageToken: String? = null, pageSize: Int = 100): DrivePage

    /** One file's metadata by id, read-after-write consistent; a file in the bin comes back with `trashed`. */
    suspend fun getFile(fileId: String): DriveFile

    /** A file without content: a folder ([FOLDER_MIME]) or an empty file (`files.create`, metadata only). */
    suspend fun createFile(file: NewFile): DriveFile

    /** A small upload in one request (`uploadType=multipart`), at most [MULTIPART_LIMIT] bytes. */
    suspend fun upload(target: UploadTarget, content: ByteArray): DriveFile

    /** Opens a resumable upload of [size] bytes (`uploadType=resumable`); Drive answers with the session URI. */
    suspend fun startUpload(target: UploadTarget, size: Long): UploadSession

    /**
     * Sends [bytes] at [offset] (`Content-Range`); every chunk but the last a multiple of [CHUNK_UNIT]. Drive answers
     * 308 with what it has ([UploadProgress.Incomplete]) or, after the last byte, the file. One try.
     */
    suspend fun uploadChunk(session: UploadSession, offset: Long, bytes: ByteArray): UploadProgress

    /** Asks Drive how much of the session arrived (`Content-Range: bytes * /size`), after a dropped connection. */
    suspend fun uploadStatus(session: UploadSession): UploadProgress

    /** Changes the name, `appProperties` or parents (`files.update`); the final `state=complete` goes this way. */
    suspend fun updateMetadata(fileId: String, change: MetadataChange): DriveFile

    /** The content (`alt=media`), all of it or the inclusive byte [range] (206). */
    suspend fun download(fileId: String, range: LongRange? = null): ByteArray

    /**
     * Deletes for good (`files.delete`: not the bin; a folder takes everything in it), docs/15 §3.3. A file that is
     * already gone (404) counts as deleted, so two devices pruning at once, or a retry, both succeed.
     */
    suspend fun delete(fileId: String)

    /** Moves to Drive's bin (kept 30 days): automatic pruning only (docs/15 §1.4 item 5). */
    suspend fun trash(fileId: String): DriveFile

    /** The content's revisions, oldest first (`revisions.list`). */
    suspend fun revisions(fileId: String): List<DriveRevision>

    companion object {
        /** docs/15 §5.2: multipart up to 5 MB, resumable above. */
        const val MULTIPART_LIMIT: Int = 5 * 1024 * 1024

        /** Drive's resumable chunk unit: every chunk but the last is a multiple of 256 KiB. */
        const val CHUNK_UNIT: Int = 256 * 1024

        /** The default chunk (4 units): a photo of 0.2 to 2 MB goes in one or two. */
        const val DEFAULT_CHUNK: Int = 4 * CHUNK_UNIT
    }
}
