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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The `dpx/1` envelope: round trips, every tamper, truncation, reordering, downgrade (TC-U-128). */
class DpxTest {
    private val p = JvmCryptoProvider
    private val dpx = Dpx(p)
    private val folderKey = p.randomBytes(32)
    private val epoch = 4
    private val kid = p.randomBytes(16)
    private val keys = FolderKeys { if (it == epoch) folderKey else null }
    private val c = Dpx.CHUNK_SIZE
    private val full = c + Dpx.TAG

    private fun enc(pt: ByteArray, inner: String = "sync/1") = dpx.encryptBytes(folderKey, epoch, kid, inner, pt).first
    private fun dec(file: ByteArray, k: FolderKeys = keys, inner: String = "sync/1") = dpx.decryptBytes(k, inner, file).first
    private fun headerLen(file: ByteArray) = 6 + (((file[4].toInt() and 0xFF) shl 8) or (file[5].toInt() and 0xFF))

    private fun expect(kind: DpxException.Kind, block: () -> Unit): DpxException {
        try {
            block()
        } catch (e: DpxException) {
            assertEquals(e.message, kind, e.kind)
            return e
        }
        fail("expected $kind")
        throw AssertionError()
    }

    private val sizes = listOf(0, 1, 100, c - 1, c, c + 1, 2 * c, 3 * c + 5)

    @Test
    fun roundTripsEverySizeAndEveryReadPattern() {
        for (size in sizes) {
            val pt = p.randomBytes(size)
            val file = enc(pt)
            val chunks = if (size == 0) 1 else (size + c - 1) / c
            assertEquals(size.toLong() + headerLen(file) + 16L * chunks, file.size.toLong())
            assertArrayEquals(pt, dec(file))
            // Streaming with tiny reads on both sides gives the same bytes.
            val sink = CollectingSink()
            val read = dpx.decrypt(keys, "sync/1", trickle(file, 7), sink)
            assertArrayEquals(pt, sink.bytes())
            assertArrayEquals(sha256(p, pt), read.plaintextSha256)
            assertArrayEquals(sha256(p, file), read.ciphertextSha256)
            assertEquals(file.size.toLong(), read.fileSize)
            val sink2 = CollectingSink()
            val w = dpx.encrypt(folderKey, epoch, kid, "sync/1", trickle(pt, 1000), sink2)
            assertArrayEquals(pt, dec(sink2.bytes()))
            assertArrayEquals(sha256(p, sink2.bytes()), w.ciphertextSha256)
            assertArrayEquals(sha256(p, pt), w.plaintextSha256)
        }
    }

    @Test
    fun aContentKeyAndNoncePrefixAreNeverReused() {
        val pt = ByteArray(10)
        val a = dpx.readHeader(Dpx.sourceOf(enc(pt)))
        val b = dpx.readHeader(Dpx.sourceOf(enc(pt)))
        assertFalse(a.wrappedKey.contentEquals(b.wrappedKey))
        assertFalse(a.wrapNonce.contentEquals(b.wrapNonce))
        assertFalse(a.noncePrefix.contentEquals(b.noncePrefix))
    }

    @Test
    fun everyHeaderByteIsAuthenticated() {
        val file = enc(p.randomBytes(c + 10))
        val h = headerLen(file)
        for (i in 0 until h) {
            for (flip in intArrayOf(0x01, 0x80)) {
                val bad = file.copyOf().also { it[i] = (it[i].toInt() xor flip).toByte() }
                try {
                    dec(bad)
                    fail("header byte $i accepted")
                } catch (_: DpxException) {
                    // Any refusal; which one depends on the byte (magic, length, JSON, a field, or the AAD).
                }
            }
        }
    }

