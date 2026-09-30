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

package app.doorprints.shared.export

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A backup opened by [BackupArchive.open], or why it was refused. */
sealed interface ArchiveOpen {
    class Ok(val archive: BackupArchive) : ArchiveOpen
    class Failed(val problem: BackupProblem) : ArchiveOpen
}

/**
 * A `doorprints-backup` file read in common code (S4b-BL-81, the iPhone's *Import a backup*): the ZIP the apps write,
 * or a bare `data.json` (the server's `GET /api/export`). The same checks, in the same order and with the same answers,
 * as Android's `BackupReader` in `:app` (docs/schemas/README.md section 2): first bytes, not the name, tell the two
 * apart; the entry count, every entry's path (zip slip), the declared and the real sizes, the manifest, the SHA-256 of
 * `data.json` and the rows' shapes are checked before anything is written. A photo's SHA-256 is checked when it is read.
 * Every byte comes through [ZipReader.readAtMost], and a running total caps the whole import at
 * [BackupFormat.MAX_UNCOMPRESSED_BYTES].
 */
class BackupArchive private constructor(
    private val zip: ZipReader?,
    private val tools: ArchiveTools,
    val manifest: BackupManifest?,
    val data: BackupData,
    /** Names of the `photos/…` entries actually present in the archive. */
    val photoEntries: Set<String>,
    alreadyRead: Long,
) {
    private val hashes: Map<String, String> = manifest?.files.orEmpty().associate { it.path to it.sha256.lowercase() }

    private var decompressed = alreadyRead

    /**
     * A photo's bytes, or null when the entry is missing, larger than a photo of ours can be, or fails the manifest's
     * SHA-256. A bad photo is skipped; it never fails the whole import. One caller at a time (the import).
     */
    fun photoBytes(entry: String): ByteArray? {
        if (!BackupValidation.isPhotoEntry(entry)) return null
        val archive = zip ?: return null
        val info = archive.entry(entry) ?: return null
        val budget = minOf(MAX_PHOTO_BYTES, BackupFormat.MAX_UNCOMPRESSED_BYTES - decompressed)
        if (budget <= 0) return null
        // A broken entry is skipped like a missing one.
        val bytes = runCatching { archive.readAtMost(info, budget, tools) }.getOrNull() ?: return null
        decompressed += bytes.size
        val expected = hashes[entry] ?: return bytes
        return if (sha256Hex(tools, bytes) == expected) bytes else null
    }

    companion object {
        private const val MAX_PHOTO_BYTES = 32L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
        private const val HEAD_BYTES = 64
        private const val FORMAT_FAMILY = "doorprints-backup/"

        /** Opens [source]; any failure while reading it is [BackupProblem.NOT_A_BACKUP], never a crash. */
        fun open(source: RandomSource, tools: ArchiveTools): ArchiveOpen = try {
            openChecked(source, tools)
        } catch (_: Exception) {
            ArchiveOpen.Failed(BackupProblem.NOT_A_BACKUP)
        }

        private fun openChecked(source: RandomSource, tools: ArchiveTools): ArchiveOpen {
            val head = ZipReader.readFully(source, 0, minOf(source.size, HEAD_BYTES.toLong()).toInt()) ?: ByteArray(0)
            if (!isZip(head)) {
                return if (isJsonObject(head)) openBareData(source, tools) else ArchiveOpen.Failed(BackupProblem.NOT_A_BACKUP)
            }
            val zip = when (val opened = ZipReader.open(source, BackupFormat.MAX_ENTRIES)) {
                is ZipOpen.Failed -> return ArchiveOpen.Failed(
                    if (opened.problem == ZipOpenProblem.TOO_MANY_ENTRIES) BackupProblem.TOO_MANY_ENTRIES else BackupProblem.NOT_A_BACKUP,
                )
                is ZipOpen.Ok -> opened.reader
            }
            // First filter only: these numbers are the file author's claim; readAtMost measures.
            var declared = 0L
            for (entry in zip.entries) {
                if (BackupValidation.isSuspiciousPath(entry.name.removeSuffix("/"))) {
                    return ArchiveOpen.Failed(BackupProblem.SUSPICIOUS_PATH)
                }
                declared += entry.size
                if (declared > BackupFormat.MAX_UNCOMPRESSED_BYTES) return ArchiveOpen.Failed(BackupProblem.TOO_LARGE)
                val packed = entry.compressedSize
                if (packed > 0 && entry.size / packed > BackupFormat.MAX_COMPRESSION_RATIO) {
                    return ArchiveOpen.Failed(BackupProblem.TOO_LARGE)
                }
            }
            val dataEntry = zip.entry(BackupFormat.DATA_ENTRY) ?: return ArchiveOpen.Failed(BackupProblem.NOT_A_BACKUP)
            if (dataEntry.size > BackupFormat.MAX_DATA_JSON_BYTES) return ArchiveOpen.Failed(BackupProblem.TOO_LARGE)
            val dataBytes = zip.readAtMost(dataEntry, BackupFormat.MAX_DATA_JSON_BYTES, tools)
                ?: return ArchiveOpen.Failed(BackupProblem.TOO_LARGE)
            val manifestEntry = zip.entry(BackupFormat.MANIFEST_ENTRY)
                ?: return ArchiveOpen.Failed(BackupProblem.NOT_A_BACKUP)
            val manifestBytes = zip.readAtMost(manifestEntry, MAX_MANIFEST_BYTES, tools)
                ?: return ArchiveOpen.Failed(BackupProblem.TOO_LARGE)
            val manifest = runCatching {
                BackupFormat.json.decodeFromString(BackupManifest.serializer(), manifestBytes.decodeToString())
            }.getOrNull() ?: return ArchiveOpen.Failed(BackupProblem.NOT_A_BACKUP)
            BackupValidation.checkManifest(manifest)?.let { return ArchiveOpen.Failed(it) }
            manifest.files.firstOrNull { it.path == BackupFormat.DATA_ENTRY }?.let { listed ->
                if (sha256Hex(tools, dataBytes) != listed.sha256.lowercase()) {
                    return ArchiveOpen.Failed(BackupProblem.CHECKSUM_MISMATCH)
                }
            }
            val data = runCatching {
                BackupFormat.json.decodeFromString(BackupData.serializer(), dataBytes.decodeToString())
            }.getOrElse { return ArchiveOpen.Failed(BackupProblem.BROKEN_DATA) }
            BackupValidation.checkData(data)?.let { return ArchiveOpen.Failed(it) }
            val photos = zip.entries.map { it.name }.filter { BackupValidation.isPhotoEntry(it) }.toSet()
            return ArchiveOpen.Ok(
                BackupArchive(zip, tools, manifest, data, photos, (dataBytes.size + manifestBytes.size).toLong()),
            )
        }

        /** A bare `data.json`: the size first, then the format id, then the shape, as Android's `BackupReader`. */
        private fun openBareData(source: RandomSource, tools: ArchiveTools): ArchiveOpen {
            val size = source.size
            if (size > BackupFormat.MAX_DATA_JSON_BYTES) return ArchiveOpen.Failed(BackupProblem.TOO_LARGE)
            if (size == 0L) return ArchiveOpen.Failed(BackupProblem.NOT_A_BACKUP)
            val text = ZipReader.readFully(source, 0, size.toInt())?.decodeToString()?.removePrefix("﻿")
                ?: return ArchiveOpen.Failed(BackupProblem.READ_FAILED)
            val root = runCatching { BackupFormat.json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return ArchiveOpen.Failed(BackupProblem.NOT_A_BACKUP)
            val format = (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return ArchiveOpen.Failed(BackupProblem.NOT_A_BACKUP)
            if (!BackupFormat.accepts(format)) {
                return ArchiveOpen.Failed(
                    if (format.startsWith(FORMAT_FAMILY)) BackupProblem.UNSUPPORTED_VERSION else BackupProblem.NOT_A_BACKUP,
                )
            }
            val data = runCatching { BackupFormat.json.decodeFromJsonElement(BackupData.serializer(), root) }
                .getOrElse { return ArchiveOpen.Failed(BackupProblem.BROKEN_DATA) }
            BackupValidation.checkData(data)?.let { return ArchiveOpen.Failed(it) }
            return ArchiveOpen.Ok(BackupArchive(null, tools, null, data, emptySet(), size))
        }

        /** A local file header, or the end record of an empty ZIP. */
        private fun isZip(head: ByteArray): Boolean =
            head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                ((head[2] == 3.toByte() && head[3] == 4.toByte()) || (head[2] == 5.toByte() && head[3] == 6.toByte()))

        /** `{` after an optional UTF-8 byte-order mark and whitespace. */
        private fun isJsonObject(head: ByteArray): Boolean {
            var i = 0
            if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
            while (i < head.size && head[i].toInt().toChar() in " \t\r\n") i++
            return i < head.size && head[i] == '{'.code.toByte()
        }
    }
}

/** The lowercase hex SHA-256 of [bytes]. */
internal fun sha256Hex(tools: ArchiveTools, bytes: ByteArray): String =
    hexOf(tools.sha256().apply { update(bytes) }.digest())
