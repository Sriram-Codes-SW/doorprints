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

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * The primitives of [platformCryptoProvider] against published known answers (NIST SP 800-38D / the GCM spec's test
 * cases 13, 14 and 16, RFC 4231, RFC 5903 §8.1, SEC 2 / NIST point multiples) and Node's `crypto` for the generated
 * ones (S4b-BL-131). It is a common test on purpose: on the host it runs against `JvmCryptoProvider`, so the vectors
 * themselves are proved right by a second implementation; on the iPhone simulator (`:shared:iosSimulatorArm64Test`)
 * the same bytes must come out of the CommonCrypto, Security.framework and pure-Kotlin code of the iOS provider.
 */
class PlatformCryptoProviderTest {
    private val p = platformCryptoProvider()

    private fun h(s: String) = Bytes.unhex(s)
    private fun ByteArray.hx() = Bytes.hex(this)
    private fun pattern(n: Int) = ByteArray(n) { ((31L * it + 7) and 0xFF).toByte() }
    private fun expect(kind: CryptoException.Kind, block: () -> Unit) {
        assertEquals(kind, assertFailsWith<CryptoException> { block() }.kind)
    }

    // ---- random, SHA-256, HMAC ----

    @Test
    fun randomBytesHaveTheRequestedSizeAndDiffer() {
        assertEquals(0, p.randomBytes(0).size)
        val a = p.randomBytes(32)
        val b = p.randomBytes(32)
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
        assertFalse(a.all { it == 0.toByte() })
        assertEquals(1000, p.randomBytes(1000).size)
        expect(CryptoException.Kind.INVALID_INPUT) { p.randomBytes(-1) }
    }

