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

package app.doorprints.shared.sync

import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.records.RecordRules
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The four lists of a `sync/1` file (S4b-BL-130, docs/15 §5.1), in the order they are written and merged, with the most
 * rows each may hold (tombstones included, they are kept for ever). A row's key is its `id`; a record's is `type/id`.
 */
enum class SyncKind(val key: String, val maxRows: Int) {
    HOUSES("houses", 20_000),
    VISITS("visits", 50_000),
    RECORDS("records", 50_000),
    PHOTOS("photos", 50_000),
}

/** Why a sync file was refused; the same codes on both stacks (`docs/schemas/sync-vectors.json`). */
enum class SyncFileProblem {
    /** Not JSON, or not a JSON object. */
    NOT_JSON,

    /** No `doorprints-sync/<n>` format. */
    NOT_A_SYNC_FILE,

    /** A newer `doorprints-sync/<n>` than this app reads: "update the app". */
    UNSUPPORTED_VERSION,

    /** `deviceId` is not the device whose file this is (a file swapped or copied into another device's place). */
    WRONG_DEVICE,

    /** Over the byte cap, or a list over its row cap. */
    TOO_LARGE,

    /** The envelope or a row lacks a field the merge needs, or has it in the wrong type or form. */
    BAD_ROW,

    /** Two rows of one list with the same key. */
    DUPLICATE_ROW,
}

/** A sync file this app will not read, refused whole: nothing of it is merged. */
class SyncFileException(val problem: SyncFileProblem, message: String) : Exception(message)

/**
 * The version of a row the merge compares: when it was made ([updatedAt], epoch milliseconds), by which device ([by]),
 * and whether it is a tombstone. Ordered by `updatedAt`, then `by` (code units), then a tombstone above a live row:
 * a total order on everything the merge looks at, so "keep the greater" converges whatever the order of the files
 * ([DriveMerge.takesIncoming]).
 */
data class SyncStamp(val updatedAt: Long, val by: String, val deleted: Boolean) : Comparable<SyncStamp> {
    override fun compareTo(other: SyncStamp): Int =
        compareValuesBy(this, other, { it.updatedAt }, { it.by }, { it.deleted })
}

/**
 * One row of a sync file: its [kind], [key], [stamp], and the whole row as it travels ([json]: the sync DTO's fields plus
 * `by`), which the sync loop decodes and validates like a server row. Made only by [SyncFiles.parse] or [of], so every
 * row has passed the checks.
 */
class SyncRow private constructor(val kind: SyncKind, val key: String, val stamp: SyncStamp, val json: JsonObject) {
    companion object {
        /** [json] as a row of [kind]; throws [SyncFileException] (`BAD_ROW`) when a field the merge needs is wrong. */
        fun of(kind: SyncKind, json: JsonObject): SyncRow {
            fun bad(what: String): Nothing = throw SyncFileException(SyncFileProblem.BAD_ROW, "${kind.key}: $what")
            val id = json.string("id")?.takeIf(RecordRules::isValidId) ?: bad("id")
            val key = if (kind == SyncKind.RECORDS) {
                val type = json.string("type")?.takeIf(RecordRules::isValidType) ?: bad("type of $id")
                "$type/$id"
            } else {
                id
            }
            val updatedAt = json.string("updatedAt")?.let(SyncTime::parse)?.takeIf { it >= SyncTime.EARLIEST_MS }
                ?: bad("updatedAt of $key")
            val by = json.string("by")?.takeIf(SyncFiles::isDeviceId) ?: bad("by of $key")
            val deleted = json.boolean("deleted") ?: bad("deleted of $key")
            if (kind == SyncKind.PHOTOS) {
                json.string("houseId")?.takeIf(RecordRules::isValidId) ?: bad("houseId of $key")
                if ("driveFileId" in json) json.string("driveFileId")?.takeIf(SyncFiles::isDriveFileId) ?: bad("driveFileId of $key")
                if ("sha256" in json) json.string("sha256")?.takeIf(SyncFiles::isSha256) ?: bad("sha256 of $key")
            }
            if (kind == SyncKind.VISITS && "houseId" in json && json["houseId"] !is JsonNull) {
                json.string("houseId")?.takeIf(RecordRules::isValidId) ?: bad("houseId of $key")
            }
            return SyncRow(kind, key, SyncStamp(updatedAt, by, deleted), json)
        }
    }
}

/**
 * A parsed `sync/1` file: the whole state of one device ([deviceId]), its write counter [seq] (raised by every write,
 * so a file brought back from an older Drive revision is seen as stale), when it was written, and its rows by kind,
 * each list sorted by key.
 */
class SyncFile(val deviceId: String, val seq: Long, val writtenAt: Long, rows: Map<SyncKind, List<SyncRow>>) {
    val rows: Map<SyncKind, List<SyncRow>> = SyncKind.entries.associateWith { kind ->
        rows[kind].orEmpty().onEach { require(it.kind == kind) { "a ${it.kind.key} row in ${kind.key}" } }.sortedBy { it.key }
    }