    @Test
    fun headerFieldChangesHaveTheirOwnKinds() {
        val file = enc(ByteArray(5))
        val h = headerLen(file)
        val json = file.copyOfRange(6, h).decodeToString()
        fun with(newJson: String): ByteArray {
            val b = newJson.encodeToByteArray()
            return Dpx.frame(b) + file.copyOfRange(h, file.size)
        }
        val otherKid = Bytes.b64(p.randomBytes(16))
        val kidField = Regex("\"kid\":\"[^\"]+\"")
        expect(DpxException.Kind.KEY_UNWRAP_FAILED) { dec(with(json.replace(kidField, "\"kid\":\"$otherKid\""))) }
        // Another epoch the reader holds: the wrap's AAD names epoch 4, so it does not open.
        val both = FolderKeys { if (it == epoch || it == 5) folderKey else null }
        expect(DpxException.Kind.KEY_UNWRAP_FAILED) { dec(with(json.replace("\"epoch\":4", "\"epoch\":5")), both) }
        expect(DpxException.Kind.UNKNOWN_EPOCH) { dec(with(json.replace("\"epoch\":4", "\"epoch\":6"))) }
        expect(DpxException.Kind.INNER_MISMATCH) { dec(with(json.replace("sync/1", "photo/1"))) }
        expect(DpxException.Kind.INNER_MISMATCH) { dec(file, inner = "doorprints-backup/2") }
        expect(DpxException.Kind.UNSUPPORTED_VERSION) { dec(with(json.replace("\"v\":1", "\"v\":2"))) }
        expect(DpxException.Kind.UNSUPPORTED_ALGORITHM) { dec(with(json.replace("A256GCM-STREAM-64K", "A128GCM-STREAM-64K"))) }
        expect(DpxException.Kind.HEADER_INVALID) { dec(with(json.replace("65536", "4096"))) }
        expect(DpxException.Kind.HEADER_INVALID) { dec(with(json.replace("{\"v\":1,", "{ \"v\":1,"))) }
        expect(DpxException.Kind.HEADER_INVALID) { dec(with(json.dropLast(1) + ",\"flags\":0}")) }
        expect(DpxException.Kind.HEADER_INVALID) { dec(with(json.replace(",\"inner\":\"sync/1\"}", ",\"inner\":\"sync/1\",\"shareWraps\":[]}"))) }
        expect(DpxException.Kind.HEADER_INVALID) { dec(with(json.replace("\"v\":1,", "\"v\":1,\"v\":1,"))) }
        expect(DpxException.Kind.HEADER_INVALID) { dec(with(json.replace("\"epoch\":4", "\"epoch\":4.0"))) }
        expect(DpxException.Kind.HEADER_INVALID) { dec(with("[]")) }
        expect(DpxException.Kind.HEADER_INVALID) { dec(Dpx.MAGIC + byteArrayOf(0, 0) + file.copyOfRange(h, file.size)) }
        expect(DpxException.Kind.HEADER_INVALID) { dec(Dpx.MAGIC + byteArrayOf(0x10, 0x01) + ByteArray(5000)) }
        // Header swapped with another file's: its content key does not open these chunks.
        val other = enc(ByteArray(5))
        val swapped = other.copyOfRange(0, headerLen(other)) + file.copyOfRange(h, file.size)
        assertEquals(0L, expect(DpxException.Kind.CHUNK_AUTH_FAILED) { dec(swapped) }.chunkIndex)
    }

    @Test
    fun everyChunkByteClassIsAuthenticated() {
        val pt = p.randomBytes(3 * c + 50)
        val file = enc(pt)
        val h = headerLen(file)
        for (chunk in 0 until 4) {
            val start = h + chunk * full
            val end = minOf(start + full, file.size)
            for (pos in listOf(start, start + 1, (start + end) / 2, end - 17, end - 16, end - 1)) {
                val bad = file.copyOf().also { it[pos] = (it[pos].toInt() xor 0x04).toByte() }
                assertEquals(chunk.toLong(), expect(DpxException.Kind.CHUNK_AUTH_FAILED) { dec(bad) }.chunkIndex)
            }
        }
    }

    @Test
    fun truncationAtEveryBoundaryAndInsideChunks() {
        val file = enc(p.randomBytes(3 * c + 50))
        val h = headerLen(file)
        expect(DpxException.Kind.NOT_DPX) { dec(ByteArray(0)) }
        expect(DpxException.Kind.TRUNCATED) { dec(file.copyOf(2)) }
        expect(DpxException.Kind.TRUNCATED) { dec(file.copyOf(5)) }
        expect(DpxException.Kind.TRUNCATED) { dec(file.copyOf(h - 1)) }
        expect(DpxException.Kind.TRUNCATED) { dec(file.copyOf(h)) }
        for (k in 1..3) expect(DpxException.Kind.TRUNCATED) { dec(file.copyOf(h + k * full)) }
        for (cut in listOf(h + 1, h + 15, h + 16, h + full / 2, h + full + 1, h + 2 * full + 100, file.size - 1)) {
            try {
                dec(file.copyOf(cut))
                fail("cut at $cut accepted")
            } catch (e: DpxException) {
                assertTrue(e.kind in setOf(DpxException.Kind.TRUNCATED, DpxException.Kind.CHUNK_AUTH_FAILED))
            }
        }
        // A file that is exactly a multiple of the chunk size, cut after its first chunk.
        val exact = enc(p.randomBytes(2 * c))
        expect(DpxException.Kind.TRUNCATED) { dec(exact.copyOf(headerLen(exact) + full)) }
    }

