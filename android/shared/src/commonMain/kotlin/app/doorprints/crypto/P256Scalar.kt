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
 * The little scalar arithmetic the recovery key and HPKE's DeriveKeyPair need on P-256's group order n, in common
 * code with no big-integer library: fixed-size limbs, no branch or index that depends on a secret value. The point
 * multiplication itself is always the platform's ([CryptoProvider.p256FromScalar]); nothing here touches a point.
 */
internal object P256Scalar {
    /** n, the order of P-256's base point (FIPS 186-5 / SEC 2), big-endian. */
    val ORDER: ByteArray = Bytes.unhex("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")

    private val ORDER_MINUS_1: IntArray = toLimbs(Bytes.unhex("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632550"))

    /** True when [scalar] (32 bytes, big-endian) is in [1, n − 1]; constant time in the value. */
    fun isValid(scalar: ByteArray): Boolean {
        require(scalar.size == 32)
        val v = toLimbs(scalar)
        val n = toLimbs(ORDER)
        // v - n borrows exactly when v < n.
        var borrow = 0L
        var nonZero = 0
        for (i in 0 until 8) {
            val d = (v[i].toLong() and MASK) - (n[i].toLong() and MASK) - borrow
            borrow = (d ushr 63) and 1L
            nonZero = nonZero or v[i]
        }
        val lessThanN = borrow.toInt()
        val isNonZero = ((nonZero or -nonZero) ushr 31) and 1
        return (lessThanN and isNonZero) == 1
    }

    /**
     * FIPS 186-5 A.2.1 ("key pair generation using extra random bits"): `d = (c mod (n − 1)) + 1` for a big-endian
     * [seed] c of any length (docs/15 §9.4 uses 48 bytes, so the bias is below 2⁻⁶⁴). The result is 32 bytes,
     * always in [1, n − 1].
     *
     * Horner's rule over the seed's bits, one constant-time conditional subtraction per bit: r < m before each step,
     * so 2r + bit < 2m and one subtraction brings it back below m.
     */
    fun reduceToScalar(seed: ByteArray): ByteArray {
        val m = ORDER_MINUS_1
        val r = IntArray(8)
        val t = IntArray(8)
        for (byteIndex in seed.indices) {
            val byte = seed[byteIndex].toInt() and 0xFF
            for (bit in 7 downTo 0) {
                // r = 2r + bit, with the bit shifted out of the top kept in `carry`.
                var carryIn = (byte ushr bit) and 1
                for (i in 0 until 8) {
                    val limb = r[i]
                    r[i] = (limb shl 1) or carryIn
                    carryIn = limb ushr 31
                }
                val carry = carryIn
                // t = r - m over 8 limbs.
                var borrow = 0L
                for (i in 0 until 8) {
                    val d = (r[i].toLong() and MASK) - (m[i].toLong() and MASK) - borrow
                    t[i] = d.toInt()
                    borrow = (d ushr 63) and 1L
                }
                // (carry ‖ r) >= m exactly when carry is set or nothing was borrowed.
                val take = carry or (1 - borrow.toInt())
                val mask = -take
                for (i in 0 until 8) r[i] = (t[i] and mask) or (r[i] and mask.inv())
            }
        }
        // d = r + 1; r <= n - 2, so no carry leaves the top limb.
        var carry = 1L
        for (i in 0 until 8) {
            val s = (r[i].toLong() and MASK) + carry
            r[i] = s.toInt()
            carry = s ushr 32
        }
        return fromLimbs(r)
    }

    private const val MASK = 0xFFFFFFFFL

    /** 32 big-endian bytes to 8 little-endian 32-bit limbs. */
    private fun toLimbs(b: ByteArray): IntArray {
        require(b.size == 32)
        return IntArray(8) { i ->
            val o = 28 - 4 * i
            ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
        }
    }

    /** The inverse of the limb split: 8 words, least significant first, as 32 big-endian bytes. */
    private fun fromLimbs(l: IntArray): ByteArray {
        val out = ByteArray(32)
        for (i in 0 until 8) {
            val o = 28 - 4 * i
            out[o] = (l[i] ushr 24).toByte()
            out[o + 1] = (l[i] ushr 16).toByte()
            out[o + 2] = (l[i] ushr 8).toByte()
            out[o + 3] = l[i].toByte()
        }
        return out
    }
}
