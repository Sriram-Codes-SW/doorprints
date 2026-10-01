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

/*
 * The ZIP container in common code (S4b-BL-81), for the platforms without `java.util.zip` (the iPhone): a writer of
 * STORED entries and a reader of STORED and DEFLATE ones, which is what the apps write (Android's `ZipOutputStream`
 * deflates) and what a backup, a CSV copy and a workbook are. What the platform has and common code has not, SHA-256
 * and raw DEFLATE decoding, comes in as [ArchiveTools] (iOS: CommonCrypto and zlib). No ZIP64: a copy that needs it is
 * over the import's 1 GiB limit anyway, so the writer refuses it and the reader calls it not a backup.
 */

/** Where a copy's bytes go: a file that can also be written again at an earlier [position] (the entries' headers). */
interface CopySink {
    /** How many bytes have been written. */
    val position: Long

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)

    /** Writes [bytes] again over what was written at [at] (which is below [position]). */
    fun patch(at: Long, bytes: ByteArray)
}

/** A file read at any position (the staged backup). */
interface RandomSource {
    val size: Long

    /** Reads up to [length] bytes at [position] into [buffer] from [offset]; the count read, 0 at the end. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** An incremental SHA-256 (iOS: CommonCrypto's `CC_SHA256_*`; the JVM tests: `MessageDigest`). */
interface Sha256 {
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)

    /** The 32-byte digest; the hasher is used up. */
    fun digest(): ByteArray
}

/** What the ZIP code needs from the platform. */
class ArchiveTools(
    val sha256: () -> Sha256,
    /**
     * [compressed] (raw DEFLATE, RFC 1951) decoded, or null when it would come to more than [maxBytes]: the output is
     * counted as it is produced, so a lying size in the archive costs [maxBytes], no more. Throws
     * [IllegalArgumentException] when it is not valid DEFLATE (broken or cut short).
     */
    val inflate: (compressed: ByteArray, maxBytes: Int) -> ByteArray?,
)

/** Lowercase hex of [bytes], as the manifest's `sha256` values are written. */
fun hexOf(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        out.append(digits[v ushr 4]).append(digits[v and 0x0F])
    }
    return out.toString()
}

/** CRC-32 (the ZIP polynomial 0xEDB88320), incremental: `update` returns the new value from the old one. */
object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun update(crc: Int, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        var c = crc.inv()
        for (i in offset until offset + length) c = table[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }

    fun of(bytes: ByteArray): Int = update(0, bytes)
}

/**
 * Writes a ZIP of STORED entries into [sink], each one streamed: its local header goes out with a zero CRC and size,
 * the bytes follow, and the header is [CopySink.patch]ed once they are counted, so nothing is held whole in memory and
 * no data descriptor is needed (every reader, `ZipInputStream` included, finds the sizes in the local header).
 *
 * Every entry carries the export's own time ([timeMillis] at [utcOffsetMinutes], as the file names do), so the same data
 * gives the same archive. Names are UTF-8 (general purpose bit 11).
 */
class ZipWriter(private val sink: CopySink, timeMillis: Long, utcOffsetMinutes: Int) {
    private class Central(val name: ByteArray, val offset: Long, val crc: Int, val size: Long)

    private val entries = mutableListOf<Central>()
    private val dosTime: Int
    private val dosDate: Int

    init {
        val (date, time) = dosDateTime(timeMillis + utcOffsetMinutes * 60_000L)
        dosDate = date
        dosTime = time
    }

    fun text(path: String, content: String) = bytes(path, content.encodeToByteArray())

    fun bytes(path: String, content: ByteArray) = stream(path) { it.write(content) }

