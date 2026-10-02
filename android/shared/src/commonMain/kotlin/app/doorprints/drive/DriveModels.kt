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

/*
 * The Drive access layer (S4b-BL-115, docs/15 §5, §7 phase 2): what Doorprints needs from Google Drive v3 under the
 * one scope `drive.file`, as plain values. The website's twin is `web/src/app/data/drive/drive-client.ts`; names and
 * meanings match, and `docs/schemas/drive-vectors.json` pins the wire on both.
 */

/** Drive's folder MIME type. */
const val FOLDER_MIME = "application/vnd.google-apps.folder"

/** One file or folder, with the fields Doorprints asks for ([DriveFields.FILE]). Times are epoch milliseconds. */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val parents: List<String> = emptyList(),
    val appProperties: Map<String, String> = emptyMap(),
    /** Bytes of the content; null for a folder. */
    val size: Long? = null,
    /** Drive's own SHA-256 of the content, lowercase hex; null for a folder (or while Drive has not computed it). */
    val sha256Checksum: String? = null,
    val createdTime: Long = 0,
    val modifiedTime: Long = 0,
    /** In the bin, by itself or through a folder above it. */
    val trashed: Boolean = false,
    val headRevisionId: String? = null,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME
}

/**
 * One page of a listing. [incompleteSearch] is Drive's own flag that it could not search everything; with
 * [nextPageToken] null and it false the listing is complete **as far as Drive's index knows** (see [DriveClient.list]).
 */
data class DrivePage(
    val files: List<DriveFile>,
    val nextPageToken: String? = null,
    val incompleteSearch: Boolean = false,
)

/**
 * What to list, as Drive's `q` ([toQ]). Doorprints finds its files by [appProperties] and the folder, never by name
 * (docs/15 §5.8). [trashed] false (the default) leaves out the bin, null lists both.
 */
data class DriveQuery(
    val parentId: String? = null,
    val appProperties: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val name: String? = null,
    val trashed: Boolean? = false,
) {
    /**
     * Drive's query string, in a fixed order (parent, name, MIME type, appProperties by key, trashed) so the two
     * stacks send the same text (drive-vectors.json `queries`). Literals are quoted with `'`, and `\` and `'` in them
     * escaped with a backslash, as Drive's query grammar asks.
     */
    fun toQ(): String = buildList {
        parentId?.let { add("${literal(it)} in parents") }
        name?.let { add("name = ${literal(it)}") }
        mimeType?.let { add("mimeType = ${literal(it)}") }
        for ((key, value) in appProperties.entries.sortedBy { it.key }) {
            add("appProperties has { key=${literal(key)} and value=${literal(value)} }")
        }
        trashed?.let { add("trashed = $it") }
    }.joinToString(" and ")

    companion object {
        fun literal(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }
}

/** A file or folder to create. [parents] empty is My Drive's root. */
data class NewFile(
    val name: String,
    val mimeType: String,
    val parents: List<String> = emptyList(),
    val appProperties: Map<String, String> = emptyMap(),
)

/**
 * A metadata change ([DriveClient.updateMetadata]): only what is set changes. In [appProperties] a null value removes
 * that key, as Drive's PATCH does; keys not named stay.
 */
data class MetadataChange(
    val name: String? = null,
    val appProperties: Map<String, String?> = emptyMap(),
    val addParents: List<String> = emptyList(),
    val removeParents: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = name == null && appProperties.isEmpty() && addParents.isEmpty() && removeParents.isEmpty()
}

/** `about.user` and `about.storageQuota` (docs/15 §2.2: the account shown in Settings comes from here). */
data class DriveAbout(
    val email: String,
    val displayName: String? = null,
    /** The account's limit in bytes; null when Drive reports none (unlimited). */
    val quotaLimit: Long? = null,
    val quotaUsage: Long = 0,
    val quotaUsageInDrive: Long = 0,
)

/** One revision of a file's content (`revisions.list`), oldest first; S4b-BL-125's rollback checks read these. */
data class DriveRevision(
    val id: String,
    val modifiedTime: Long,
    val size: Long? = null,
)

/**
 * A resumable upload session (Drive's session URI): a capability in itself, so it is never logged or shown, and kept
 * only as long as the upload (Drive lets it live a week). [fileId] is set when the session writes new content to an
 * existing file. Plain values, so S4b-BL-128 can keep one across a network change and resume it.
 */
data class UploadSession(val uri: String, val size: Long, val fileId: String? = null) {
    override fun toString(): String = "UploadSession(size=$size, fileId=$fileId)"
}

/** Where a resumable upload stands. */
sealed interface UploadProgress {
    /** Drive has the first [received] bytes; the next chunk starts there. */
    data class Incomplete(val received: Long) : UploadProgress

    /** The last byte arrived and Drive made (or updated) [file]. */
    data class Complete(val file: DriveFile) : UploadProgress
}

/** The outcome of [deleteAll]: what went, and what is still in Drive when it stopped (docs/15 §3.3, "12 of 52"). */
data class DeleteReport(
    val deleted: List<String>,
    val left: List<String>,
    val error: DriveException? = null,
)

/** The folders of docs/15 §5.1, found by their `doorprints` app property. */
data class FolderSpec(val name: String, val appProperties: Map<String, String>)

/** The layout and the app-property keys of docs/15 §5.1 (Drive allows 124 bytes per key and value together). */
object DriveLayout {
    /** The folder's role: `root`, `backups`, `sync`, `photos`, `shared`. */
    const val ROLE = "doorprints"

    /** A file's kind: `backup`, `sync`, `photo`, `control`, `keys`. */
    const val KIND = "kind"

    /** The writing device's random id (sync files, backups). */
    const val DEVICE = "device"

    /** `partial` while written, `complete` after the checksum check (docs/15 §1.4 item 2). */
    const val STATE = "state"

    /** When the writer made it, epoch milliseconds as text (retention goes by this, not by Drive's times). */
    const val CREATED_AT = "createdAt"

    const val STATE_PARTIAL = "partial"
    const val STATE_COMPLETE = "complete"

    val ROOT = FolderSpec("Doorprints", mapOf(ROLE to "root"))
    val BACKUPS = FolderSpec("Backups", mapOf(ROLE to "backups"))
    val SYNC = FolderSpec("Sync", mapOf(ROLE to "sync"))
    val PHOTOS = FolderSpec("Photos", mapOf(ROLE to "photos"))

    /** Drive's limit on one app property, key and value together, in UTF-8 bytes. */
    const val MAX_PROPERTY_BYTES = 124
}

/** The `fields` Doorprints asks Drive for (smaller answers, and the same on both stacks). */
object DriveFields {
    const val FILE = "id,name,mimeType,parents,appProperties,size,sha256Checksum,createdTime,modifiedTime,trashed,headRevisionId"
    const val LIST = "nextPageToken,incompleteSearch,files($FILE)"
    const val ABOUT = "user(emailAddress,displayName),storageQuota(limit,usage,usageInDrive)"
    const val REVISIONS = "nextPageToken,revisions(id,modifiedTime,size)"
}
