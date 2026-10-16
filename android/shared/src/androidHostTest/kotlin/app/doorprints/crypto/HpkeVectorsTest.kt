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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HPKE against RFC 9180 Appendix A.3.1 (official) and the AES-256-GCM regression vectors (TC-U-126), both in
 * `docs/schemas/hpke-vectors.json`, plus the failure cases.
 */
class HpkeVectorsTest {
    private val p = JvmCryptoProvider
    private val hpke = Vectors.load("hpke-vectors.json").getValue("hpke").jsonObject

    private fun suite(v: JsonObject): Hpke {
        assertEquals(0, v.getValue("mode").jsonPrimitive.int)
        assertEquals(Hpke.KEM_ID, v.getValue("kem_id").jsonPrimitive.int)
        assertEquals(Hpke.KDF_ID, v.getValue("kdf_id").jsonPrimitive.int)
        val aead = Hpke.Aead.entries.single { it.id == v.getValue("aead_id").jsonPrimitive.int }
        return Hpke(p, aead)
    }

    private fun run(v: JsonObject) {
        val h = suite(v)
        val skE = h.deriveKeyPair(v.h("ikmE"))
        val skR = h.deriveKeyPair(v.h("ikmR"))
        assertEquals("pkEm", v.s("pkEm"), skE.publicKey.hex())
        assertEquals("pkRm", v.s("pkRm"), skR.publicKey.hex())
        // The published private scalars give the same public keys (the regression vectors list no scalars).
        if (v.containsKey("skEm")) assertEquals(v.s("pkEm"), p.p256FromScalar(v.h("skEm")).publicKey.hex())
        if (v.containsKey("skRm")) assertEquals(v.s("pkRm"), p.p256FromScalar(v.h("skRm")).publicKey.hex())

        val (shared, enc) = h.encap(skR.publicKey, skE)
        assertEquals("enc", v.s("enc"), enc.hex())
        assertEquals("shared_secret", v.s("shared_secret"), shared.hex())
        assertEquals("decap", v.s("shared_secret"), h.decap(enc, skR).hex())
        val schedule = h.keySchedule(Hpke.MODE_BASE, shared, v.h("info"), ByteArray(0), ByteArray(0))
        assertEquals("key", v.s("key"), schedule.key.hex())
        assertEquals("base_nonce", v.s("base_nonce"), schedule.baseNonce.hex())
        assertEquals("exporter_secret", v.s("exporter_secret"), schedule.exporterSecret.hex())

        val sender = h.setupBaseS(skR.publicKey, v.h("info"), skE)
        val receiver = h.setupBaseR(enc, skR, v.h("info"))
        var seq = 0
        for (e in v.getValue("encryptions").jsonArray.map { it.jsonObject }) {
            val target = e.getValue("seq").jsonPrimitive.int
            while (seq < target) {
                // Advance both contexts past the sequence numbers the vector skips.
                receiver.open(ByteArray(0), sender.context.seal(ByteArray(0), ByteArray(0)))
                seq++
            }
            val ct = sender.context.seal(e.h("aad"), e.h("pt"))
            assertEquals("seq $target", e.s("ct"), ct.hex())
            assertArrayEquals(e.h("pt"), receiver.open(e.h("aad"), ct))
            seq++
        }
    }

    @Test
    fun rfc9180AppendixA31() {
        val official = hpke.getValue("official").jsonArray.map { it.jsonObject }
        assertTrue(official.isNotEmpty())
        official.forEach(::run)
    }

    @Test
    fun aes256GcmRegressionVectors() {
        val regression = hpke.getValue("regression").jsonArray.map { it.jsonObject }
        assertTrue(regression.isNotEmpty())
        regression.forEach(::run)
    }

    @Test
    fun openFailsClosed() {
        val h = Hpke(p)
        val r = p.p256Generate()
        val other = p.p256Generate()
        val info = "doorprints/dpx1/wrap".encodeToByteArray()
        val aad = byteArrayOf(9)
        val s = h.seal(r.publicKey, info, aad, ByteArray(32) { it.toByte() })
        assertArrayEquals(ByteArray(32) { it.toByte() }, h.open(s.enc, r, info, aad, s.ciphertext))
        expectKind(CryptoException.Kind.AUTH_FAILED) { h.open(s.enc, other, info, aad, s.ciphertext) }
        expectKind(CryptoException.Kind.AUTH_FAILED) { h.open(s.enc, r, "x".encodeToByteArray(), aad, s.ciphertext) }
        expectKind(CryptoException.Kind.AUTH_FAILED) { h.open(s.enc, r, info, byteArrayOf(8), s.ciphertext) }
        expectKind(CryptoException.Kind.AUTH_FAILED) { h.open(other.publicKey, r, info, aad, s.ciphertext) }
        for (i in s.ciphertext.indices) {
            val bad = s.ciphertext.copyOf().also { it[i] = (it[i].toInt() xor 0x80).toByte() }
            expectKind(CryptoException.Kind.AUTH_FAILED) { h.open(s.enc, r, info, aad, bad) }
        }
        val offCurve = s.enc.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        expectKind(CryptoException.Kind.INVALID_KEY) { h.open(offCurve, r, info, aad, s.ciphertext) }
        expectKind(CryptoException.Kind.INVALID_KEY) { h.open(s.enc.copyOf(33), r, info, aad, s.ciphertext) }
        expectKind(CryptoException.Kind.INVALID_KEY) { h.seal(offCurve, info, aad, ByteArray(1)) }
    }

    @Test
    fun everySealUsesAFreshEphemeralKey() {
        val h = Hpke(p)
        val r = p.p256Generate()
        val a = h.seal(r.publicKey, ByteArray(0), ByteArray(0), ByteArray(32))
        val b = h.seal(r.publicKey, ByteArray(0), ByteArray(0), ByteArray(32))
        assertTrue(!a.enc.contentEquals(b.enc) && !a.ciphertext.contentEquals(b.ciphertext))
    }

    @Test
    fun pskInputsAreCheckedByTheKeySchedule() {
        val h = Hpke(p)
        val ss = ByteArray(32)
        expectKind(CryptoException.Kind.INVALID_INPUT) { h.keySchedule(Hpke.MODE_BASE, ss, ByteArray(0), ByteArray(32), ByteArray(1)) }
        expectKind(CryptoException.Kind.INVALID_INPUT) { h.keySchedule(Hpke.MODE_PSK, ss, ByteArray(0), ByteArray(0), ByteArray(0)) }
        expectKind(CryptoException.Kind.INVALID_INPUT) { h.keySchedule(Hpke.MODE_PSK, ss, ByteArray(0), ByteArray(32), ByteArray(0)) }
        expectKind(CryptoException.Kind.INVALID_INPUT) { h.keySchedule(Hpke.MODE_PSK, ss, ByteArray(0), ByteArray(16), ByteArray(1)) }
        // A PSK-mode schedule differs from base mode (the hook S4b-BL-126's enrolment uses).
        val base = h.keySchedule(Hpke.MODE_BASE, ss, ByteArray(0), ByteArray(0), ByteArray(0))
        val psk = h.keySchedule(Hpke.MODE_PSK, ss, ByteArray(0), ByteArray(32) { 1 }, byteArrayOf(1))
        assertTrue(!base.key.contentEquals(psk.key))
    }
}
