package com.househunt.app.export

import com.househunt.shared.export.BackupData
import com.househunt.shared.export.BackupFormat
import com.househunt.shared.export.BackupManifest
import com.househunt.shared.export.BackupProblem
import com.househunt.shared.export.BackupValidation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Either an open, validated backup or the reason it was refused. */
sealed interface BackupOpen {
    data class Ok(val reader: BackupReader) : BackupOpen
    data class Failed(val problem: BackupProblem) : BackupOpen
}

/**
 * Reads a `doorprints-backup/1` backup (S4-04): the ZIP the apps write, or a bare `data.json` — what the server's
 * `GET /api/export` downloads. docs/schemas/README.md section 2: "an importer must not require [a manifest] when it
 * is handed a bare data.json". A bare file carries no photo bytes, so its photo rows are reported as missing from
 * the file by the preview and skipped by the import, exactly as for a ZIP that lacks them.
 *
 * Everything is checked **before** a single row is written: the format, the entry count, the entry names (zip
 * slip), the size of everything actually decompressed, the DTO shapes and the SHA-256 of `data.json`. A photo's
 * hash is checked when its bytes are read, not up front, because that would mean reading a 1 GB archive twice.
 *
 * **The central directory is not evidence.** `ZipEntry.size` and `ZipEntry.compressedSize` are numbers the author
 * of the file wrote; `java.util.zip.ZipFile` does not verify the declared uncompressed size while it inflates, so
 * a size of 100 on an entry whose megabyte of deflate expands to a gigabyte passes every arithmetic check and then
 * kills the process inside `readBytes()`. Deflate reaches about 1000:1 on a run of zeroes, so the declared numbers
 * can only ever be a cheap first filter. Every byte this class returns therefore goes through [readAtMost], which
 * counts what actually comes out of the inflater and gives up the moment it passes the limit, and a running total
 * across all entries read caps the whole import at [BackupFormat.MAX_UNCOMPRESSED_BYTES].
 *
 * `ZipFile` rather than `ZipInputStream` is still the right reader: it gives random access to `data.json` and to
 * one photo at a time, which is what the importer needs.
 */