    /** One entry whose bytes [write] produces through the [EntryOut] it is given. */
    fun stream(path: String, write: (EntryOut) -> Unit) {
        check(entries.size < 0xFFFF) { "too many entries for a ZIP without ZIP64" }
        val name = path.encodeToByteArray()
        val offset = sink.position
        sink.write(localHeader(name, crc = 0, size = 0))
        val out = EntryOut(sink)
        write(out)
        val size = out.count
        check(size <= MAX_32 && sink.position <= MAX_32) { "entry too large for a ZIP without ZIP64" }
        val patch = ByteArray(12)
        putInt(patch, 0, out.crc)
        putInt(patch, 4, size.toInt())
        putInt(patch, 8, size.toInt())
        sink.patch(offset + 14, patch)
        entries += Central(name, offset, out.crc, size)
    }

    /** Writes the central directory and its end record; nothing may be added afterwards. */
    fun finish() {
        val start = sink.position
        for (e in entries) {
            val h = ByteArray(46)
            putInt(h, 0, 0x02014b50)
            putShort(h, 4, VERSION)
            putShort(h, 6, VERSION)
            putShort(h, 8, UTF8_FLAG)
            putShort(h, 10, 0) // STORED
            putShort(h, 12, dosTime)
            putShort(h, 14, dosDate)
            putInt(h, 16, e.crc)
            putInt(h, 20, e.size.toInt())
            putInt(h, 24, e.size.toInt())
            putShort(h, 28, e.name.size)
            // Extra, comment, disk, internal and external attributes: all zero.
            putInt(h, 42, e.offset.toInt())
            sink.write(h)
            sink.write(e.name)
        }
        val size = sink.position - start
        check(sink.position <= MAX_32) { "archive too large for a ZIP without ZIP64" }
        val end = ByteArray(22)
        putInt(end, 0, 0x06054b50)
        putShort(end, 8, entries.size)
        putShort(end, 10, entries.size)
        putInt(end, 12, size.toInt())
        putInt(end, 16, start.toInt())
        sink.write(end)
    }

    private fun localHeader(name: ByteArray, crc: Int, size: Int): ByteArray {
        val h = ByteArray(30 + name.size)
        putInt(h, 0, 0x04034b50)
        putShort(h, 4, VERSION)
        putShort(h, 6, UTF8_FLAG)
        putShort(h, 8, 0) // STORED
        putShort(h, 10, dosTime)
        putShort(h, 12, dosDate)
        putInt(h, 14, crc)
        putInt(h, 18, size)
        putInt(h, 22, size)
        putShort(h, 26, name.size)
        name.copyInto(h, 30)
        return h
    }

    /** An entry's bytes on their way to the sink, counted and checksummed. */
    class EntryOut internal constructor(private val sink: CopySink) {
        internal var crc = 0
        internal var count = 0L

        fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
            crc = Crc32.update(crc, bytes, offset, length)
            count += length
            sink.write(bytes, offset, length)
        }
    }

    private companion object {
        const val VERSION = 20
        const val UTF8_FLAG = 0x0800
        const val MAX_32 = 0xFFFFFFFFL
    }
}

/**
 * UTF-8 text into a byte consumer, as an [Appendable] for the writers that stream (`HtmlWriter.write`), in chunks of
 * about [CHUNK] characters; a surrogate pair is never split between two chunks. [flush] at the end.
 */
class Utf8Out(private val out: (ByteArray) -> Unit) : Appendable {
    private val buffer = StringBuilder()

    override fun append(value: Char): Appendable {
        buffer.append(value)
        if (buffer.length >= CHUNK) drain()
        return this
    }

    override fun append(value: CharSequence?): Appendable {
        buffer.append(value ?: "null")
        if (buffer.length >= CHUNK) drain()
        return this
    }

    override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): Appendable =
        append((value ?: "null").subSequence(startIndex, endIndex))

    /** Writes out everything appended so far. */
    fun flush() {
        if (buffer.isNotEmpty()) out(buffer.toString().encodeToByteArray())
        buffer.clear()
    }

    /** Everything but a trailing high surrogate, whose low half is still to come. */
    private fun drain() {
        val keep = if (buffer.last().isHighSurrogate()) 1 else 0
        val end = buffer.length - keep
        out(buffer.substring(0, end).encodeToByteArray())
        buffer.deleteRange(0, end)
    }

    private companion object {
        const val CHUNK = 16 * 1024
    }
}

