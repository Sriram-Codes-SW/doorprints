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

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The pure-Kotlin GCM of the iPhone ([Gcm], with GHASH by 4-bit tables) against the JVM's `javax.crypto` AES/GCM,
 * reached through [CryptoProvider] ([JvmCryptoProvider]) as the reference (S4b-BL-131, review optimisation O2).
 * The block cipher under [Gcm] is `AES/ECB/NoPadding` of the same JVM. It lives in the host test tree because
 * `javax.crypto` is JVM-only; the common vectors are in `IosGcmTest` and `PlatformCryptoProviderTest`.
 */
class GcmDifferentialTest {
    private val ref = JvmCryptoProvider

    private fun key(size: Int, salt: Int) = ByteArray(size) { ((it * 29 + salt * 7 + 3) and 0xFF).toByte() }
    private fun data(n: Int, salt: Int) = ByteArray(n) { ((it * 131 + salt * 17 + (it shr 3)) and 0xFF).toByte() }
    private fun nonce(salt: Int) = ByteArray(12) { ((it * 11 + salt) and 0xFF).toByte() }

    private fun gcmFor(raw: ByteArray): Gcm {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(raw, "AES"))
        return Gcm { blocks -> c.doFinal(blocks) }
    }

    /** seal() must equal the JVM's bytes and open() of the JVM's bytes must give the plaintext back. */
    private fun check(keySize: Int, aadLen: Int, ptLen: Int, salt: Int) {
        val raw = key(keySize, salt)
        val n = nonce(salt)
        val aad = data(aadLen, salt + 1)
        val pt = data(ptLen, salt + 2)
        val expected = ref.aesGcmSeal(ref.aesKey(raw), n, aad, pt)
        val gcm = gcmFor(raw)
        val mine = gcm.seal(n, aad, pt)
        assertContentEquals(expected, mine, "seal key=$keySize aad=$aadLen pt=$ptLen")
        assertContentEquals(pt, gcm.open(n, aad, expected), "open key=$keySize aad=$aadLen pt=$ptLen")
    }

    @Test
    fun sealAndOpenMatchTheJvmForEveryPlaintextLengthUpTo70AndBothKeySizes() {
        for (k in listOf(16, 32)) for (len in 0..70) check(k, if (len % 3 == 0) 0 else 20, len, len)
    }

    @Test
    fun sealAndOpenMatchTheJvmForEveryAadLengthUpTo33() {
        for (k in listOf(16, 32)) for (a in 0..33) for (len in listOf(0, 1, 16, 33)) check(k, a, len, a * 5 + len)
    }

    @Test
    fun aHundredKilobytesMatchTheJvm() {
        check(32, 13, 100_000, 9)
        check(16, 0, 100_000 + 7, 10)
    }

    @Test
    fun fiveThousandSeededShapesMatchTheJvm() {
        var s = 0x2545F491L
        fun next(bound: Int): Int {
            s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
            return ((s ushr 3) % bound).toInt()
        }
        repeat(5000) { i -> check(if (next(2) == 0) 16 else 32, next(65), next(300), i + next(1000)) }
    }

    @Test
    fun aChangedTagCiphertextOrAadIsRefused() {
        for (k in listOf(16, 32)) for (len in listOf(0, 1, 15, 16, 17, 33, 64)) {
            val raw = key(k, len)
            val n = nonce(len)
            val aad = data(5, 1)
            val gcm = gcmFor(raw)
            val sealed = gcm.seal(n, aad, data(len, 2))
            for (i in sealed.indices) {
                val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor (1 shl (i % 8))).toByte() }
                assertEquals(CryptoException.Kind.AUTH_FAILED, assertFailsWith<CryptoException> { gcm.open(n, aad, bad) }.kind)
            }
            for (i in aad.indices) {
                val badAad = aad.copyOf().also { it[i] = (it[i].toInt() xor 0x80).toByte() }
                assertEquals(CryptoException.Kind.AUTH_FAILED, assertFailsWith<CryptoException> { gcm.open(n, badAad, sealed) }.kind)
            }
            assertFailsWith<CryptoException> { gcm.open(n, aad + 0, sealed) } // an extra AAD byte moves the length block
            assertFailsWith<CryptoException> { gcm.open(n, aad, sealed + 0) }
        }
    }
}
