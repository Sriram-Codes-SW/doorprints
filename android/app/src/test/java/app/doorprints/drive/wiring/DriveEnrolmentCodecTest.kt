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

package app.doorprints.drive.wiring

import app.doorprints.ui.drive.Dp1EnrolmentCodec
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.crypto.QR_PREFIX
import app.doorprints.crypto.QR_PSK_LEN
import app.doorprints.crypto.parseQrOffer
import app.doorprints.drive.connect.EnrolmentWrap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `dp1.` offer, the reply and the 8-digit code of the Drive screens (docs/15 §9.5; the website's formats). */
class DriveEnrolmentCodecTest {
    private val p = JvmCryptoProvider
    private val codec = Dp1EnrolmentCodec(p)
    private val key = p.p256Generate().publicKey

    @Test
    fun theOfferIsTheWebsitesDp1TextWithAFreshSecret() {
        val a = codec.newOffer(key, "Pixel 8")
        val b = codec.newOffer(key, "Pixel 8")
        assertTrue(a.qrText.startsWith(QR_PREFIX))
        assertEquals(QR_PSK_LEN, a.psk.size)
        assertNotEquals("every offer has its own secret", a.qrText, b.qrText)
        val parsed = parseQrOffer(a.qrText)!!
        assertArrayEquals(key, parsed.publicKey)
        assertArrayEquals(a.psk, parsed.psk)
    }

    @Test
    fun bothPhonesShowTheSameEightDigits() {
        val offer = codec.newOffer(key, "Pixel 8")
        val approver = codec.parseOffer(offer.qrText)!!
        assertEquals(offer.code, approver.code)
        assertTrue(Regex("\\d{8}").matches(offer.code))
    }

    @Test
    fun theCodeDependsOnBothTheKeyAndTheSecret() {
        val psk = ByteArray(QR_PSK_LEN) { 7 }
        val base = codec.codeOf(key, psk)
        assertEquals("the same offer, the same code", base, codec.codeOf(key.copyOf(), psk.copyOf()))
        assertNotEquals(base, codec.codeOf(p.p256Generate().publicKey, psk))
        assertNotEquals(base, codec.codeOf(key, ByteArray(QR_PSK_LEN) { 8 }))
    }

    @Test
    fun aMistypedOfferShowsADifferentCodeOrIsRefused() {
        val offer = codec.newOffer(key, "Pixel 8")
        val tampered = offer.qrText.dropLast(3) + (if (offer.qrText.takeLast(3) == "AAA") "BBB" else "AAA")
        val parsed = codec.parseOffer(tampered)
        if (parsed != null) assertNotEquals(offer.code, parsed.code)
    }

    @Test
    fun theApproverReadsTheKeyTheSecretAndAGenericName() {
        val offer = codec.newOffer(key, "Pixel 8")
        val approver = codec.parseOffer("  " + offer.qrText + "\n")!!
        assertArrayEquals(key, approver.publicKey)
        assertArrayEquals(offer.psk, approver.psk)
        assertEquals(Dp1EnrolmentCodec.NEW_DEVICE_NAME, approver.deviceName)
        assertEquals(DevicePlatform.ANDROID, approver.platform)
    }

    @Test
    fun textThatIsNotAnOfferIsRefused() {
        assertNull(codec.parseOffer(""))
        assertNull(codec.parseOffer("hello"))
        assertNull(codec.parseOffer("dp1.not-base64!"))
        assertNull(codec.parseOffer("dp1." + "A".repeat(10)))
        assertNull(codec.parseOffer("dp1." + "A".repeat(Dp1EnrolmentCodec.MAX_TEXT)))
    }

    @Test
    fun theReplyIsTheWebsitesJsonAndReadsBack() {
        val offer = codec.newOffer(key, "Pixel 8")
        val text = codec.encodeReply(EnrolmentWrap("ZW5j", "Y3Q=", 3))
        assertTrue(text.contains("\"wrapEnc\":\"ZW5j\""))
        val reply = codec.parseReply(text, offer)!!
        assertEquals("ZW5j", reply.enc)
        assertEquals("Y3Q=", reply.ct)
        assertEquals(3, reply.epoch)
    }

    @Test
    fun theWebsitesOwnReplyIsAccepted() {
        val offer = codec.newOffer(key, "Pixel 8")
        val reply = codec.parseReply("{\"wrapEnc\":\"abc\",\"wrapCt\":\"def\",\"epoch\":2}", offer)
        assertNotNull(reply)
        assertEquals(2, reply!!.epoch)
    }

    @Test
    fun aBadReplyIsRefused() {
        val offer = codec.newOffer(key, "Pixel 8")
        for (text in listOf(
            "", "nope", "[]", "{}", "{\"wrapEnc\":\"a\",\"wrapCt\":\"b\"}",
            "{\"wrapEnc\":\"\",\"wrapCt\":\"b\",\"epoch\":1}", "{\"wrapEnc\":\"a\",\"wrapCt\":\" \",\"epoch\":1}",
            "{\"wrapEnc\":\"a\",\"wrapCt\":\"b\",\"epoch\":0}", "{\"wrapEnc\":\"a\",\"wrapCt\":\"b\",\"epoch\":\"1\"}",
            "{\"wrapEnc\":1,\"wrapCt\":\"b\",\"epoch\":1}",
            "x".repeat(Dp1EnrolmentCodec.MAX_TEXT + 1),
        )) assertNull("accepted: $text", codec.parseReply(text, offer))
    }

    @Test
    fun theOfferNeverShowsItsSecretInToString() {
        val offer = codec.newOffer(key, "Pixel 8")
        assertTrue(!offer.toString().contains(offer.qrText))
        assertTrue(!codec.parseOffer(offer.qrText)!!.toString().contains(offer.qrText))
    }
}
