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

/**
 * AES-GCM (NIST SP 800-38D) over a block cipher in ECB mode, for a 96-bit nonce and a 128-bit tag. CommonCrypto's
 * public API has no GCM (`kCCModeGCM` and `CCCryptorGCM*` are SPI, not in the SDK headers Kotlin/Native imports) and
 * Security.framework has none, so the iPhone gets GCM as the platform's AES (CommonCrypto, hardware accelerated, no
 * lookup tables in our code) for the counter blocks and the tag mask, plus GHASH here. GHASH multiplies in GF(2¹²⁸)
 * with masks and no table, so nothing is indexed by the hash key or by the data; its inputs, the AAD and the
 * ciphertext, are public anyway and only the hash subkey H = E_K(0) is secret.
 *
 * The tag is checked in constant time **before** any keystream is applied: [open] never produces plaintext for a
 * message that does not authenticate.
 */
internal class Gcm(private val ecb: BlockEncryptor) {
    /** Encrypts [blocks] (a multiple of 16 bytes) with the AES key, each block on its own (ECB). */
    fun interface BlockEncryptor {
        fun encryptBlocks(blocks: ByteArray): ByteArray
    }

    fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        requireNonce(nonce)
        val h = ecb.encryptBlocks(ByteArray(16))
        val ciphertext = ctr(nonce, plaintext)
        val tag = tag(h, nonce, aad, ciphertext)
        return ciphertext + tag
    }

    fun open(nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        requireNonce(nonce)
        if (sealed.size < TAG) throw CryptoException(CryptoException.Kind.AUTH_FAILED, "shorter than a tag")
        val ciphertext = sealed.copyOfRange(0, sealed.size - TAG)
        val received = sealed.copyOfRange(sealed.size - TAG, sealed.size)
        val h = ecb.encryptBlocks(ByteArray(16))
        if (!constantTimeEquals(tag(h, nonce, aad, ciphertext), received)) {
            throw CryptoException(CryptoException.Kind.AUTH_FAILED, "tag mismatch")
        }
        return ctr(nonce, ciphertext)
    }

    private fun requireNonce(nonce: ByteArray) {
        if (nonce.size != NONCE) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "nonce length")
    }

    /** The counter-mode keystream from block counter 2 (counter 1 is the tag mask), XORed with [data]. */
    private fun ctr(nonce: ByteArray, data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val blocks = (data.size + 15) / 16
        // inc32 wraps after 2^32 blocks (64 GiB); a message that long is refused rather than reusing a counter.
        if (blocks.toLong() > MAX_BLOCKS) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "message too long")
        val counters = ByteArray(blocks * 16)
        for (b in 0 until blocks) {
            nonce.copyInto(counters, b * 16)
            val c = b + 2
            counters[b * 16 + 12] = (c ushr 24).toByte()
            counters[b * 16 + 13] = (c ushr 16).toByte()
            counters[b * 16 + 14] = (c ushr 8).toByte()
            counters[b * 16 + 15] = c.toByte()
        }
        val stream = ecb.encryptBlocks(counters)
        return ByteArray(data.size) { (data[it].toInt() xor stream[it].toInt()).toByte() }
    }

    private fun tag(h: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val s = ghash(h, aad, ciphertext)
        val j0 = ByteArray(16)
        nonce.copyInto(j0)
        j0[15] = 1
        val mask = ecb.encryptBlocks(j0)
        return ByteArray(16) { (s[it].toInt() xor mask[it].toInt()).toByte() }
    }

    companion object {
        const val NONCE = 12
        const val TAG = 16
        private const val MAX_BLOCKS = 0xFFFF_FFFDL

        private fun long(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }

        /** GHASH_H(A ‖ pad ‖ C ‖ pad ‖ [len A]₆₄ ‖ [len C]₆₄) (SP 800-38D §6.4). */
        fun ghash(h: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
            val hHi = long(h, 0)
            val hLo = long(h, 8)
            var yHi = 0L
            var yLo = 0L
            fun block(src: ByteArray, at: Int, length: Int) {
                val b = ByteArray(16)
                src.copyInto(b, 0, at, at + length)
                yHi = yHi xor long(b, 0)
                yLo = yLo xor long(b, 8)
                // Z = Y · H in GF(2^128), bit-reflected as in the specification, one mask per bit.
                var zHi = 0L
                var zLo = 0L
                var vHi = hHi
                var vLo = hLo
                for (i in 0 until 128) {
                    val word = if (i < 64) yHi else yLo
                    val bit = (word ushr (63 - (i and 63))) and 1L
                    val m = -bit
                    zHi = zHi xor (vHi and m)
                    zLo = zLo xor (vLo and m)
                    val lsb = -(vLo and 1L)
                    vLo = (vLo ushr 1) or (vHi shl 63)
                    vHi = (vHi ushr 1) xor (R_HI and lsb)
                }
                yHi = zHi
                yLo = zLo
            }
            for (data in arrayOf(aad, ciphertext)) {
                var at = 0
                while (at < data.size) {
                    val n = minOf(16, data.size - at)
                    block(data, at, n)
                    at += n
                }
            }
            val lengths = ByteArray(16)
            for (i in 0 until 8) {
                lengths[7 - i] = ((aad.size.toLong() * 8) ushr (8 * i)).toByte()
                lengths[15 - i] = ((ciphertext.size.toLong() * 8) ushr (8 * i)).toByte()
            }
            block(lengths, 0, 16)
            return ByteArray(16) { (((if (it < 8) yHi else yLo) ushr (56 - 8 * (it and 7))) and 0xFF).toByte() }
        }

        /** The reduction constant R = 11100001 ‖ 0¹²⁰ as the high word. */
        private const val R_HI = -0x1f00000000000000L // 0xE100000000000000
    }
}