    fun rows(kind: SyncKind): List<SyncRow> = rows.getValue(kind)
}

/**
 * The `sync/1` inner format (S4b-BL-130, docs/15 §5.1; schema `docs/schemas/sync-1.schema.json`, vectors
 * `docs/schemas/sync-vectors.json`; the website's twin is `web/src/app/data/drive/sync-file.ts`). Every file read from
 * Drive is untrusted input (docs/schemas §6): [parse] is strict about everything the merge relies on and refuses the
 * whole file on the first problem; the rest of a row is the loop's to validate, as for the server's rows.
 */
object SyncFiles {
    const val FORMAT = "doorprints-sync/1"
    const val FORMAT_PREFIX = "doorprints-sync/"
    const val MAX_VERSION = 1

    /** The cap of the decompressed JSON, in UTF-8 bytes: the backup's `data.json` cap (docs/schemas §7). */
    const val MAX_BYTES = 16 * 1024 * 1024

    /** All four lists together. */
    const val MAX_ROWS = 100_000

    /**
     * The deepest nesting of objects and arrays (the root object is 1, a row 3, a record's payload from 4): checked
     * before parsing, because kotlinx's tree reader overflows the stack on a few hundred thousand brackets.
     */
    const val MAX_DEPTH = 64

    /** The highest `seq`: the largest integer both stacks hold exactly (2^53 - 1). */
    const val MAX_SEQ = 9_007_199_254_740_991L

    private val DEVICE_ID = Regex("[A-Za-z0-9_-]{8,64}")
    private val DRIVE_FILE_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val VERSION = Regex("[1-9][0-9]{0,8}")

    fun isDeviceId(value: String): Boolean = DEVICE_ID.matches(value)

    fun isDriveFileId(value: String): Boolean = DRIVE_FILE_ID.matches(value)

    fun isSha256(value: String): Boolean = SHA256.matches(value)

    /**
     * Reads [text] as the sync file of [expectedDeviceId] (the writer the envelope authenticated, docs/15 §9.6).
     * Unknown fields are ignored, at the top and in rows (a newer writer within `/1`).
     *
     * @throws SyncFileException with the [SyncFileProblem] of the first thing wrong.
     */
    fun parse(text: String, expectedDeviceId: String): SyncFile {
        if (utf8Length(text) > MAX_BYTES) throw SyncFileException(SyncFileProblem.TOO_LARGE, "over $MAX_BYTES bytes")
        if (nestingDepth(text) > MAX_DEPTH) throw SyncFileException(SyncFileProblem.TOO_LARGE, "nested deeper than $MAX_DEPTH")
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw SyncFileException(SyncFileProblem.NOT_JSON, "not JSON")
        } catch (e: IllegalArgumentException) {
            throw SyncFileException(SyncFileProblem.NOT_JSON, "not JSON")
        }
        if (root !is JsonObject) throw SyncFileException(SyncFileProblem.NOT_JSON, "not a JSON object")
        checkFormat(root.string("format"))
        fun bad(what: String): Nothing = throw SyncFileException(SyncFileProblem.BAD_ROW, what)
        val deviceId = root.string("deviceId")?.takeIf(::isDeviceId) ?: bad("deviceId")
        if (deviceId != expectedDeviceId) throw SyncFileException(SyncFileProblem.WRONG_DEVICE, "deviceId is not the writer's")
        val seq = root.integer("seq")?.takeIf { it in 1..MAX_SEQ } ?: bad("seq")
        val writtenAt = root.string("writtenAt")?.let(SyncTime::parse)?.takeIf { it >= SyncTime.EARLIEST_MS } ?: bad("writtenAt")
        val lists = SyncKind.entries.associateWith { kind ->
            val list = root[kind.key] as? JsonArray ?: bad(kind.key)
            if (list.size > kind.maxRows) throw SyncFileException(SyncFileProblem.TOO_LARGE, "${kind.key} over ${kind.maxRows}")
            list
        }
        if (lists.values.sumOf { it.size } > MAX_ROWS) throw SyncFileException(SyncFileProblem.TOO_LARGE, "over $MAX_ROWS rows")
        val rows = lists.mapValues { (kind, list) ->
            val seen = HashSet<String>(list.size * 2)
            list.map { element ->
                val row = SyncRow.of(kind, element as? JsonObject ?: bad("${kind.key}: a row that is not an object"))
                if (!seen.add(row.key)) throw SyncFileException(SyncFileProblem.DUPLICATE_ROW, "${kind.key}: ${row.key} twice")
                row
            }
        }
        return SyncFile(deviceId, seq, writtenAt, rows)
    }

    /**
     * The canonical text of [file]: the envelope in a fixed order, then the four lists in [SyncKind] order, each sorted
     * by key. Refuses (as [SyncFileException]) a file a reader would refuse: over a cap, or two rows with one key.
     */
    fun encode(file: SyncFile): String {
        require(isDeviceId(file.deviceId)) { "deviceId" }
        require(file.seq in 1..MAX_SEQ) { "seq" }
        var total = 0
        for (kind in SyncKind.entries) {
            val list = file.rows(kind)
            if (list.size > kind.maxRows) throw SyncFileException(SyncFileProblem.TOO_LARGE, "${kind.key} over ${kind.maxRows}")
            total += list.size
            for (i in 1 until list.size) {
                if (list[i].key == list[i - 1].key) throw SyncFileException(SyncFileProblem.DUPLICATE_ROW, "${kind.key}: ${list[i].key} twice")
            }
        }
        if (total > MAX_ROWS) throw SyncFileException(SyncFileProblem.TOO_LARGE, "over $MAX_ROWS rows")
        val text = buildJsonObject {
            put("format", FORMAT)
            put("deviceId", file.deviceId)
            put("seq", file.seq)
            put("writtenAt", IsoTime.format(file.writtenAt))
            for (kind in SyncKind.entries) put(kind.key, JsonArray(file.rows(kind).map { it.json }))
        }.toString()
        if (utf8Length(text) > MAX_BYTES) throw SyncFileException(SyncFileProblem.TOO_LARGE, "over $MAX_BYTES bytes")
        return text
    }

    private fun checkFormat(format: String?) {
        if (format == FORMAT) return
        val number = format?.takeIf { it.startsWith(FORMAT_PREFIX) }?.removePrefix(FORMAT_PREFIX)
        if (number != null && VERSION.matches(number) && number.toInt() > MAX_VERSION) {
            throw SyncFileException(SyncFileProblem.UNSUPPORTED_VERSION, "made by a newer version of Doorprints")
        }
        throw SyncFileException(SyncFileProblem.NOT_A_SYNC_FILE, "not a Doorprints sync file")
    }

    /**
     * The deepest nesting of `{` and `[` in [text] outside strings, stopping as soon as it passes [MAX_DEPTH]. Not a
     * validator: unbalanced text is the parser's to refuse.
     */
    internal fun nestingDepth(text: String): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > deepest) deepest = depth
                        if (deepest > MAX_DEPTH) return deepest
                    }
                    '}', ']' -> depth--
                }
            }
            i++
        }
        return deepest
    }

    /** The UTF-8 length of [text] without encoding it (an unpaired surrogate counts as the 3 bytes of U+FFFD). */
    internal fun utf8Length(text: String): Long {
        var n = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i].code
            when {
                c < 0x80 -> n += 1
                c < 0x800 -> n += 2
                c in 0xD800..0xDBFF && i + 1 < text.length && text[i + 1].code in 0xDC00..0xDFFF -> { n += 4; i++ }
                else -> n += 3
            }
            i++
        }
        return n
    }
}

