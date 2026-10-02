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

package app.doorprints.crypto

/** Why a `dpx/1` file was refused; every kind is a refusal, none returns any plaintext of a failed file. */
class DpxException(val kind: Kind, message: String, val chunkIndex: Long = -1) : Exception("dpx ${kind.name}: $message") {
    enum class Kind {
        /** No `DPX1` magic: a plain (unencrypted) file, or not a Doorprints file at all (the downgrade case). */
        NOT_DPX,

        /** `DPX` followed by another version, or a header `v` other than 1: "update the app" (docs/schemas §1.1). */
        UNSUPPORTED_VERSION,

        /** A header `alg` other than `A256GCM-STREAM-64K`. */
        UNSUPPORTED_ALGORITHM,

        /** The header is not the canonical JSON of the `dpx/1` fields (unknown or missing field, a bad value). */
        HEADER_INVALID,

        /** The header's `inner` is not the format the caller expects (a sync file offered as a backup). */
        INNER_MISMATCH,

        /** The reader holds no folder key for the header's epoch. */
        UNKNOWN_EPOCH,

        /** The content key did not unwrap: a wrong folder key, or a changed epoch, kid, inner or wrap. */
        KEY_UNWRAP_FAILED,

        /** A chunk did not authenticate at its position: a flipped bit, a changed header, reordered or duplicated chunks, or a cut inside a chunk. */
        CHUNK_AUTH_FAILED,

        /** The file ends before its last chunk (cut at a chunk boundary, or inside the header). */
        TRUNCATED,

        /** Bytes follow the chunk marked last. */
        TRAILING_DATA,

        /** An empty last chunk after data: authentic but not what a writer makes, so refused. */
        NON_CANONICAL_CHUNKS,

        /** More plaintext than the caller allows, or more chunks than a 32-bit index. */
        TOO_LARGE,

        /** The decrypted bytes or the file's bytes are not the SHA-256 the caller expected. */
        CHECKSUM_MISMATCH,

        /** A `photo/1` file opened without the SHA-256 its photo row records (a swapped photo would go unnoticed). */
        CHECKSUM_REQUIRED,
    }
}