/** One entry of the central directory as its author wrote it: [size] and [compressedSize] are claims, not facts. */
class ZipEntryInfo internal constructor(
    val name: String,
    internal val method: Int,
    internal val flags: Int,
    internal val crc: Int,
    val compressedSize: Long,
    val size: Long,
    internal val localOffset: Long,
)

/** Why a file could not be opened as a ZIP. */
enum class ZipOpenProblem { NOT_A_ZIP, TOO_MANY_ENTRIES }

/**
 * Reads the ZIPs a backup comes in: the central directory first (random access through [RandomSource]), then one
 * entry at a time with [readAtMost], which counts what DEFLATE produces and checks the CRC. Entries that are encrypted,
 * compressed any other way or ZIP64 are unreadable (null), never guessed at.
 */
class ZipReader private constructor(private val source: RandomSource, val entries: List<ZipEntryInfo>) {
    private val byName = entries.associateBy { it.name }

    fun entry(name: String): ZipEntryInfo? = byName[name]

    /**
     * The entry's bytes, or null when they would be more than [limit]. The declared sizes are a first filter only;
     * [ArchiveTools.inflate] stops at [limit] whatever they say. Throws [IllegalArgumentException] for an entry that
     * cannot be read: encrypted, compressed another way, cut short, broken, or failing its CRC.
     */
    fun readAtMost(entry: ZipEntryInfo, limit: Long, tools: ArchiveTools): ByteArray? {
        require(entry.flags and 1 == 0) { "encrypted entry" }
        if (entry.size > limit) return null
        require(entry.compressedSize <= Int.MAX_VALUE) { "entry too large" }
        val cap = minOf(limit, Int.MAX_VALUE.toLong()).toInt()
        val header = requireNotNull(readFully(entry.localOffset, 30)) { "no local header" }
        require(intAt(header, 0) == 0x04034b50) { "no local header" }
        val start = entry.localOffset + 30 + shortAt(header, 26) + shortAt(header, 28)
        val packed = entry.compressedSize.toInt()
        val bytes = when (entry.method) {
            0 -> {
                require(entry.compressedSize == entry.size) { "stored sizes differ" }
                requireNotNull(readFully(start, packed)) { "entry cut short" }
            }
            // DEFLATE seldom grows data by more than a few bytes per 16 KiB block; anything far beyond the limit is
            // not an honest entry of that size.
            8 -> {
                if (entry.compressedSize > cap.toLong() + cap / 64 + 1024) return null
                tools.inflate(requireNotNull(readFully(start, packed)) { "entry cut short" }, cap) ?: return null
            }
            else -> throw IllegalArgumentException("compression method ${entry.method}")
        }
        if (bytes.size > cap) return null
        require(Crc32.of(bytes) == entry.crc) { "CRC mismatch" }
        return bytes
    }

    private fun readFully(position: Long, count: Int): ByteArray? = readFully(source, position, count)

