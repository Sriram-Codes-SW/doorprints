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

import app.doorprints.shared.api.IsoTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * Drive v3's JSON as it is on the wire (sizes and quota numbers are strings, times RFC 3339), and the bodies
 * Doorprints sends. Shared by HttpDriveClient and the tests' HTTP face of the fake (FakeDriveHttp), so both read and
 * write the same shapes.
 */

internal val driveJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * Drive's `File` resource as JSON: every field optional, because a `fields` mask leaves some out.
 * Read it with [toModel]; build the Drive-shaped answer for tests with [of].
 */
@Serializable
internal data class FileWire(
    val id: String? = null,
    val name: String? = null,
    val mimeType: String? = null,
    val parents: List<String>? = null,
    val appProperties: Map<String, String>? = null,
    val size: String? = null,
    val sha256Checksum: String? = null,
    val createdTime: String? = null,
    val modifiedTime: String? = null,
    val trashed: Boolean? = null,
    val headRevisionId: String? = null,
) {
    /** The model; an answer without an id, or with a size or time that is not one, is [DriveException.Kind.CORRUPT]. */
    fun toModel(): DriveFile {
        val fileId = id ?: throw corrupt()
        return DriveFile(
            id = fileId,
            name = name.orEmpty(),
            mimeType = mimeType ?: "application/octet-stream",
            parents = parents.orEmpty(),
            appProperties = appProperties.orEmpty(),
            size = size?.let { it.toLongOrNull() ?: throw corrupt() },
            sha256Checksum = sha256Checksum?.lowercase(),
            createdTime = time(createdTime),
            modifiedTime = time(modifiedTime),
            trashed = trashed ?: false,
            headRevisionId = headRevisionId,
        )
    }

    companion object {
        fun of(file: DriveFile) = FileWire(
            id = file.id,
            name = file.name,
            mimeType = file.mimeType,
            parents = file.parents,
            appProperties = file.appProperties,
            size = file.size?.toString(),
            sha256Checksum = file.sha256Checksum,
            createdTime = IsoTime.format(file.createdTime),
            modifiedTime = IsoTime.format(file.modifiedTime),
            trashed = file.trashed,
            headRevisionId = file.headRevisionId,
        )
    }
}

/** The answer of `files.list`: one page of [FileWire]s and the token for the next page. */
@Serializable
internal data class ListWire(
    val files: List<FileWire> = emptyList(),
    val nextPageToken: String? = null,
    val incompleteSearch: Boolean = false,
)

@Serializable
internal data class UserWire(val emailAddress: String? = null, val displayName: String? = null)

@Serializable
internal data class QuotaWire(val limit: String? = null, val usage: String? = null, val usageInDrive: String? = null)

/**
 * The answer of `about.get`. [toModel] needs the account's e-mail address (else the answer is corrupt) and reads the
 * quota numbers, which Drive sends as strings.
 */
@Serializable
internal data class AboutWire(val user: UserWire? = null, val storageQuota: QuotaWire? = null) {
    fun toModel(): DriveAbout {
        fun num(text: String?): Long? = text?.let { it.toLongOrNull() ?: throw corrupt() }
        return DriveAbout(
            email = user?.emailAddress ?: throw corrupt(),
            displayName = user.displayName,
            quotaLimit = num(storageQuota?.limit),
            quotaUsage = num(storageQuota?.usage) ?: 0,
            quotaUsageInDrive = num(storageQuota?.usageInDrive) ?: 0,
        )
    }
}

/** One entry of `revisions.list`; [toModel] fails with CORRUPT when the id, size or time is malformed. */
@Serializable
internal data class RevisionWire(val id: String? = null, val modifiedTime: String? = null, val size: String? = null) {
    fun toModel() = DriveRevision(
        id = id ?: throw corrupt(),
        modifiedTime = time(modifiedTime),
        size = size?.let { it.toLongOrNull() ?: throw corrupt() },
    )
}

@Serializable
internal data class RevisionsWire(val revisions: List<RevisionWire> = emptyList(), val nextPageToken: String? = null)

/**
 * A [DriveException.Kind.CORRUPT] for an answer that is not what Drive sends; [reason] names the check, never content.
 */
internal fun corrupt(reason: String = "badAnswer") = DriveException(DriveException.Kind.CORRUPT, reason = reason)

private fun time(text: String?): Long =
    text?.let { runCatching { IsoTime.parseMillis(it) }.getOrElse { throw corrupt() } } ?: 0

/** The JSON body of a create: name, MIME type, and the parents and app properties when there are any. */
internal fun newFileJson(file: NewFile): JsonObject = buildJsonObject {
    put("name", file.name)
    put("mimeType", file.mimeType)
    if (file.parents.isNotEmpty()) putJsonArray("parents") { file.parents.forEach { add(JsonPrimitive(it)) } }
    if (file.appProperties.isNotEmpty()) {
        putJsonObject("appProperties") { file.appProperties.entries.sortedBy { it.key }.forEach { (k, v) -> put(k, v) } }
    }
}

/** The JSON body of an update: only what changes; a removed app property is `null`. Parents go in the query. */
internal fun changeJson(change: MetadataChange): JsonObject = buildJsonObject {
    change.name?.let { put("name", it) }
    if (change.appProperties.isNotEmpty()) {
        putJsonObject("appProperties") {
            change.appProperties.entries.sortedBy { it.key }.forEach { (k, v) -> put(k, if (v == null) JsonNull else JsonPrimitive(v)) }
        }
    }
}