class BackupReader private constructor(
    /** Null for a bare `data.json`, which has nothing but the rows. */
    private val zip: ZipFile?,
    val manifest: BackupManifest?,
    val data: BackupData,
    /** Names of the `photos/…` entries actually present in the archive. */
    val photoEntries: Set<String>,
    /** Bytes already decompressed while opening, so the running cap covers the whole file, not each entry. */
    alreadyRead: Long,
) : Closeable {

    private val hashes: Map<String, String> =
        manifest?.files.orEmpty().associate { it.path to it.sha256.lowercase() }

    /** Guarded by @Synchronized below; the worker reads photos one at a time, but the cap must not race. */
    private var decompressed: Long = alreadyRead

    /**
     * A photo's bytes, or null when the entry is missing, is larger than a photo of ours can be, or its SHA-256
     * does not match the manifest. A bad photo is skipped; it never fails the whole import, because the rest of
     * the backup is still worth restoring.
     */
    @Synchronized
    fun photoBytes(entry: String): ByteArray? {
        if (!BackupValidation.isPhotoEntry(entry)) return null
        val archive = zip ?: return null
        val zipEntry = archive.getEntry(entry) ?: return null
        val budget = minOf(MAX_PHOTO_BYTES, BackupFormat.MAX_UNCOMPRESSED_BYTES - decompressed)
        if (budget <= 0) return null
        val bytes = runCatching { archive.readAtMost(zipEntry, budget) }.getOrNull() ?: return null
        decompressed += bytes.size
        val expected = hashes[entry] ?: return bytes
        return if (Exporters.hex(sha256(bytes)) == expected) bytes else null
    }

    override fun close() {
        zip?.close()
    }

    companion object {
        /** A single photo above this is not one of ours; the exporter writes at most a few megabytes. */
        private const val MAX_PHOTO_BYTES = 32L * 1024 * 1024

        fun open(file: File): BackupOpen {
            // A staged copy that is gone (a failed import deletes it) is a storage problem, not a bad file:
            // "not a Doorprints backup" would send the user looking for the wrong fix.
            if (!file.exists()) return BackupOpen.Failed(BackupProblem.READ_FAILED)
            // Told apart by their first bytes, never by the name: providers rename, and the staged copy has none.
            val head = firstBytes(file, HEAD_BYTES)
            if (!isZip(head)) {
                return if (isJsonObject(head)) openBareData(file) else BackupOpen.Failed(BackupProblem.NOT_A_BACKUP)
            }
            val zip = try {
                ZipFile(file)
            } catch (_: Exception) {
                return BackupOpen.Failed(BackupProblem.NOT_A_BACKUP)
            }
            var ok = false
            var read = 0L
            try {
                val entries = zip.entries().toList()
                if (entries.size > BackupFormat.MAX_ENTRIES) {
                    return BackupOpen.Failed(BackupProblem.TOO_MANY_ENTRIES)
                }
                // First filter only: these numbers are the file author's claim, not a measurement. An honest
                // backup is refused here cheaply; a lying one is caught by readAtMost below.
                var declared = 0L
                for (entry in entries) {
                    if (BackupValidation.isSuspiciousPath(entry.name.removeSuffix("/"))) {
                        return BackupOpen.Failed(BackupProblem.SUSPICIOUS_PATH)
                    }
                    val size = entry.size
                    val packed = entry.compressedSize
                    if (size < 0 || packed < 0) return BackupOpen.Failed(BackupProblem.NOT_A_BACKUP)
                    declared += size
                    if (declared > BackupFormat.MAX_UNCOMPRESSED_BYTES) {
                        return BackupOpen.Failed(BackupProblem.TOO_LARGE)
                    }
                    // A ZIP bomb is a tiny entry that expands enormously; 100:1 is far above anything JPEG,
                    // JSON or HTML reaches in practice.
                    if (packed > 0 && size / packed > BackupFormat.MAX_COMPRESSION_RATIO) {
                        return BackupOpen.Failed(BackupProblem.TOO_LARGE)
                    }
                }

                val dataEntry = zip.getEntry(BackupFormat.DATA_ENTRY)
                    ?: return BackupOpen.Failed(BackupProblem.NOT_A_BACKUP)
                if (dataEntry.size > BackupFormat.MAX_DATA_JSON_BYTES) {
                    return BackupOpen.Failed(BackupProblem.TOO_LARGE)
                }
                val dataBytes = zip.readAtMost(dataEntry, BackupFormat.MAX_DATA_JSON_BYTES)
                    ?: return BackupOpen.Failed(BackupProblem.TOO_LARGE)
                read += dataBytes.size

                val manifestEntry = zip.getEntry(BackupFormat.MANIFEST_ENTRY)
                    ?: return BackupOpen.Failed(BackupProblem.NOT_A_BACKUP)
                // The manifest is a short document: a handful of options and one row per entry.
                val manifestBytes = zip.readAtMost(manifestEntry, MAX_MANIFEST_BYTES)
                    ?: return BackupOpen.Failed(BackupProblem.TOO_LARGE)
                read += manifestBytes.size
                val manifest = runCatching {
                    BackupFormat.json.decodeFromString(
                        BackupManifest.serializer(),
                        manifestBytes.toString(Charsets.UTF_8),
                    )
                }.getOrNull() ?: return BackupOpen.Failed(BackupProblem.NOT_A_BACKUP)
                BackupValidation.checkManifest(manifest)?.let { return BackupOpen.Failed(it) }

                manifest.files.firstOrNull { it.path == BackupFormat.DATA_ENTRY }?.let { listed ->
                    if (Exporters.hex(sha256(dataBytes)) != listed.sha256.lowercase()) {
                        return BackupOpen.Failed(BackupProblem.CHECKSUM_MISMATCH)
                    }
                }

                val data = runCatching {
                    BackupFormat.json.decodeFromString(
                        BackupData.serializer(),
                        dataBytes.toString(Charsets.UTF_8),
                    )
                }.getOrElse { return BackupOpen.Failed(BackupProblem.BROKEN_DATA) }
                BackupValidation.checkData(data)?.let { return BackupOpen.Failed(it) }

                val photos = entries.map { it.name }.filter { BackupValidation.isPhotoEntry(it) }.toSet()
                ok = true
                return BackupOpen.Ok(BackupReader(zip, manifest, data, photos, read))
            } catch (_: Exception) {
                return BackupOpen.Failed(BackupProblem.NOT_A_BACKUP)
            } finally {
                // The reader owns the handle once it exists; on every other path it has to be closed here.
                if (!ok) runCatching { zip.close() }
            }
        }

        private const val HEAD_BYTES = 64

        /** Up to [count] bytes from the start of [file]; fewer when it is shorter, none when it cannot be read. */
        private fun firstBytes(file: File, count: Int): ByteArray = runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(count)
                var total = 0
                // `InputStream.readNBytes` is API 33+; minSdk is 26.
                while (total < count) {
                    val read = input.read(buffer, total, count - total)
                    if (read < 0) break
                    total += read
                }
                buffer.copyOf(total)
            }
        }.getOrDefault(ByteArray(0))

        /** A ZIP starts with a local file header, or — when it holds nothing — with the end-of-directory record. */
        private fun isZip(head: ByteArray): Boolean =
            head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                ((head[2] == 3.toByte() && head[3] == 4.toByte()) || (head[2] == 5.toByte() && head[3] == 6.toByte()))

        /**
         * A JSON object: `{` after an optional UTF-8 byte-order mark and whitespace. Anything else — a PDF, a photo,
         * one of our HTML copies — is [BackupProblem.NOT_A_BACKUP] straight away, rather than [BackupProblem.TOO_LARGE]
         * from the JSON size cap, which would send the user looking for the wrong fix.
         */
        private fun isJsonObject(head: ByteArray): Boolean {
            var i = 0
            if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
            while (i < head.size && head[i].toInt().toChar() in " \t\r\n") i++
            return i < head.size && head[i] == '{'.code.toByte()
        }

        /**
         * A bare `data.json`. The same checks as the ZIP's `data.json` — size first, then the format id, then the
         * shape — in an order that gives the most useful answer: a file that is JSON but not ours is
         * [BackupProblem.NOT_A_BACKUP], one of ours from a newer format is [BackupProblem.UNSUPPORTED_VERSION], and
         * only a file that says it is `doorprints-backup/1` and then does not parse is [BackupProblem.BROKEN_DATA].
         */
        private fun openBareData(file: File): BackupOpen {
            val size = file.length()
            if (size > BackupFormat.MAX_DATA_JSON_BYTES) return BackupOpen.Failed(BackupProblem.TOO_LARGE)
            if (size == 0L) return BackupOpen.Failed(BackupProblem.NOT_A_BACKUP)
            val text = try {
                // A byte-order mark is not JSON; a file saved by a Windows editor may still carry one.
                file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
            } catch (_: Exception) {
                return BackupOpen.Failed(BackupProblem.READ_FAILED)
            }
            val root = runCatching { BackupFormat.json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return BackupOpen.Failed(BackupProblem.NOT_A_BACKUP)
            val format = (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return BackupOpen.Failed(BackupProblem.NOT_A_BACKUP)
            if (format != BackupFormat.ID) {
                val newer = format.startsWith(FORMAT_FAMILY)
                return BackupOpen.Failed(if (newer) BackupProblem.UNSUPPORTED_VERSION else BackupProblem.NOT_A_BACKUP)
            }
            val data = runCatching { BackupFormat.json.decodeFromJsonElement(BackupData.serializer(), root) }
                .getOrElse { return BackupOpen.Failed(BackupProblem.BROKEN_DATA) }
            BackupValidation.checkData(data)?.let { return BackupOpen.Failed(it) }
            return BackupOpen.Ok(BackupReader(null, null, data, emptySet(), size))
        }

        /** `doorprints-backup/2` is ours from a newer app; `house-hunt-export/1` or anything else is not ours at all. */
        private const val FORMAT_FAMILY = "doorprints-backup/"

        /** `manifest.json` for 5 000 entries is well under a megabyte. */
        private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024

        private fun sha256(bytes: ByteArray): ByteArray =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/**
 * The entry's bytes, or null once more than [limit] of them have actually come out of the inflater.
 *
 * This — not `ZipEntry.size` — is what makes the ZIP-bomb limits real. `ZipFile.getInputStream(...).readBytes()`
 * bounds its *input* by the compressed size and will happily grow its output to whatever deflate expands to, so
 * a declared size of 100 is no protection at all. Here nothing is allocated beyond what has actually been read,
 * so a lying central directory costs [limit] bytes and a refusal rather than the process.
 */
private fun ZipFile.readAtMost(entry: ZipEntry, limit: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    getInputStream(entry).use { input ->
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) return null
            out.write(buffer, 0, count)
        }
    }
    return out.toByteArray()
}