    companion object {
        /** The central directory of 5 000 entries is well under a megabyte. */
        private const val MAX_DIRECTORY_BYTES = 8 * 1024 * 1024

        /** Opens [source] as a ZIP of at most [maxEntries] entries. */
        fun open(source: RandomSource, maxEntries: Int): ZipOpen {
            val size = source.size
            if (size < 22) return ZipOpen.Failed(ZipOpenProblem.NOT_A_ZIP)
            // The end record is the last 22 bytes, unless the archive has a comment (up to 64 KiB) after it.
            val tailSize = minOf(size, 22L + 0xFFFF).toInt()
            val tail = readFully(source, size - tailSize, tailSize) ?: return ZipOpen.Failed(ZipOpenProblem.NOT_A_ZIP)
            var at = tailSize - 22
            while (at >= 0 && intAt(tail, at) != 0x06054b50) at--
            if (at < 0) return ZipOpen.Failed(ZipOpenProblem.NOT_A_ZIP)
            val count = shortAt(tail, at + 10)
            val dirSize = uintAt(tail, at + 12)
            val dirStart = uintAt(tail, at + 16)
            // 0xFFFF entries or 0xFFFFFFFF offsets mean ZIP64, which no copy of ours needs.
            if (count == 0xFFFF || dirStart == 0xFFFFFFFFL) return ZipOpen.Failed(ZipOpenProblem.NOT_A_ZIP)
            if (count > maxEntries) return ZipOpen.Failed(ZipOpenProblem.TOO_MANY_ENTRIES)
            if (dirSize > MAX_DIRECTORY_BYTES || dirStart + dirSize > size) return ZipOpen.Failed(ZipOpenProblem.NOT_A_ZIP)
            val dir = readFully(source, dirStart, dirSize.toInt()) ?: return ZipOpen.Failed(ZipOpenProblem.NOT_A_ZIP)
            val entries = ArrayList<ZipEntryInfo>(count)
            var p = 0
            repeat(count) {
                if (p + 46 > dir.size || intAt(dir, p) != 0x02014b50) return ZipOpen.Failed(ZipOpenProblem.NOT_A_ZIP)
                val nameLength = shortAt(dir, p + 28)
                val extraLength = shortAt(dir, p + 30)
                val commentLength = shortAt(dir, p + 32)
                if (p + 46 + nameLength > dir.size) return ZipOpen.Failed(ZipOpenProblem.NOT_A_ZIP)
                entries += ZipEntryInfo(
                    name = dir.decodeToString(p + 46, p + 46 + nameLength),
                    method = shortAt(dir, p + 10),
                    flags = shortAt(dir, p + 8),
                    crc = intAt(dir, p + 16),
                    compressedSize = uintAt(dir, p + 20),
                    size = uintAt(dir, p + 24),
                    localOffset = uintAt(dir, p + 42),
                )
                p += 46 + nameLength + extraLength + commentLength
            }
            return ZipOpen.Ok(ZipReader(source, entries))
        }

        /** Exactly [count] bytes at [position], or null when the file ends first. */
        internal fun readFully(source: RandomSource, position: Long, count: Int): ByteArray? {
            if (count < 0 || position < 0 || position + count > source.size) return null
            val out = ByteArray(count)
            var done = 0
            while (done < count) {
                val read = source.readAt(position + done, out, done, count - done)
                if (read <= 0) return null
                done += read
            }
            return out
        }
    }
}

/** A [ZipReader] opened, or why the file could not be. */
sealed interface ZipOpen {
    class Ok(val reader: ZipReader) : ZipOpen
    class Failed(val problem: ZipOpenProblem) : ZipOpen
}

private fun putShort(b: ByteArray, at: Int, v: Int) {
    b[at] = v.toByte()
    b[at + 1] = (v ushr 8).toByte()
}

private fun putInt(b: ByteArray, at: Int, v: Int) {
    putShort(b, at, v and 0xFFFF)
    putShort(b, at + 2, v ushr 16)
}

private fun shortAt(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

private fun intAt(b: ByteArray, at: Int): Int = shortAt(b, at) or (shortAt(b, at + 2) shl 16)

private fun uintAt(b: ByteArray, at: Int): Long = intAt(b, at).toLong() and 0xFFFFFFFFL

/**
 * The MS-DOS date and time of [localMillis] (milliseconds since 1970 on the local clock), as ZIP headers carry them:
 * two-second resolution, years 1980 to 2107 (earlier is 1980-01-01).
 */
internal fun dosDateTime(localMillis: Long): Pair<Int, Int> {
    val days = localMillis.floorDiv(86_400_000L)
    val secondOfDay = (localMillis.mod(86_400_000L) / 1000).toInt()
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    val z = days + 719_468
    val era = z.floorDiv(146_097)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    if (year < 1980) return ((0 shl 9) or (1 shl 5) or 1) to 0
    val date = ((year.coerceAtMost(2107) - 1980) shl 9) or (month shl 5) or day
    val time = ((secondOfDay / 3600) shl 11) or (((secondOfDay / 60) % 60) shl 5) or ((secondOfDay % 60) / 2)
    return date to time
}
