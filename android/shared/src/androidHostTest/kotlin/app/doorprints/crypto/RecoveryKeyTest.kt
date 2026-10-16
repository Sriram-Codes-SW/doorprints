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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/** The recovery key's text and key pair, and the scalar reduction under it (TC-U-127). */
class RecoveryKeyTest {
    private val p = JvmCryptoProvider
    private val random = SecureRandom()
    private val n = BigInteger(1, P256Scalar.ORDER)

    @Test
    fun reductionMatchesBigIntegerForRandomAndEdgeSeeds() {
        val edges = listOf(
            ByteArray(48),
            ByteArray(48) { -1 },
            ByteArray(16) + (n - BigInteger.ONE).toByteArray().takeLast(32).toByteArray(),
            ByteArray(16) + (n - BigInteger.TWO).toByteArray().takeLast(32).toByteArray(),
            ByteArray(16) + P256Scalar.ORDER,
            ByteArray(32) { -1 },
            byteArrayOf(1),
            ByteArray(0),
        )
        val randoms = List(2000) { ByteArray(48).also { b -> random.nextBytes(b) } }
        for (seed in edges + randoms) {
            val expected = BigInteger(1, seed).mod(n - BigInteger.ONE) + BigInteger.ONE
            val got = BigInteger(1, P256Scalar.reduceToScalar(seed))
            assertEquals(expected, got)
            assertTrue(P256Scalar.isValid(P256Scalar.reduceToScalar(seed)))
        }
    }

    @Test
    fun isValidIsTheRangeOneToNMinusOne() {
        fun b32(v: BigInteger) = ByteArray(32).also { out -> v.toByteArray().takeLast(32).toByteArray().let { it.copyInto(out, 32 - it.size) } }
        assertTrue(!P256Scalar.isValid(ByteArray(32)))
        assertTrue(P256Scalar.isValid(b32(BigInteger.ONE)))
        assertTrue(P256Scalar.isValid(b32(n - BigInteger.ONE)))
        assertTrue(!P256Scalar.isValid(b32(n)))
        assertTrue(!P256Scalar.isValid(b32(n + BigInteger.ONE)))
        assertTrue(!P256Scalar.isValid(ByteArray(32) { -1 }))
    }

    @Test
    fun textRoundTripsAndHasTheShownShape() {
        repeat(200) {
            val key = RecoveryKey.generate(p)
            assertEquals(27, key.symbols.length)
            assertTrue(key.display.matches(Regex("^([0-9A-Z*~$=]{4}-){6}[0-9A-Z*~$=]{3}$")))
            assertTrue(key.symbols[0] in '0'..'7')
            assertArrayEquals(key.bytes, RecoveryKey.parse(key.display).bytes)
            assertArrayEquals(key.bytes, RecoveryKey.parse(key.symbols.lowercase()).bytes)
        }
    }

    @Test
    fun everySingleSubstitutionAndAdjacentSwapIsCaught() {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val checks = "$alphabet*~$=U"
        repeat(20) {
            val s = RecoveryKey.generate(p).symbols
            for (i in s.indices) {
                for (c in if (i < 26) alphabet else checks) {
                    if (c == s[i]) continue
                    val changed = s.substring(0, i) + c + s.substring(i + 1)
                    expectRefused(changed)
                }
            }
            for (i in 0 until s.length - 1) {
                if (s[i] == s[i + 1]) continue
                expectRefused(s.substring(0, i) + s[i + 1] + s[i] + s.substring(i + 2))
            }
        }
    }

    private fun expectRefused(text: String) {
        try {
            RecoveryKey.parse(text)
            fail("accepted $text")
        } catch (e: RecoveryKeyException) {
            assertTrue(e.reason in setOf(RecoveryKeyException.Reason.CHECK_MISMATCH, RecoveryKeyException.Reason.OUT_OF_RANGE, RecoveryKeyException.Reason.INVALID_CHARACTER))
        }
    }

    @Test
    fun theKeyPairIsDeterministicAndOpensWhatItsPublicKeyReceives() {
        val key = RecoveryKey.generate(p)
        val a = key.keyPair(p)
        val b = RecoveryKey.parse(key.display).keyPair(p)
        assertArrayEquals(a.publicKey, b.publicKey)
        val sealed = Hpke(p).seal(a.publicKey, WrapAad.HPKE_INFO, byteArrayOf(1), ByteArray(32) { 5 })
        assertArrayEquals(ByteArray(32) { 5 }, Hpke(p).open(sealed.enc, b, WrapAad.HPKE_INFO, byteArrayOf(1), sealed.ciphertext))
        assertTrue(!RecoveryKey.generate(p).keyPair(p).publicKey.contentEquals(a.publicKey))
        assertEquals("RecoveryKey(…)", key.toString())
    }
}
