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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The `dp1.` payload and the HPKE PSK wrap (S4b-BL-126; web twin: `qr-enrol.spec.ts`, same cases plus the edges). */
class QrEnrolTest {
    private val p = JvmCryptoProvider

    @Test
    fun roundTripsThePublicKeyAndThePskIncludingAPastedUrl() {
        val key = p.p256Generate()
        val psk = p.randomBytes(QR_PSK_LEN)
        val text = qrOfferText(key.publicKey, psk)
        assertTrue(text.startsWith("dp1."))
        assertEquals(4 + 130, text.length)
        val parsed = parseQrOffer("https://doorprints.web.app/enrol#$text")
        assertNotNull(parsed)
        assertArrayEquals(key.publicKey, parsed!!.publicKey)
        assertArrayEquals(psk, parsed.psk)
        assertNull(parseQrOffer("not-a-code"))
    }

    @Test
    fun theTextIsBase64UrlOfPublicKeyThenPskWithoutPadding() {
        val pub = ByteArray(65) { if (it == 0) 4 else 0xfb.toByte() }
        val psk = ByteArray(32) { 0xff.toByte() }
        val text = qrOfferText(pub, psk)
        assertTrue(text.none { it == '+' || it == '/' || it == '=' })
        assertEquals(Bytes.hex(pub + psk), Bytes.hex(parseQrOffer(text)!!.let { it.publicKey + it.psk }))
        assertTrue(text.contains('-') || text.contains('_'))
    }

    @Test
    fun readsTheCodeFromWhitespaceQueryAndFragmentsButNothingElse() {
        val key = p.p256Generate()
        val psk = p.randomBytes(32)
        val text = qrOfferText(key.publicKey, psk)
        for (wrapped in listOf("  $text\n", "$text&x=1", "$text?x=1", "see $text and more", "https://x/y?c=$text#frag")) {
            assertArrayEquals(wrapped, key.publicKey, parseQrOffer(wrapped)?.publicKey)
        }
        // Truncated, padded, wrong first byte, non-alphabet, wrong length: none is an offer.
        assertNull(parseQrOffer(text.dropLast(1)))
        assertNull(parseQrOffer(text + "="))
        assertNull(parseQrOffer("dp1.AAAA"))
        assertNull(parseQrOffer("dp1."))
        assertNull(parseQrOffer("dp1.${"+".repeat(130)}"))
        val raw = ByteArray(97) { 7 }
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        assertNull(parseQrOffer("dp1." + encoder.encodeToString(raw.also { it[0] = 5 })))
        val good = "dp1." + encoder.encodeToString(raw.also { it[0] = 4 })
        assertNotNull(parseQrOffer(good))
        // The same bytes spelled another way (non-zero spare bits in the last character) are not accepted.
        assertEquals('w', good.last())
        assertNull(parseQrOffer(good.dropLast(1) + "x"))
    }

    @Test
    fun refusesAnOfferOfTheWrongShapeWhenMaking() {
        val key = p.p256Generate()
        try {
            qrOfferText(key.publicKey.copyOf(64), ByteArray(32)); fail()
        } catch (_: IllegalArgumentException) {
        }
        try {
            qrOfferText(key.publicKey, ByteArray(31)); fail()
        } catch (_: IllegalArgumentException) {
        }
        try {
            qrOfferText(ByteArray(65), ByteArray(32)); fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun aPskWrapOpensOnlyWithTheSamePskAndTheSamePublicKey() {
        val recipient = p.p256Generate()
        val psk = p.randomBytes(32)
        val folder = p.randomBytes(32)
        val kid = kidOf(p, recipient.publicKey)
        val hpke = Hpke(p)
        val aad = WrapAad.folderKey(1, kid)
        val sealed = hpke.sealPsk(recipient.publicKey, WrapAad.HPKE_INFO, aad, folder, psk, QR_PSK_ID)
        assertArrayEquals(folder, hpke.openPsk(sealed.enc, recipient, WrapAad.HPKE_INFO, aad, sealed.ciphertext, psk, QR_PSK_ID))

        val wrong = psk.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        expectAuthFailure { hpke.openPsk(sealed.enc, recipient, WrapAad.HPKE_INFO, aad, sealed.ciphertext, wrong, QR_PSK_ID) }
        val other = p.p256Generate()
        expectFailure { hpke.openPsk(sealed.enc, other, WrapAad.HPKE_INFO, WrapAad.folderKey(1, kidOf(p, other.publicKey)), sealed.ciphertext, psk, QR_PSK_ID) }
        // The right key and PSK but another epoch, another psk_id, or a base-mode open of the same bytes: all refused.
        expectAuthFailure { hpke.openPsk(sealed.enc, recipient, WrapAad.HPKE_INFO, WrapAad.folderKey(2, kid), sealed.ciphertext, psk, QR_PSK_ID) }
        expectAuthFailure { hpke.openPsk(sealed.enc, recipient, WrapAad.HPKE_INFO, aad, sealed.ciphertext, psk, "other".encodeToByteArray()) }
        expectAuthFailure { hpke.open(sealed.enc, recipient, WrapAad.HPKE_INFO, aad, sealed.ciphertext) }
    }

    @Test
    fun theConstantsAreTheWebsitesConstants() {
        assertEquals("dp1.", QR_PREFIX)
        assertEquals(32, QR_PSK_LEN)
        assertEquals(65, QR_PUBLIC_LEN)
        assertEquals("doorprints/dpx1/qr-psk", String(QR_PSK_ID, Charsets.UTF_8))
    }

    @Test
    fun aPskShorterThan32BytesIsRefused() {
        val recipient = p.p256Generate()
        expectFailure { Hpke(p).sealPsk(recipient.publicKey, WrapAad.HPKE_INFO, ByteArray(0), ByteArray(32), ByteArray(16), QR_PSK_ID) }
        expectFailure { Hpke(p).sealPsk(recipient.publicKey, WrapAad.HPKE_INFO, ByteArray(0), ByteArray(32), ByteArray(0), ByteArray(0)) }
    }

    private fun expectAuthFailure(block: () -> Unit) {
        try {
            block()
            fail("expected a failure")
        } catch (e: CryptoException) {
            assertEquals(CryptoException.Kind.AUTH_FAILED, e.kind)
        }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("expected a failure")
        } catch (_: CryptoException) {
        }
    }
}
