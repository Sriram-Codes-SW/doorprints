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

/**
 * RFC 9180 Appendix A.3.1 (DHKEM(P-256), HKDF-SHA256, AES-128-GCM, base mode) and the project's AES-256-GCM regression
 * vector 1 (`docs/schemas/hpke-vectors.json`, checked there against two other implementations), embedded so they also
 * run on the iPhone simulator, which cannot read the repository's files (S4b-BL-131). They drive [Hpke] over
 * [platformCryptoProvider]: HKDF (HMAC), DeriveKeyPair (SHA-256, HMAC, scalar to public key), ECDH and AES-GCM, so a
 * wrong primitive anywhere shows here. `HpkeVectorsTest` (host only) reads the file and covers every vector.
 */
class HpkePlatformVectorsTest {
    private val p = platformCryptoProvider()
    private fun h(s: String) = Bytes.unhex(s)
    private fun ByteArray.hx() = Bytes.hex(this)

    private class Vector(
        val aead: Hpke.Aead, val key: String, val baseNonce: String, val ct: String,
    )

    private val info = "4f6465206f6e2061204772656369616e2055726e"
    private val ikmE = "4270e54ffd08d79d5928020af4686d8f6b7d35dbe470265f1f5aa22816ce860e"
    private val ikmR = "668b37171f1072f3cf12ea8a236a45df23fc13b82af3609ad1e354f6ef817550"
    private val pkE = "04a92719c6195d5085104f469a8b9814d5838ff72b60501e2c4466e5e67b325ac98536d7b61a1af4b78e5b7f951c0900be863c403ce65c9bfcb9382657222d18c4"
    private val pkR = "04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0"
    private val shared = "c0d26aeab536609a572b07695d933b589dcf363ff9d93c93adea537aeabb8cb8"
    private val pt = "4265617574792069732074727574682c20747275746820626561757479"
    private val aad = "436f756e742d30"

    private fun run(v: Vector) {
        val hpke = Hpke(p, v.aead)
        val skE = hpke.deriveKeyPair(h(ikmE))
        val skR = hpke.deriveKeyPair(h(ikmR))
        assertEquals(pkE, skE.publicKey.hx())
        assertEquals(pkR, skR.publicKey.hx())
        val (secret, enc) = hpke.encap(skR.publicKey, skE)
        assertEquals(pkE, enc.hx())
        assertEquals(shared, secret.hx())
        assertEquals(shared, hpke.decap(enc, skR).hx())
        val schedule = hpke.keySchedule(Hpke.MODE_BASE, secret, h(info), ByteArray(0), ByteArray(0))
        assertEquals(v.key, schedule.key.hx())
        assertEquals(v.baseNonce, schedule.baseNonce.hx())
        val sender = hpke.setupBaseS(skR.publicKey, h(info), skE)
        val receiver = hpke.setupBaseR(enc, skR, h(info))
        val sealed = sender.context.seal(h(aad), h(pt))
        assertEquals(v.ct, sealed.hx())
        assertContentEquals(h(pt), receiver.open(h(aad), sealed))
    }

    @Test
    fun rfc9180AppendixA31Aes128() = run(
        Vector(
            Hpke.Aead.AES_128_GCM, "868c066ef58aae6dc589b6cfdd18f97e", "4e0bc5018beba4bf004cca59",
            "5ad590bb8baa577f8619db35a36311226a896e7342a6d836d8b7bcd2f20b6c7f9076ac232e3ab2523f39513434",
        ),
    )

    @Test
    fun aes256Regression() = run(
        Vector(
            Hpke.Aead.AES_256_GCM, "642ebea3c09bfa219629599a318e0b88aa0a0996148df927dc5a981e22dabb86", "883881320125125689d28047",
            "518c46e6810fbc55362f7d5995b0f54339d93664ca44e5c74d0f289c4f7983b4781b193de04ad3319f177244de",
        ),
    )
}
