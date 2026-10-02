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

import app.doorprints.shared.export.Sha256
import kotlin.math.min

/*
 * What docs/15 builds from the [DriveClient] calls, written once over the interface so the HTTP client and the fake
 * behave the same (web: `drive-ops.ts`, the same names).
 */

/** Every page of [query] (at most [maxPages], a guard against a token that never ends). */
suspend fun DriveClient.listAll(query: DriveQuery, pageSize: Int = 100, maxPages: Int = 1000): List<DriveFile> {
    val out = mutableListOf<DriveFile>()
    var token: String? = null
    repeat(maxPages) {
        val page = list(query, token, pageSize)
        out += page.files
        token = page.nextPageToken ?: return out
    }
    throw DriveException(DriveException.Kind.CORRUPT, reason = "tooManyPages")
}

/**
 * The folder of [spec] under [parentId] (null: anywhere, for the root), found by its app properties (docs/15 §5.8,
 * not by name), the oldest when there are several (two devices that created it at once). [knownId], the id this device
 * stored, is read first, because a listing may not show a folder made a moment ago.
 *
 * **Never re-created silently** (docs/15 §3.4): with [create] false a folder that is gone (deleted, or in the bin)
 * gives null, and the caller asks the person; only [create] true makes one.
 */
suspend fun DriveClient.ensureFolder(
    spec: FolderSpec,
    parentId: String?,
    create: Boolean,
    knownId: String? = null,
): DriveFile? {
    if (knownId != null) {
        val known = try {
            getFile(knownId)
        } catch (e: DriveException) {
            if (e.kind != DriveException.Kind.NOT_FOUND) throw e
            null
        }
        if (known != null && known.isFolder && !known.trashed &&
            spec.appProperties.all { (k, v) -> known.appProperties[k] == v }
        ) {
            return known
        }
    }
    val found = list(DriveQuery(parentId = parentId, appProperties = spec.appProperties, mimeType = FOLDER_MIME))
        .files.firstOrNull()
    if (found != null || !create) return found
    return createFile(NewFile(spec.name, FOLDER_MIME, listOfNotNull(parentId), spec.appProperties))
}

/**
 * Uploads [size] bytes read by [read] (offset, length) in a resumable session (docs/15 §5.2, §11): chunks of
 * [chunkSize] (a multiple of [DriveClient.CHUNK_UNIT]); after a dropped connection, a 5xx or a rate limit it waits by
 * the client's [DriveClient.retry], asks Drive how far it got ([DriveClient.uploadStatus]) and goes on from that byte;
 * a session Drive forgot (404) starts again once. [session] resumes a kept one; [onSession] hands a new one out to be
 * kept (S4b-BL-128); [shouldStop] is asked between chunks ([DriveException.Kind.CANCELLED]).
 */
suspend fun DriveClient.uploadResumable(
    target: UploadTarget,
    size: Long,
    read: suspend (offset: Long, length: Int) -> ByteArray,
    chunkSize: Int = DriveClient.DEFAULT_CHUNK,
    session: UploadSession? = null,
    onSession: (UploadSession) -> Unit = {},
    onProgress: (sent: Long) -> Unit = {},
    shouldStop: () -> Boolean = { false },
): DriveFile {
    require(size > 0) { "a resumable upload needs content" }
    require(chunkSize > 0 && chunkSize % DriveClient.CHUNK_UNIT == 0) { "chunks are multiples of 256 KiB" }
    var current = session ?: startUpload(target, size).also(onSession)
    var offset = 0L
    var needStatus = session != null
    var restarted = false
    var attempt = 0
    while (true) {
        if (shouldStop()) throw DriveException(DriveException.Kind.CANCELLED, reason = "stopped")
        val progress = try {
            if (needStatus) {
                uploadStatus(current)
            } else {
                val length = min(chunkSize.toLong(), size - offset).toInt()
                uploadChunk(current, offset, read(offset, length))
            }
        } catch (e: DriveException) {
            if (e.kind == DriveException.Kind.NOT_FOUND && !restarted) {
                restarted = true
                current = startUpload(target, size).also(onSession)
                offset = 0
                needStatus = false
                continue
            }
            attempt++
            retry.pause(attempt, e)
            needStatus = true
            continue
        }
        needStatus = false
        when (progress) {
            is UploadProgress.Complete -> {
                onProgress(size)
                return progress.file
            }
            is UploadProgress.Incomplete -> {
                if (progress.received > offset) attempt = 0
                offset = progress.received
                onProgress(offset)
            }
        }
    }
}

