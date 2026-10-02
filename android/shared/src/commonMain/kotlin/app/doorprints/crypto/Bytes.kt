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

import kotlin.io.encoding.Base64

/** Small, explicit byte helpers for the crypto code (big-endian throughout, as RFC 9180's I2OSP). */
internal object Bytes {
    fun concat(vararg parts: ByteArray): ByteArray {
        var size = 0
        for (p in parts) size += p.size
        val out = ByteArray(size)
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    /** I2OSP(value, length): [value] as [length] big-endian bytes; [value] must fit. */
    fun i2osp(value: Long, length: Int): ByteArray {
        require(length in 1..8) { "length" }
        require(value >= 0 && (length == 8 || value < (1L shl (8 * length)))) { "value does not fit" }
        val out = ByteArray(length)
        var v = value
        for (i in length - 1 downTo 0) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    fun u32(value: Long): ByteArray = i2osp(value, 4)

    fun utf8(text: String): ByteArray = text.encodeToByteArray()

    /** `u8(length) ‖ utf8(text)`: a label that cannot run into the field after it. */
    fun label(text: String): ByteArray {
        val b = utf8(text)
        require(b.size <= 255) { "label too long" }
        return concat(byteArrayOf(b.size.toByte()), b)
    }

    fun xor(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size)
        return ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }

    /** Standard base64 with padding (RFC 4648 §4), the only form the formats write. */
    fun b64(bytes: ByteArray): String = Base64.Default.encode(bytes)

    /**
     * Decodes [text] and checks that it is the canonical encoding of exactly [size] bytes (when [size] >= 0), so two
     * spellings of the same bytes never both pass.
     */
    fun unb64(text: String, size: Int = -1): ByteArray? {
        val bytes = try {
            Base64.Default.decode(text)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (size >= 0 && bytes.size != size) return null
        if (b64(bytes) != text) return null
        return bytes
    }

    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(digits[v ushr 4]).append(digits[v and 0xF])
        }
        return sb.toString()
    }

    fun unhex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex" }
        return ByteArray(text.length / 2) { i ->
            val hi = text[2 * i].digitToInt(16)
            val lo = text[2 * i + 1].digitToInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }
}

/** HKDF with SHA-256 (RFC 5869) over the provider's HMAC. */
internal class Hkdf(private val p: CryptoProvider) {
    /** HKDF-Extract; an empty [salt] is HashLen zero bytes (RFC 5869 §2.2), the same HMAC key by definition. */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        p.hmacSha256(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)

    /** HKDF-Expand to [length] bytes (at most 255 · 32). */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        if (length < 0 || length > 255 * HASH_LEN) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "HKDF length")
        if (prk.size < HASH_LEN) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "HKDF PRK too short")
        val out = ByteArray(length)
        var t = ByteArray(0)
        var at = 0
        var counter = 1
        while (at < length) {
            t = p.hmacSha256(prk, Bytes.concat(t, info, byteArrayOf(counter.toByte())))
            val n = minOf(HASH_LEN, length - at)
            t.copyInto(out, at, 0, n)
            at += n
            counter++
        }
        return out
    }

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)

    companion object {
        const val HASH_LEN = 32
    }
}