/** Where bytes come from: [read] fills up to [length] bytes and returns the count, or -1 at the end. */
fun interface ByteSource {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/** Where bytes go. */
fun interface ByteSink {
    fun write(buffer: ByteArray, offset: Int, length: Int)
}

/** The parsed `dpx/1` header (docs/15 §9.6); [bytes] is the exact prefix every chunk authenticates. */
class DpxHeader internal constructor(
    val epoch: Int,
    /** The writing device's kid (16 bytes). */
    val kid: ByteArray,
    internal val wrapNonce: ByteArray,
    internal val wrappedKey: ByteArray,
    internal val noncePrefix: ByteArray,
    val inner: String,
) {
    /** `"DPX1" ‖ u16 length ‖ canonical JSON`. */
    val bytes: ByteArray by lazy { Dpx.frame(json()) }

    internal fun json(): ByteArray = CanonicalJson()
        .raw("{\"v\":").number(Dpx.VERSION.toLong())
        .raw(",\"alg\":").string(Dpx.ALG)
        .raw(",\"epoch\":").number(epoch.toLong())
        .raw(",\"kid\":").string(Bytes.b64(kid))
        .raw(",\"wrappedKey\":{\"nonce\":").string(Bytes.b64(wrapNonce))
        .raw(",\"ct\":").string(Bytes.b64(wrappedKey))
        .raw("},\"noncePrefix\":").string(Bytes.b64(noncePrefix))
        .raw(",\"chunkSize\":").number(Dpx.CHUNK_SIZE.toLong())
        .raw(",\"inner\":").string(inner)
        .raw("}")
        .bytes()
}

/** What [Dpx.encrypt] wrote. [ciphertextSha256] is what Drive's `sha256Checksum` must equal (docs/15 §9.6). */
class DpxWritten(val header: DpxHeader, val plaintextSize: Long, val fileSize: Long, val plaintextSha256: ByteArray, val ciphertextSha256: ByteArray)

/** What [Dpx.decrypt] read; only returned when every chunk and the end of the file checked out. */
class DpxRead(val header: DpxHeader, val plaintextSize: Long, val fileSize: Long, val plaintextSha256: ByteArray, val ciphertextSha256: ByteArray)

/**
 * The `dpx/1` envelope (docs/15 §9.6, §9.7), byte for byte the same as the website's `dpx.ts`:
 *
 * ```
 * "DPX1"                      4 bytes, 44 50 58 31
 * header length               2 bytes, big-endian, 1..4096
 * header                      canonical JSON: {"v":1,"alg":"A256GCM-STREAM-64K","epoch":E,"kid":B64(16),
 *                             "wrappedKey":{"nonce":B64(12),"ct":B64(48)},"noncePrefix":B64(7),
 *                             "chunkSize":65536,"inner":"…"}
 * chunk 0 … chunk k           AES-256-GCM under the file's content key, each 65536 plaintext bytes + 16 tag
 *                             bytes, the last one 0..65536 + 16 (empty only when the whole file is empty)
 *   nonce(i)                  noncePrefix(7) ‖ u32 i ‖ u8 last (1 on the last chunk, else 0)
 *   aad(i)                    header bytes (from "DPX1" on) ‖ u32 i ‖ u8 last
 * ```
 *
 * The content key is 32 random bytes, new for every file and every rewrite, wrapped in the header with AES-256-GCM
 * under [FolderKey.contentWrapKey] of the epoch's folder key, a random 96-bit nonce and the AAD
 * [WrapAad.contentKey] (epoch, writer kid, inner). `noncePrefix` is random per file; with a fresh content key per file
 * no nonce can repeat under a key.
 *
 * Streaming: [encrypt] and [decrypt] hold two chunks at a time. [decrypt] writes each chunk to the sink once it has
 * authenticated, but the file as a whole is only proven complete when it returns: a caller must treat what it wrote as
 * unconfirmed (a temporary file) until then. [encryptBytes] and [decryptBytes] are the in-memory form.
 */
class Dpx(private val p: CryptoProvider) {

    /**
     * Encrypts [source] for the folder key of [epoch]; fresh content key, wrap nonce and nonce prefix. The length is
     * not known in advance, so a [DpxException.Kind.TOO_LARGE] (or any failure of [source] or [sink]) can leave a
     * partial file on [sink]: the caller writes to a temporary place (Drive's `partial-` name, a resumable session)
     * and discards it on any exception.
     */
    fun encrypt(
        folderKey: ByteArray,
        epoch: Int,
        writerKid: ByteArray,
        inner: String,
        source: ByteSource,
        sink: ByteSink,
        maxPlaintext: Long = DEFAULT_MAX_PLAINTEXT,
    ): DpxWritten {
        val contentKey = p.randomBytes(32)
        try {
            return encryptWith(
                folderKey, epoch, writerKid, inner, source, sink, maxPlaintext,
                contentKey = contentKey, wrapNonce = p.randomBytes(12), noncePrefix = p.randomBytes(NONCE_PREFIX),
            )
        } finally {
            contentKey.fill(0)
        }
    }

    /** [encrypt] with the random values given: the vectors' deterministic mode. Never call it with reused values. */
    internal fun encryptWith(
        folderKey: ByteArray,
        epoch: Int,
        writerKid: ByteArray,
        inner: String,
        source: ByteSource,
        sink: ByteSink,
        maxPlaintext: Long,
        contentKey: ByteArray,
        wrapNonce: ByteArray,
        noncePrefix: ByteArray,
    ): DpxWritten {
        require(epoch in 1..MAX_EPOCH) { "epoch" }
        require(writerKid.size == WrapAad.KID_SIZE) { "kid" }
        require(INNER.matches(inner)) { "inner" }
        require(contentKey.size == 32 && wrapNonce.size == 12 && noncePrefix.size == NONCE_PREFIX)
        val wrapped = p.aesGcmSeal(FolderKey.contentWrapKey(p, folderKey), wrapNonce, WrapAad.contentKey(epoch, writerKid, inner), contentKey)
        val header = DpxHeader(epoch, writerKid.copyOf(), wrapNonce.copyOf(), wrapped, noncePrefix.copyOf(), inner)
        val headerBytes = header.bytes
        val key = p.aesKey(contentKey)
        val ptHash = p.sha256()
        val ctHash = p.sha256()
        var fileSize = 0L
        fun out(b: ByteArray) {
            sink.write(b, 0, b.size)
            ctHash.update(b)
            fileSize += b.size
        }
        out(headerBytes)

        var total = 0L
        var index = 0L
        var cur = readUpTo(source, CHUNK_SIZE)
        while (true) {
            val next = if (cur.size == CHUNK_SIZE) readUpTo(source, CHUNK_SIZE) else ByteArray(0)
            val last = next.isEmpty()
            total += cur.size
            if (total > maxPlaintext) throw DpxException(DpxException.Kind.TOO_LARGE, "plaintext over the limit")
            if (index > MAX_INDEX) throw DpxException(DpxException.Kind.TOO_LARGE, "more chunks than the index holds")
            ptHash.update(cur)
            out(p.aesGcmSeal(key, nonce(noncePrefix, index, last), aad(headerBytes, index, last), cur))
            if (last) break
            cur = next
            index++
        }
        return DpxWritten(header, total, fileSize, ptHash.digest(), ctHash.digest())
    }

    /**
     * Decrypts a `dpx/1` file. [headerCheck] sees the header (not yet authenticated) before any key is used (the
     * [RevokedEpochRule] and the sync file's writer check go there) and may throw to refuse it.
     * [expectedPlaintextSha256] and [expectedCiphertextSha256], when given, are checked at the end
     * ([DpxException.Kind.CHECKSUM_MISMATCH]). **A `photo/1` file needs [expectedPlaintextSha256]** (the photo row's
     * SHA-256, [DpxException.Kind.CHECKSUM_REQUIRED] otherwise): every photo is a valid file under the same folder key,
     * so only the row's hash binds the file to the row (a photo file swapped for another is refused).
     */
    fun decrypt(
        keys: FolderKeys,
        expectedInner: String,
        source: ByteSource,
        sink: ByteSink,
        maxPlaintext: Long = DEFAULT_MAX_PLAINTEXT,
        expectedPlaintextSha256: ByteArray? = null,
        expectedCiphertextSha256: ByteArray? = null,
        headerCheck: (DpxHeader) -> Unit = {},
    ): DpxRead {
        val ctHash = p.sha256()
        var fileSize = 0L
        val counted = ByteSource { b, o, l ->
            val n = source.read(b, o, l)
            if (n > 0) {
                ctHash.update(b, o, n)
                fileSize += n
            }
            n
        }
        val header = readHeader(counted)
        if (header.inner != expectedInner) throw DpxException(DpxException.Kind.INNER_MISMATCH, "inner format")
        if (header.inner == PHOTO && expectedPlaintextSha256 == null) throw DpxException(DpxException.Kind.CHECKSUM_REQUIRED, "a photo needs its row's SHA-256")
        headerCheck(header)
        val folderKey = keys.folderKey(header.epoch) ?: throw DpxException(DpxException.Kind.UNKNOWN_EPOCH, "epoch ${header.epoch}")
        val contentKey = try {
            p.aesGcmOpen(FolderKey.contentWrapKey(p, folderKey), header.wrapNonce, WrapAad.contentKey(header.epoch, header.kid, header.inner), header.wrappedKey)
        } catch (_: CryptoException) {
            throw DpxException(DpxException.Kind.KEY_UNWRAP_FAILED, "content key")
        } finally {
            folderKey.fill(0)
        }
        val key = p.aesKey(contentKey)
        contentKey.fill(0)
        val headerBytes = header.bytes
        val ptHash = p.sha256()
        val full = CHUNK_SIZE + TAG

        var total = 0L
        var index = 0L
        var cur = readUpTo(counted, full)
        if (cur.isEmpty()) throw DpxException(DpxException.Kind.TRUNCATED, "no chunk")
        while (true) {
            if (index > MAX_INDEX) throw DpxException(DpxException.Kind.TOO_LARGE, "more chunks than the index holds")
            // A short block can only be the last: readUpTo stops early only at the end of the source.
            val next = if (cur.size == full) readUpTo(counted, full) else ByteArray(0)
            val last = next.isEmpty()
            if (cur.size < TAG) throw DpxException(DpxException.Kind.TRUNCATED, "chunk shorter than a tag", index)
            val pt = openChunk(key, header, headerBytes, index, last, cur)
                ?: throw diagnose(key, header, headerBytes, index, last, cur)
            if (last && pt.isEmpty() && index > 0) throw DpxException(DpxException.Kind.NON_CANONICAL_CHUNKS, "empty last chunk", index)
            total += pt.size
            if (total > maxPlaintext) throw DpxException(DpxException.Kind.TOO_LARGE, "plaintext over the limit", index)
            ptHash.update(pt)
            sink.write(pt, 0, pt.size)
            if (last) break
            cur = next
            index++
        }
        val read = DpxRead(header, total, fileSize, ptHash.digest(), ctHash.digest())
        if (expectedPlaintextSha256 != null && !constantTimeEquals(expectedPlaintextSha256, read.plaintextSha256)) {
            throw DpxException(DpxException.Kind.CHECKSUM_MISMATCH, "plaintext SHA-256")
        }
        if (expectedCiphertextSha256 != null && !constantTimeEquals(expectedCiphertextSha256, read.ciphertextSha256)) {
            throw DpxException(DpxException.Kind.CHECKSUM_MISMATCH, "file SHA-256")
        }
        return read
    }

    private fun openChunk(key: AesKey, h: DpxHeader, headerBytes: ByteArray, index: Long, last: Boolean, block: ByteArray): ByteArray? =
        try {
            p.aesGcmOpen(key, nonce(h.noncePrefix, index, last), aad(headerBytes, index, last), block)
        } catch (_: CryptoException) {
            null
        }

    /**
     * A chunk failed at its position. Tells the two cases the last flag can prove apart: a block that opens as the
     * last chunk while more bytes follow (TRAILING_DATA), and a full block at the end that opens as a middle chunk
     * (TRUNCATED at a chunk boundary). Anything else is CHUNK_AUTH_FAILED.
     */
    private fun diagnose(key: AesKey, h: DpxHeader, headerBytes: ByteArray, index: Long, last: Boolean, block: ByteArray): DpxException {
        val asOther = openChunk(key, h, headerBytes, index, !last, block)
        return when {
            asOther != null && !last -> DpxException(DpxException.Kind.TRAILING_DATA, "bytes after the last chunk", index)
            asOther != null && last -> DpxException(DpxException.Kind.TRUNCATED, "file ends after a middle chunk", index)
            else -> DpxException(DpxException.Kind.CHUNK_AUTH_FAILED, "chunk $index", index)
        }
    }

    /** Encrypts bytes in memory. */
    fun encryptBytes(folderKey: ByteArray, epoch: Int, writerKid: ByteArray, inner: String, plaintext: ByteArray): Pair<ByteArray, DpxWritten> {
        val out = Buffer()
        val w = encrypt(folderKey, epoch, writerKid, inner, sourceOf(plaintext), out, Long.MAX_VALUE)
        return out.toByteArray() to w
    }

    /** Decrypts bytes in memory; returns the plaintext only when the whole file checked out. */
    fun decryptBytes(
        keys: FolderKeys,
        expectedInner: String,
        file: ByteArray,
        maxPlaintext: Long = DEFAULT_MAX_PLAINTEXT,
        expectedPlaintextSha256: ByteArray? = null,
        headerCheck: (DpxHeader) -> Unit = {},
    ): Pair<ByteArray, DpxRead> {
        val out = Buffer()
        val r = decrypt(keys, expectedInner, sourceOf(file), out, maxPlaintext, expectedPlaintextSha256, headerCheck = headerCheck)
        return out.toByteArray() to r
    }

    /** Reads and checks the header only (for a listing that wants the epoch and writer before downloading the rest). */
    fun readHeader(source: ByteSource): DpxHeader {
        val magic = readUpTo(source, 4)
        if (magic.size < 4) {
            if (magic.isNotEmpty() && MAGIC.copyOfRange(0, magic.size).contentEquals(magic)) {
                throw DpxException(DpxException.Kind.TRUNCATED, "inside the magic")
            }
            throw DpxException(DpxException.Kind.NOT_DPX, "no DPX1 magic")
        }
        if (!magic.contentEquals(MAGIC)) {
            if (magic.copyOfRange(0, 3).contentEquals(MAGIC.copyOfRange(0, 3))) {
                throw DpxException(DpxException.Kind.UNSUPPORTED_VERSION, "DPX version")
            }
            throw DpxException(DpxException.Kind.NOT_DPX, "no DPX1 magic")
        }
        val lenBytes = readUpTo(source, 2)
        if (lenBytes.size < 2) throw DpxException(DpxException.Kind.TRUNCATED, "inside the header length")
        val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
        if (len < 1 || len > MAX_HEADER) throw DpxException(DpxException.Kind.HEADER_INVALID, "header length $len")
        val json = readUpTo(source, len)
        if (json.size < len) throw DpxException(DpxException.Kind.TRUNCATED, "inside the header")
        return parseHeader(json)
    }

    private fun parseHeader(json: ByteArray): DpxHeader {
        fun bad(why: String): Nothing = throw DpxException(DpxException.Kind.HEADER_INVALID, why)
        val root = CanonicalJson.parse(json) as? kotlinx.serialization.json.JsonObject ?: bad("not a JSON object")
        // The version first, so a dpx/2 header with other fields says "update the app".
        val v = JsonRead.long(root["v"], 0, CanonicalJson.MAX_SAFE) ?: bad("v")
        if (v != VERSION.toLong()) throw DpxException(DpxException.Kind.UNSUPPORTED_VERSION, "v=$v")
        val alg = JsonRead.string(root["alg"]) ?: bad("alg")
        if (alg != ALG) throw DpxException(DpxException.Kind.UNSUPPORTED_ALGORITHM, "alg")
        JsonRead.obj(root, "v", "alg", "epoch", "kid", "wrappedKey", "noncePrefix", "chunkSize", "inner") ?: bad("fields")
        val epoch = JsonRead.long(root["epoch"], 1, MAX_EPOCH.toLong())?.toInt() ?: bad("epoch")
        val kid = JsonRead.string(root["kid"])?.let { Bytes.unb64(it, WrapAad.KID_SIZE) } ?: bad("kid")
        val wk = JsonRead.obj(root["wrappedKey"], "nonce", "ct") ?: bad("wrappedKey")
        val wrapNonce = JsonRead.string(wk["nonce"])?.let { Bytes.unb64(it, 12) } ?: bad("wrappedKey.nonce")
        val wrapped = JsonRead.string(wk["ct"])?.let { Bytes.unb64(it, 48) } ?: bad("wrappedKey.ct")
        val prefix = JsonRead.string(root["noncePrefix"])?.let { Bytes.unb64(it, NONCE_PREFIX) } ?: bad("noncePrefix")
        val chunkSize = JsonRead.long(root["chunkSize"], 0, CanonicalJson.MAX_SAFE) ?: bad("chunkSize")
        if (chunkSize != CHUNK_SIZE.toLong()) bad("chunkSize")
        val inner = JsonRead.string(root["inner"])?.takeIf { INNER.matches(it) } ?: bad("inner")
        val header = DpxHeader(epoch, kid, wrapNonce, wrapped, prefix, inner)
        if (!header.json().contentEquals(json)) bad("not canonical")
        return header
    }

    private class Buffer : ByteSink {
        private var buf = ByteArray(1024)
        private var size = 0
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (size + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + length))
            buffer.copyInto(buf, size, offset, offset + length)
            size += length
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)
    }

    companion object {
        const val VERSION = 1
        const val ALG = "A256GCM-STREAM-64K"
        const val CHUNK_SIZE = 65536
        const val TAG = 16
        const val NONCE_PREFIX = 7
        const val MAX_HEADER = 4096
        const val MAX_EPOCH = Int.MAX_VALUE

        /** The default plaintext limit: 4 GiB (a full backup with photos, by hand; docs/15 §5.1). */
        const val DEFAULT_MAX_PLAINTEXT = 4L * 1024 * 1024 * 1024
        internal const val MAX_INDEX = 0xFFFFFFFFL
        internal val MAGIC = byteArrayOf(0x44, 0x50, 0x58, 0x31)

        /** The photo format: decrypting it needs the photo row's SHA-256. */
        const val PHOTO = "photo/1"

        /** `inner`: a format name and number (`doorprints-backup/2`, `sync/1`, `photo/1`). */
        internal val INNER = Regex("^[a-z][a-z0-9-]{0,39}/[1-9][0-9]{0,3}$")

        internal fun frame(json: ByteArray): ByteArray {
            require(json.size in 1..MAX_HEADER)
            return Bytes.concat(MAGIC, byteArrayOf((json.size ushr 8).toByte(), json.size.toByte()), json)
        }

        internal fun nonce(prefix: ByteArray, index: Long, last: Boolean): ByteArray =
            Bytes.concat(prefix, Bytes.u32(index), byteArrayOf(if (last) 1 else 0))

        internal fun aad(header: ByteArray, index: Long, last: Boolean): ByteArray =
            Bytes.concat(header, Bytes.u32(index), byteArrayOf(if (last) 1 else 0))

        /** Reads until [n] bytes or the end of [source]. */
        internal fun readUpTo(source: ByteSource, n: Int): ByteArray {
            val buf = ByteArray(n)
            var at = 0
            while (at < n) {
                val r = source.read(buf, at, n - at)
                if (r < 0) break
                check(r > 0) { "a ByteSource returned 0" }
                at += r
            }
            return if (at == n) buf else buf.copyOf(at)
        }

        fun sourceOf(bytes: ByteArray): ByteSource {
            var at = 0
            return ByteSource { b, o, l ->
                if (at >= bytes.size) {
                    -1
                } else {
                    val n = minOf(l, bytes.size - at)
                    bytes.copyInto(b, o, at, at + n)
                    at += n
                    n
                }
            }
        }
    }
}
