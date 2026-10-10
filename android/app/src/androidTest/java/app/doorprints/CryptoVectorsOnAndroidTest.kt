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

package app.doorprints

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.crypto.ByteSink
import app.doorprints.crypto.Dpx
import app.doorprints.crypto.FolderKeys
import app.doorprints.crypto.Hpke
import app.doorprints.crypto.RecoveryKey
import app.doorprints.crypto.RecoveryKeyException
import app.doorprints.crypto.kidOf
import app.doorprints.crypto.platformCryptoProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * The crypto vectors on the Android runtime's own provider (S4b-BL-133): S4b-BL-125's host tests run `JvmCryptoProvider`
 * on the JDK's SunEC, while on a phone the same `javax.crypto` and `java.security` calls land in Conscrypt/BoringSSL.
 * This runs the vectors of `docs/schemas/hpke-vectors.json` and `dpx-vectors.json` (a copy in this test's assets,
 * `CryptoVectorAssetsTest` checks it equals the schemas' file) on every emulator API level of android-emulator.yml.
 *
 * What it covers is what `:app` can reach: the crypto classes' internal hooks (an injected ephemeral key, the key
 * schedule's intermediate values, `RecoveryKey.scalar`, the deterministic `dpx/1` writer) are `internal` to `:shared`
 * and stay in its host tests (`HpkeVectorsTest`, `CryptoVectorsTest`). Here: DeriveKeyPair and `p256FromScalar` (the
 * two ECDH operations on a private key with no public part) against the published public keys, ECDH agreement, HPKE
 * open of the AES-256-GCM regression vectors' ciphertexts from their `enc`, the recovery key's text, public key and
 * key id, typed recovery keys, and the `dpx/1` files of the envelope vectors decrypted to their plaintext digest. The
 * official RFC 9180 A.3.1 vector (AES-128-GCM, which only the tests' hook can choose) is covered for its keys.
 */
@RunWith(AndroidJUnit4::class)
class CryptoVectorsOnAndroidTest {
    private val p = platformCryptoProvider()

    private fun asset(name: String): JSONObject =
        JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("crypto/$name").bufferedReader().use { it.readText() })

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun b64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun JSONObject.h(key: String): ByteArray = hex(getString(key))

    private val hpke: JSONObject by lazy { asset("hpke-vectors.json").getJSONObject("hpke") }

    @Test
    fun theHpkeVectorsKeysComeOutOfTheAndroidProvider() {
        val all = hpke.getJSONArray("official").objects() + hpke.getJSONArray("regression").objects()
        assertTrue(all.isNotEmpty())
        for (v in all) {
            val suite = Hpke(p)
            val skE = suite.deriveKeyPair(v.h("ikmE"))
            val skR = suite.deriveKeyPair(v.h("ikmR"))
            assertEquals("pkEm", v.getString("pkEm"), skE.publicKey.hex())
            assertEquals("pkRm", v.getString("pkRm"), skR.publicKey.hex())
            // The published private scalars: the public key of a private key with no public part.
            if (v.has("skEm")) assertEquals(v.getString("pkEm"), p.p256FromScalar(v.h("skEm")).publicKey.hex())
            if (v.has("skRm")) assertEquals(v.getString("pkRm"), p.p256FromScalar(v.h("skRm")).publicKey.hex())
            // ECDH both ways agrees (the provider's KeyAgreement on a key it just made from a scalar).
            val viaScalar = p.p256FromScalar(v.h("skEm").takeIf { v.has("skEm") } ?: ByteArray(32).also { it[31] = 7 })
            val ab = p.p256Agree(viaScalar, skR.publicKey)
            val ba = p.p256Agree(skR, viaScalar.publicKey)
            assertArrayEquals(ab, ba)
        }
    }

    @Test
    fun theAes256GcmRegressionCiphertextsOpenFromTheirEnc() {
        val regression = hpke.getJSONArray("regression").objects()
        assertTrue(regression.isNotEmpty())
        for (v in regression) {
            val suite = Hpke(p) // AES-256-GCM, the suite Doorprints uses
            val skR = suite.deriveKeyPair(v.h("ikmR"))
            val receiver = suite.setupBaseR(v.h("enc"), skR, v.h("info"))
            var seq = 0
            for (e in v.getJSONArray("encryptions").objects()) {
                // Sequence numbers count up with each open; the vectors list them from 0 without a gap.
                assertEquals("seq", seq, e.getInt("seq"))
                assertArrayEquals("seq $seq", e.h("pt"), receiver.open(e.h("aad"), e.h("ct")))
                seq++
            }
        }
    }

    @Test
    fun theRecoveryKeyVectors() {
        val vectors = asset("dpx-vectors.json")
        for (v in vectors.getJSONArray("recovery").objects()) {
            val key = RecoveryKey.fromBytes(v.h("bytes"))
            assertEquals(v.getString("symbols"), key.symbols)
            assertEquals(v.getString("display"), key.display)
            assertArrayEquals(v.h("bytes"), RecoveryKey.parse(v.getString("display")).bytes)
            val pair = key.keyPair(p)
            assertEquals(v.getString("publicKey"), pair.publicKey.hex())
            assertEquals(v.getString("kid"), kidOf(p, pair.publicKey).hex())
        }
        for (v in vectors.getJSONArray("recoveryParse").objects()) {
            val input = v.getString("input")
            if (v.has("bytes")) {
                assertEquals(input, v.getString("bytes"), RecoveryKey.parse(input).bytes.hex())
            } else {
                try {
                    RecoveryKey.parse(input)
                    fail("accepted $input")
                } catch (e: RecoveryKeyException) {
                    assertEquals(input, v.getString("error"), e.reason.name)
                }
            }
        }
    }

    @Test
    fun theDpxFilesOfTheEnvelopeVectorsDecryptToTheirPlaintext() {
        val withFile = asset("dpx-vectors.json").getJSONArray("envelope").objects().filter { it.has("file") }
        assertTrue(withFile.isNotEmpty())
        for (v in withFile) {
            val name = v.getString("name")
            val epoch = v.getInt("epoch")
            val folderKey = b64(v.getString("folderKey"))
            val keys = FolderKeys { e -> if (e == epoch) folderKey.copyOf() else null }
            val (plain, read) = Dpx(p).decryptBytes(
                keys, v.getString("inner"), b64(v.getString("file")), expectedPlaintextSha256 = v.h("plaintextSha256"),
            )
            assertEquals(name, v.getLong("plaintextSize"), plain.size.toLong())
            assertEquals(name, v.getString("plaintextSha256"), sha256(plain).hex())
            assertEquals(name, v.getString("fileSha256"), read.ciphertextSha256.hex())
        }
    }

    @Test
    fun aFreshDpxFileRoundTripsAcrossChunkSizes() {
        val folderKey = p.randomBytes(32)
        val keys = FolderKeys { e -> if (e == 1) folderKey.copyOf() else null }
        val kid = ByteArray(16) { it.toByte() }
        for (size in listOf(0, 1, 65_535, 65_536, 65_537, 3 * 65_536 + 100)) {
            val plaintext = ByteArray(size) { ((31L * it + 7) and 0xFF).toByte() }
            val out = java.io.ByteArrayOutputStream()
            Dpx(p).encrypt(folderKey, 1, kid, "sync/1", Dpx.sourceOf(plaintext), ByteSink { b, o, l -> out.write(b, o, l) })
            assertArrayEquals("size $size", plaintext, Dpx(p).decryptBytes(keys, "sync/1", out.toByteArray()).first)
        }
    }
}
