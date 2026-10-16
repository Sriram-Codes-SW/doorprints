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

package app.doorprints.drive.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import app.doorprints.drive.sha256HexOf

/** The format version every state file carries as `"v"`; a file of another version reads as absent instead of being misread. */
internal const val STATE_VERSION = 1

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Reads [file] as [D]: null when absent, corrupt (not JSON, truncated, wrong shape), or of another version
 * ([versionOf] is not [STATE_VERSION]). [convert] may reject a decoded value (a bad hex string, an unknown enum) by
 * returning null or throwing; that too is "absent". Never throws.
 */
internal fun <D, T : Any> readState(file: StateFile, serializer: KSerializer<D>, versionOf: (D) -> Int, convert: (D) -> T?): T? {
    val text = file.readText() ?: return null
    return try {
        val dto = json.decodeFromString(serializer, text)
        if (versionOf(dto) != STATE_VERSION) null else convert(dto)
    } catch (_: Exception) {
        null
    }
}

/** Writes [dto] to [file] as JSON, atomically (see [StateFile]); the version field is part of [dto]. */
internal fun <D> writeState(file: StateFile, serializer: KSerializer<D>, dto: D) {
    file.writeText(json.encodeToString(serializer, dto))
}

/**
 * The file name for a Drive folder id: the id itself when it is made of letters, digits, `_` and `-` (what Drive ids
 * are), else `h.` and the hash of it. No id can name a file outside the store's folder.
 */
internal fun folderFileName(prefix: String, rootId: String): String {
    require(rootId.isNotBlank()) { "root id is blank" }
    val safe = SAFE_ID.matches(rootId)
    val part = if (safe) rootId else "h." + sha256HexOf(rootId.encodeToByteArray())
    return "$prefix-$part.json"
}

private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,100}")