    @Test
    fun reorderedDuplicatedAndExtraChunksAreRefused() {
        val file = enc(p.randomBytes(3 * c + 50))
        val h = headerLen(file)
        val head = file.copyOfRange(0, h)
        val ch = (0 until 4).map { file.copyOfRange(h + it * full, minOf(h + (it + 1) * full, file.size)) }
        fun join(vararg parts: ByteArray) = Bytes.concat(head, *parts)
        assertEquals(0L, expect(DpxException.Kind.CHUNK_AUTH_FAILED) { dec(join(ch[1], ch[0], ch[2], ch[3])) }.chunkIndex)
        assertEquals(1L, expect(DpxException.Kind.CHUNK_AUTH_FAILED) { dec(join(ch[0], ch[0], ch[2], ch[3])) }.chunkIndex)
        assertEquals(2L, expect(DpxException.Kind.CHUNK_AUTH_FAILED) { dec(join(ch[0], ch[1], ch[1], ch[2], ch[3])) }.chunkIndex)
        // The last chunk moved forward: it opens only as the last chunk of its own index.
        expect(DpxException.Kind.CHUNK_AUTH_FAILED) { dec(join(ch[0], ch[1], ch[3])) }
        // A full-size last chunk followed by more bytes.
        val exact = enc(p.randomBytes(2 * c))
        expect(DpxException.Kind.TRAILING_DATA) { dec(exact + ByteArray(1)) }
        expect(DpxException.Kind.TRAILING_DATA) { dec(exact + exact.copyOfRange(headerLen(exact), headerLen(exact) + full)) }
        // After a short last chunk extra bytes run into it, so its tag fails.
        expect(DpxException.Kind.CHUNK_AUTH_FAILED) { dec(file + ByteArray(1)) }
        // A chunk from another file with the same header bytes is impossible (fresh key); one from another file
        // under its own header fails at that position.
        val other = enc(p.randomBytes(3 * c + 50))
        val oh = headerLen(other)
        assertEquals(1L, expect(DpxException.Kind.CHUNK_AUTH_FAILED) { dec(join(ch[0], other.copyOfRange(oh + full, oh + 2 * full), ch[2], ch[3])) }.chunkIndex)
    }

    @Test
    fun plainFilesAndOtherVersionsAreRefused() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(100)
        expect(DpxException.Kind.NOT_DPX) { dec(zip) }
        expect(DpxException.Kind.NOT_DPX) { dec("{\"format\":\"doorprints-backup/2\"}".encodeToByteArray()) }
        expect(DpxException.Kind.TRUNCATED) { dec(byteArrayOf(0x44)) }
        val file = enc(ByteArray(3))
        expect(DpxException.Kind.UNSUPPORTED_VERSION) { dec(file.copyOf().also { it[3] = 0x32 }) }
    }

    @Test
    fun theWrongKeyOrNoKeyIsRefused() {
        val file = enc(ByteArray(3))
        expect(DpxException.Kind.KEY_UNWRAP_FAILED) { dec(file, FolderKeys { if (it == epoch) p.randomBytes(32) else null }) }
        expect(DpxException.Kind.UNKNOWN_EPOCH) { dec(file, FolderKeys { null }) }
    }

    @Test
    fun limitsChecksumsAndTheHeaderCheckAreEnforced() {
        val pt = p.randomBytes(c + 1)
        val file = enc(pt)
        expect(DpxException.Kind.TOO_LARGE) { dpx.decryptBytes(keys, "sync/1", file, maxPlaintext = c.toLong()) }
        expect(DpxException.Kind.TOO_LARGE) { dpx.encrypt(folderKey, epoch, kid, "sync/1", Dpx.sourceOf(pt), CollectingSink(), maxPlaintext = 10) }
        val sha = sha256(p, pt)
        dpx.decrypt(keys, "sync/1", Dpx.sourceOf(file), CollectingSink(), expectedPlaintextSha256 = sha, expectedCiphertextSha256 = sha256(p, file))
        expect(DpxException.Kind.CHECKSUM_MISMATCH) { dpx.decrypt(keys, "sync/1", Dpx.sourceOf(file), CollectingSink(), expectedPlaintextSha256 = ByteArray(32)) }
        expect(DpxException.Kind.CHECKSUM_MISMATCH) { dpx.decrypt(keys, "sync/1", Dpx.sourceOf(file), CollectingSink(), expectedCiphertextSha256 = sha) }
        var seen: DpxHeader? = null
        dpx.decryptBytes(keys, "sync/1", file) { seen = it }
        assertEquals(epoch, seen!!.epoch)
        assertArrayEquals(kid, seen!!.kid)
        try {
            dpx.decryptBytes(keys, "sync/1", file) { throw IllegalStateException("refused by the rule") }
            fail()
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun anEmptyLastChunkAfterDataIsRefused() {
        // Built by hand: a writer never makes it (a full chunk is the last one when the size is a multiple).
        val contentKey = p.randomBytes(32)
        val wrapNonce = p.randomBytes(12)
        val prefix = p.randomBytes(7)
        val sink = CollectingSink()
        dpx.encryptWith(folderKey, epoch, kid, "sync/1", Dpx.sourceOf(ByteArray(0)), sink, Long.MAX_VALUE, contentKey, wrapNonce, prefix)
        val header = dpx.readHeader(Dpx.sourceOf(sink.bytes()))
        val k = p.aesKey(contentKey)
        val first = p.aesGcmSeal(k, Dpx.nonce(prefix, 0, false), Dpx.aad(header.bytes, 0, false), ByteArray(c))
        val last = p.aesGcmSeal(k, Dpx.nonce(prefix, 1, true), Dpx.aad(header.bytes, 1, true), ByteArray(0))
        expect(DpxException.Kind.NON_CANONICAL_CHUNKS) { dec(header.bytes + first + last) }
    }
}