/**
 * The times of a sync file, read the same way on both stacks (web: `syncTime` in `sync-file.ts`): an ISO-8601 UTC instant
 * `YYYY-MM-DDTHH:MM:SS[.f]Z` with 1 to 9 fraction digits (truncated to milliseconds), a real calendar date, no offset and
 * no leap second. Hand-written rather than `Instant.parse` / `Date.parse`, whose leniency differs between platforms.
 */
object SyncTime {
    /** 2000-01-01T00:00:00Z, the server's `ClientClock.EARLIEST`: an earlier stamp is a bug or tampering. */
    const val EARLIEST_MS = 946_684_800_000L

    private val ISO = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,9}))?Z")

    /** Epoch milliseconds of [text], or null when it is not exactly that form. */
    fun parse(text: String): Long? {
        val g = ISO.matchEntire(text)?.groupValues ?: return null
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].toInt()
        val minute = g[5].toInt()
        val second = g[6].toInt()
        if (month !in 1..12 || day < 1 || day > daysIn(year, month) || hour > 23 || minute > 59 || second > 59) return null
        val millis = g[7].padEnd(3, '0').take(3).toInt()
        return daysFromCivil(year, month, day) * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
    }

    private fun daysIn(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's algorithm), for years 0..9999. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.boolean(key: String): Boolean? =
    (get(key) as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull

private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

/**
 * A JSON number that is an integer (`1`, `1.0` and `1e0` alike, as `JSON.parse` reads them), or null. The grammar is
 * checked first: kotlinx's tree reader also takes bare words, and `toDouble` reads hex floats.
 */
private fun JsonObject.integer(key: String): Long? {
    val p = get(key) as? JsonPrimitive ?: return null
    if (p.isString || p is JsonNull || !JSON_NUMBER.matches(p.content)) return null
    val d = p.content.toDoubleOrNull()?.takeIf { it.isFinite() && it == kotlin.math.floor(it) } ?: return null
    return if (d < 0 || d > SyncFiles.MAX_SEQ.toDouble()) null else d.toLong()
}