/** [content] by multipart when it is small enough ([DriveClient.MULTIPART_LIMIT]), else resumable. */
suspend fun DriveClient.uploadBytes(
    target: UploadTarget,
    content: ByteArray,
    chunkSize: Int = DriveClient.DEFAULT_CHUNK,
): DriveFile =
    if (content.size <= DriveClient.MULTIPART_LIMIT) {
        upload(target, content)
    } else {
        uploadResumable(target, content.size.toLong(), { offset, length ->
            content.copyOfRange(offset.toInt(), offset.toInt() + length)
        }, chunkSize)
    }

/**
 * Drive's own `sha256Checksum` of [fileId] is [expectedSha256] (lowercase hex), docs/15 §1.4 item 3. A checksum Drive
 * has not computed yet is asked again by the backoff, up to the retry's tries; missing or different, it is
 * [DriveException.Kind.CORRUPT].
 */
suspend fun DriveClient.confirmChecksum(fileId: String, expectedSha256: String, uploaded: DriveFile? = null): DriveFile {
    var file = uploaded?.takeIf { it.sha256Checksum != null } ?: getFile(fileId)
    var attempt = 1
    while (file.sha256Checksum == null && attempt < retry.maxAttempts) {
        retry.backoff(attempt++)
        file = getFile(fileId)
    }
    val drive = file.sha256Checksum
        ?: throw DriveException(DriveException.Kind.CORRUPT, reason = "noChecksum")
    if (!drive.equals(expectedSha256, ignoreCase = true)) {
        throw DriveException(DriveException.Kind.CORRUPT, reason = "checksumMismatch")
    }
    return file
}

/**
 * The end of a backup upload (docs/15 §1.4 item 2): the checksum checked ([confirmChecksum]), then one
 * `files.update` setting `state=complete` and, when given, the real name instead of `partial-…`.
 */
suspend fun DriveClient.markComplete(
    fileId: String,
    expectedSha256: String,
    finalName: String? = null,
    uploaded: DriveFile? = null,
): DriveFile {
    confirmChecksum(fileId, expectedSha256, uploaded)
    return updateMetadata(fileId, MetadataChange(name = finalName, appProperties = mapOf(DriveLayout.STATE to DriveLayout.STATE_COMPLETE)))
}

/**
 * The content of [fileId], checked against Drive's `sha256Checksum` and, when given, [expectedSha256]
 * ([DriveException.Kind.CORRUPT] otherwise): every Drive file is untrusted input (docs/schemas §6).
 */
suspend fun DriveClient.downloadVerified(
    fileId: String,
    expectedSha256: String? = null,
    sha256: () -> Sha256 = ::driveSha256,
): ByteArray {
    val file = getFile(fileId)
    val bytes = download(fileId)
    val actual = sha256HexOf(bytes, sha256)
    val drive = file.sha256Checksum
    if ((drive != null && !drive.equals(actual, ignoreCase = true)) ||
        (expectedSha256 != null && !expectedSha256.equals(actual, ignoreCase = true))
    ) {
        throw DriveException(DriveException.Kind.CORRUPT, reason = "checksumMismatch")
    }
    return bytes
}

/** The content of [fileId] of [size] bytes in ranges of [chunkSize], handed to [sink] in order (a large photo). */
suspend fun DriveClient.downloadTo(
    fileId: String,
    size: Long,
    chunkSize: Int = DriveClient.DEFAULT_CHUNK,
    sink: suspend (ByteArray) -> Unit,
) {
    var offset = 0L
    while (offset < size) {
        val end = min(size, offset + chunkSize) - 1
        val bytes = download(fileId, offset..end)
        if (bytes.isEmpty()) throw DriveException(DriveException.Kind.CORRUPT, reason = "shortRange")
        sink(bytes)
        offset += bytes.size
    }
}

/**
 * Deletes [fileIds] for good, one by one in order (docs/15 §3.3: children before their folder, each a [DriveClient.delete]
 * with its retries; a 404 counts as deleted). Stops at the first failure and reports what is left, for "12 of 52 files
 * are still in your Drive. Try again".
 */
suspend fun DriveClient.deleteAll(fileIds: List<String>): DeleteReport {
    val deleted = mutableListOf<String>()
    for ((index, id) in fileIds.withIndex()) {
        try {
            delete(id)
        } catch (e: DriveException) {
            return DeleteReport(deleted, fileIds.subList(index, fileIds.size).toList(), e)
        }
        deleted += id
    }
    return DeleteReport(deleted, emptyList())
}