    @Test
    fun sha256KnownAnswersAndIncrementalUse() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", p.sha256().digest().hx())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            p.sha256Of("abc".encodeToByteArray()).hx(),
        )
        val parts = p.sha256().run {
            update("a".encodeToByteArray())
            update("bc".encodeToByteArray())
            digest()
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", parts.hx())
        // 1,000,000 x 'a' (FIPS 180-2 test 3), in chunks that straddle the 64-byte block.
        val million = p.sha256().run {
            val chunk = ByteArray(1000) { 'a'.code.toByte() }
            repeat(1000) { update(chunk) }
            digest()
        }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", million.hx())
    }

    @Test
    fun hmacSha256Rfc4231() {
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            p.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).hx(),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            p.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).hx(),
        )
        // Test case 6: a key longer than the block size (131 bytes of 0xaa) is hashed first.
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            p.hmacSha256(
                ByteArray(131) { 0xaa.toByte() },
                "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
            ).hx(),
        )
        expect(CryptoException.Kind.INVALID_INPUT) { p.hmacSha256(ByteArray(0), ByteArray(1)) }
    }

    // ---- AES-GCM ----

    @Test
    fun aesGcmNistCases() {
        val zero = p.aesKey(ByteArray(32))
        // The GCM specification's test cases 13 (empty) and 14 (one zero block), AES-256.
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b", p.aesGcmSeal(zero, ByteArray(12), ByteArray(0), ByteArray(0)).hx())
        assertEquals(
            "cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919",
            p.aesGcmSeal(zero, ByteArray(12), ByteArray(0), ByteArray(16)).hx(),
        )
        // Test case 16: AES-256 with AAD and a partial last block.
        val key = p.aesKey(h("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308"))
        val nonce = h("cafebabefacedbaddecaf888")
        val aad = h("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val pt = h(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
        )
        val expected = h(
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" + "76fc6ece0f4e1768cddf8853bb2d551b",
        )
        assertContentEquals(expected, p.aesGcmSeal(key, nonce, aad, pt))
        assertContentEquals(pt, p.aesGcmOpen(key, nonce, aad, expected))
    }

    @Test
    fun aesGcmAcrossLengthsMatchesNode() {
        val key = p.aesKey(pattern(32))
        val sizes = listOf(0, 1, 15, 16, 17, 31, 32, 33, 47, 48, 63, 64, 65, 100, 1000, 4099)
        val all = sizes.map { n ->
            val sealed = p.aesGcmSeal(key, pattern(12), pattern(20), pattern(n))
            assertEquals(n + 16, sealed.size)
            assertContentEquals(pattern(n), p.aesGcmOpen(key, pattern(12), pattern(20), sealed))
            sealed
        }
        assertEquals(
            "1f4af01f3059b88879ceb8be508c8a458a16d6827478f6dd677fea431bf5d771",
            p.sha256Of(Bytes.concat(*all.toTypedArray())).hx(),
        )
        // AES-128 (RFC 9180's AES-128-GCM suite uses it).
        assertEquals(
            "8e73fc3138506b70e4c01b50bd5182ee4d3ea11ce16a7d1817a853fb2ef2a2be33ad4f425483b22de285f5652406baf687",
            p.aesGcmSeal(p.aesKey(pattern(16)), pattern(12), pattern(5), pattern(33)).hx(),
        )
    }

    @Test
    fun aesGcmOpenFailsClosedOnEveryChange() {
        val key = p.aesKey(pattern(32))
        val nonce = pattern(12)
        val aad = pattern(7)
        val sealed = p.aesGcmSeal(key, nonce, aad, pattern(40))
        for (i in sealed.indices) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            expect(CryptoException.Kind.AUTH_FAILED) { p.aesGcmOpen(key, nonce, aad, bad) }
        }
        expect(CryptoException.Kind.AUTH_FAILED) { p.aesGcmOpen(key, nonce, pattern(8), sealed) }
        expect(CryptoException.Kind.AUTH_FAILED) { p.aesGcmOpen(key, pattern(12).also { it[0]++ }, aad, sealed) }
        expect(CryptoException.Kind.AUTH_FAILED) { p.aesGcmOpen(p.aesKey(pattern(32).also { it[31]++ }), nonce, aad, sealed) }
        expect(CryptoException.Kind.AUTH_FAILED) { p.aesGcmOpen(key, nonce, aad, sealed.copyOf(sealed.size - 1)) }
        expect(CryptoException.Kind.AUTH_FAILED) { p.aesGcmOpen(key, nonce, aad, sealed + byteArrayOf(0)) }
        expect(CryptoException.Kind.AUTH_FAILED) { p.aesGcmOpen(key, nonce, aad, ByteArray(15)) }
        expect(CryptoException.Kind.AUTH_FAILED) { p.aesGcmOpen(key, nonce, aad, ByteArray(0)) }
    }

    @Test
    fun aesInputsAreChecked() {
        expect(CryptoException.Kind.INVALID_KEY) { p.aesKey(ByteArray(24)) }
        expect(CryptoException.Kind.INVALID_KEY) { p.aesKey(ByteArray(0)) }
        expect(CryptoException.Kind.INVALID_KEY) { p.aesKey(ByteArray(31)) }
        assertEquals(32, p.aesKey(ByteArray(32)).sizeBytes)
        assertEquals(16, p.aesKey(ByteArray(16)).sizeBytes)
        val key = p.aesKey(ByteArray(32))
        for (n in listOf(0, 8, 11, 13, 16)) {
            expect(CryptoException.Kind.INVALID_INPUT) { p.aesGcmSeal(key, ByteArray(n), ByteArray(0), ByteArray(1)) }
            expect(CryptoException.Kind.INVALID_INPUT) { p.aesGcmOpen(key, ByteArray(n), ByteArray(0), ByteArray(32)) }
        }
        // The caller's key array is copied: changing it afterwards changes nothing.
        val raw = pattern(32)
        val k = p.aesKey(raw)
        val before = p.aesGcmSeal(k, pattern(12), ByteArray(0), pattern(5))
        raw.fill(0)
        assertContentEquals(before, p.aesGcmSeal(k, pattern(12), ByteArray(0), pattern(5)))
    }

    // ---- P-256 ----

    @Test
    fun ecdhRfc5903() {
        val i = p.p256FromScalar(h("c88f01f510d9ac3f70a292daa2316de544e9aab8afe84049c62a9c57862d1433"))
        val r = p.p256FromScalar(h("c6ef9c5d78ae012a011164acb397ce2088685d8f06bf9be0b283ab46476bee53"))
        assertEquals(
            "04dad0b65394221cf9b051e1feca5787d098dfe637fc90b9ef945d0c3772581180" +
                "5271a0461cdb8252d61f1c456fa3e59ab1f45b33accf5f58389e0577b8990bb3",
            i.publicKey.hx(),
        )
        assertEquals(
            "04d12dfb5289c8d4f81208b70270398c342296970a0bccb74c736fc7554494bf63" +
                "56fbf3ca366cc23e8157854c13c58d6aac23f046ada30f8353e74f33039872ab",
            r.publicKey.hx(),
        )
        val expected = "d6840f6b42f6edafd13116e0e12565202fef8e9ece7dce03812464d04b9442de"
        assertEquals(expected, p.p256Agree(i, r.publicKey).hx())
        assertEquals(expected, p.p256Agree(r, i.publicKey).hx())
    }

    @Test
    fun publicKeyOfAScalarKnownAnswers() {
        val n = "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551"
        val cases = listOf(
            "0000000000000000000000000000000000000000000000000000000000000001" to
                "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
            "0000000000000000000000000000000000000000000000000000000000000002" to
                "047cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc4766997807775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1",
            "0000000000000000000000000000000000000000000000000000000000000003" to
                "045ecbe4d1a6330a44c8f7ef951d4bf165e6c6b721efada985fb41661bc6e7fd6c8734640c4998ff7e374b06ce1a64a2ecd82ab036384fb83d9a79b127a27d5032",
            // n - 1: -G, so the same x and the negated y.
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632550" to
                "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296b01cbd1c01e58065711814b583f061e9d431cca994cea1313449bf97c840ae0a",
            // n - 2: -2G.
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63254f" to
                "047cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978f888aaee24712fc0d6c26539608bcf244582521ac3167dd661fb4862dd878c2e",
            "8000000000000000000000000000000000000000000000000000000000000000" to
                "0477b20a912e6b23135066e911891524bc4efe3560e3e92350b52dec8f375f2b54a3dc291825cea3f7f7b10bfcdd038a72df623da1e850e0f1caa801fcd6cc67ff",
            "0000000000000100000000000000000000000000000000000000000000003039" to
                "04f805cb24c0992b29345b4ebe2f6307f711600806aa5f8f85c20a20c093b674dec062ca996c912ee34533be667a50242469762c015b072e8b0b60f14db7e215ed",
        )
        for ((d, pub) in cases) assertEquals(pub, p.p256FromScalar(h(d)).publicKey.hx(), "d = $d")
        // Twenty scalars from SHA-256, public keys computed by Node's crypto, compared by their digest.
        val pubs = (0 until 20).map { i ->
            p.p256FromScalar(p.sha256Of("doorprints-ios-scalar".encodeToByteArray(), byteArrayOf(i.toByte()))).publicKey
        }
        assertEquals(
            "c54e39ce2886b4b61be18caccbec4601e7531a472098b28d661f64e7c4d56879",
            p.sha256Of(Bytes.concat(*pubs.toTypedArray())).hx(),
        )
        // Scalars outside [1, n - 1] and of the wrong length are refused.
        for (bad in listOf(ByteArray(32), h(n), h("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"), ByteArray(31), ByteArray(33), ByteArray(0))) {
            expect(CryptoException.Kind.INVALID_KEY) { p.p256FromScalar(bad) }
        }
    }

    @Test
    fun generatedKeysAgreeAndAreValid() {
        val a = p.p256Generate()
        val b = p.p256Generate()
        assertEquals(65, a.publicKey.size)
        assertEquals(0x04.toByte(), a.publicKey[0])
        assertNotEquals(a.publicKey.hx(), b.publicKey.hx())
        assertContentEquals(a.publicKey, p.p256ValidatePublic(a.publicKey))
        val ab = p.p256Agree(a, b.publicKey)
        assertEquals(32, ab.size)
        assertContentEquals(ab, p.p256Agree(b, a.publicKey))
        assertFalse(ab.all { it == 0.toByte() })
        // The public key array handed out is a copy.
        a.publicKey.fill(0)
        assertEquals(0x04.toByte(), a.publicKey[0])
        // A key built from a scalar agrees with a generated one the same way.
        val c = p.p256FromScalar(pattern(32).also { it[0] = 1 })
        assertContentEquals(p.p256Agree(c, a.publicKey), p.p256Agree(a, c.publicKey))
        assertFalse(c.toString().contains(Bytes.hex(pattern(32).also { it[0] = 1 }).take(16)), "toString must not print key bytes")
    }

    @Test
    fun publicPointValidation() {
        val g = h(
            "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
                "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
        )
        assertContentEquals(g, p.p256ValidatePublic(g))
        val key = p.p256Generate()
        fun bad(mutate: (ByteArray) -> ByteArray) {
            expect(CryptoException.Kind.INVALID_KEY) { p.p256ValidatePublic(mutate(g.copyOf())) }
            expect(CryptoException.Kind.INVALID_KEY) { p.p256Agree(key, mutate(g.copyOf())) }
        }
        bad { it.also { b -> b[64] = (b[64].toInt() xor 1).toByte() } } // off the curve
        bad { it.also { b -> b[0] = 0x02 } } // compressed prefix
        bad { it.also { b -> b[0] = 0x00 } }
        bad { it.also { b -> b[0] = 0x06 } } // hybrid
        bad { it.copyOf(64) }
        bad { it.copyOf(66) }
        bad { ByteArray(0) }
        bad { ByteArray(65).also { b -> b[0] = 0x04 } } // (0, 0): not on the curve, and no infinity encoding
        bad { ByteArray(65) } // the point at infinity as zeros
        // x = p and y = p are out of range (non-canonical), whatever the other coordinate is.
        val p256 = h("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")
        bad { it.also { b -> p256.copyInto(b, 1) } }
        bad { it.also { b -> p256.copyInto(b, 33) } }
        bad { it.also { b -> b.fill(0xFF.toByte(), 1, 65) } }
        // The foreign-key guard: an object that is not this provider's key is refused.
        val foreign = object : P256PrivateKey {
            override val publicKey: ByteArray get() = g.copyOf()
        }
        expect(CryptoException.Kind.INVALID_KEY) { p.p256Agree(foreign, g) }
    }

    // ---- what sits on top: HPKE and the envelope run on these primitives ----

    @Test
    fun hpkeSealOpenRoundTripAndTamper() {
        val h = Hpke(p)
        val r = p.p256Generate()
        val info = "doorprints/dpx1/wrap".encodeToByteArray()
        val aad = byteArrayOf(9)
        val s = h.seal(r.publicKey, info, aad, pattern(32))
        assertContentEquals(pattern(32), h.open(s.enc, r, info, aad, s.ciphertext))
        expect(CryptoException.Kind.AUTH_FAILED) { h.open(s.enc, p.p256Generate(), info, aad, s.ciphertext) }
        expect(CryptoException.Kind.AUTH_FAILED) { h.open(s.enc, r, info, byteArrayOf(8), s.ciphertext) }
    }
}
