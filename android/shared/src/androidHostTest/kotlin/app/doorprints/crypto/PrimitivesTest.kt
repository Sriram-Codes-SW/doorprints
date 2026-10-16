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

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The JVM provider against the published known answers in `docs/schemas/hpke-vectors.json` (TC-U-125). */
class PrimitivesTest {
    private val p = JvmCryptoProvider
    private val prim = Vectors.load("hpke-vectors.json").getValue("primitives").jsonObject
    private fun cases(key: String): List<JsonObject> = prim.getValue(key).jsonArray.map { it.jsonObject }

    @Test
    fun hmacSha256MatchesRfc4231() {
        for (c in cases("hmacSha256")) assertEquals(c.s("source"), c.s("mac"), p.hmacSha256(c.h("key"), c.h("data")).hex())
    }

    @Test
    fun hkdfMatchesRfc5869() {
        val hkdf = Hkdf(p)
        for (c in cases("hkdfSha256")) {
            val prk = hkdf.extract(c.h("salt"), c.h("ikm"))
            assertEquals(c.s("source"), c.s("prk"), prk.hex())
            assertEquals(c.s("source"), c.s("okm"), hkdf.expand(prk, c.h("info"), c.getValue("length").jsonPrimitive.int).hex())
        }
    }

    @Test
    fun aesGcmMatchesTheGcmSpecAndFailsClosed() {
        for (c in cases("aesGcm")) {
            val key = p.aesKey(c.h("key"))
            val sealed = p.aesGcmSeal(key, c.h("nonce"), c.h("aad"), c.h("pt"))
            assertEquals(c.s("source"), c.s("ct") + c.s("tag"), sealed.hex())
            assertArrayEquals(c.h("pt"), p.aesGcmOpen(key, c.h("nonce"), c.h("aad"), sealed))
            for (i in sealed.indices) {
                val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
                expectKind(CryptoException.Kind.AUTH_FAILED) { p.aesGcmOpen(key, c.h("nonce"), c.h("aad"), bad) }
            }
            expectKind(CryptoException.Kind.AUTH_FAILED) { p.aesGcmOpen(key, c.h("nonce"), c.h("aad") + 0, sealed) }
            expectKind(CryptoException.Kind.AUTH_FAILED) { p.aesGcmOpen(key, c.h("nonce"), c.h("aad"), sealed.copyOf(sealed.size - 1)) }
            expectKind(CryptoException.Kind.AUTH_FAILED) { p.aesGcmOpen(key, c.h("nonce"), c.h("aad"), ByteArray(15)) }
        }
        expectKind(CryptoException.Kind.INVALID_INPUT) { p.aesGcmSeal(p.aesKey(ByteArray(32)), ByteArray(16), ByteArray(0), ByteArray(0)) }
        expectKind(CryptoException.Kind.INVALID_KEY) { p.aesKey(ByteArray(24)) }
    }

    @Test
    fun ecdhAndScalarMultiplicationMatchPublishedValues() {
        for (c in cases("ecdhP256")) {
            val i = p.p256FromScalar(c.h("i"))
            val r = p.p256FromScalar(c.h("r"))
            assertEquals(c.s("gi"), i.publicKey.hex())
            assertEquals(c.s("gr"), r.publicKey.hex())
            assertEquals(c.s("girx"), p.p256Agree(i, r.publicKey).hex())
            assertEquals(c.s("girx"), p.p256Agree(r, i.publicKey).hex())
        }
        for (c in cases("p256ScalarMult")) assertEquals(c.s("source"), c.s("point"), p.p256FromScalar(c.h("k")).publicKey.hex())
    }

    @Test
    fun scalarsOutOfRangeAreRefused() {
        for (bad in listOf(ByteArray(32), P256Scalar.ORDER, ByteArray(32) { -1 }, ByteArray(31))) {
            expectKind(CryptoException.Kind.INVALID_KEY) { p.p256FromScalar(bad) }
        }
    }

    @Test
    fun receivedPointsAreValidated() {
        val good = p.p256Generate().publicKey
        val pHex = "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff"
        val invalid = listOf(
            ByteArray(65), // the all-zero encoding (no infinity encoding is accepted)
            byteArrayOf(0), // SEC1's point at infinity
            good.copyOf(64),
            good + 0,
            good.copyOf().also { it[0] = 0x02 }, // compressed prefix with 65 bytes
            good.copyOf(33).also { it[0] = 0x02 }, // compressed points are not accepted
            good.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }, // off the curve
            byteArrayOf(4) + hex(pHex) + good.copyOfRange(33, 65), // x = p
            byteArrayOf(4) + good.copyOfRange(1, 33) + hex(pHex), // y = p
        )
        for (bad in invalid) {
            expectKind(CryptoException.Kind.INVALID_KEY) { p.p256ValidatePublic(bad) }
            expectKind(CryptoException.Kind.INVALID_KEY) { p.p256Agree(p.p256Generate(), bad) }
        }
        assertArrayEquals(good, p.p256ValidatePublic(good))
    }

    @Test
    fun generatedKeysAgree() {
        repeat(5) {
            val a = p.p256Generate()
            val b = p.p256Generate()
            assertArrayEquals(p.p256Agree(a, b.publicKey), p.p256Agree(b, a.publicKey))
            // A key rebuilt from a generated pair's agreement partner: the derived public key is on the curve.
            p.p256ValidatePublic(a.publicKey)
        }
    }

    @Test
    fun scalarPublicKeyIsTheRightSign() {
        // For random scalars, the public key from p256FromScalar agrees with a partner like a generated key does,
        // and HPKE (whose kem_context holds the encoding, so a wrong sign of y would fail) opens across them.
        repeat(20) {
            val d = p.randomBytes(32)
            if (!P256Scalar.isValid(d)) return@repeat
            val k = p.p256FromScalar(d)
            val q = p.p256Generate()
            assertArrayEquals(p.p256Agree(k, q.publicKey), p.p256Agree(q, k.publicKey))
            val sealed = Hpke(p).seal(k.publicKey, ByteArray(0), ByteArray(0), byteArrayOf(1, 2, 3))
            assertArrayEquals(byteArrayOf(1, 2, 3), Hpke(p).open(sealed.enc, k, ByteArray(0), ByteArray(0), sealed.ciphertext))
        }
    }

    @Test
    fun constantTimeEqualsComparesContentAndLength() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 0)))
        assertTrue(constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun hmacRefusesAnEmptyKeyAndHkdfSubstitutesTheZeroSalt() {
        expectKind(CryptoException.Kind.INVALID_INPUT) { p.hmacSha256(ByteArray(0), ByteArray(1)) }
        assertArrayEquals(Hkdf(p).extract(ByteArray(32), byteArrayOf(1)), Hkdf(p).extract(ByteArray(0), byteArrayOf(1)))
    }
}

internal fun expectKind(kind: CryptoException.Kind, block: () -> Unit) {
    try {
        block()
        fail("expected $kind")
    } catch (e: CryptoException) {
        assertEquals(kind, e.kind)
    }
}
