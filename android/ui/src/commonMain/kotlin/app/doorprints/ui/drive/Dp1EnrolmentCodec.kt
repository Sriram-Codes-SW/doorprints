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

package app.doorprints.ui.drive

import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.QR_PSK_LEN
import app.doorprints.crypto.parseQrOffer
import app.doorprints.crypto.qrOfferText
import app.doorprints.drive.connect.EnrolmentWrap
import app.doorprints.drive.enrol.PairingCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The text of the two enrolment messages for the Drive screens ([EnrolmentCodec]), over the shared QR code and pairing
 * code classes. The offer is the website's `dp1.` text (`pk_new ‖ s ‖ platform`, docs/15 §9.5), so a phone and the website can enrol
 * each other; the reply is the website's JSON `{"wrapEnc","wrapCt","epoch"}` for the same reason.
 *
 * The 8-digit code is a check of the offer text: both phones derive it from the offer's public key and secret, so a
 * mistyped or cut-off paste shows a different code on the approving phone. It is not a second factor (the secret is in
 * the offer anyway); the approval itself is the device check on the approving phone and the PSK wrap.
 */
class Dp1EnrolmentCodec(
    private val p: CryptoProvider,
    /** This device's platform, written into the offers it makes (S4b-BL-144) so the approver lists it correctly. */
    private val platform: DevicePlatform,
    /** The name the approving phone lists a phone under: the offer carries no name (the website's `dp1.` has none), only the platform byte. */
    private val newDeviceName: String = NEW_DEVICE_NAME,
) : EnrolmentCodec {
    private val codes = PairingCode(p)

    override fun newOffer(publicKey: ByteArray, deviceName: String): NewcomerOffer {
        val psk = p.randomBytes(QR_PSK_LEN)
        return NewcomerOffer(qrOfferText(publicKey, psk, platform), codeOf(publicKey, psk), psk)
    }

    override fun parseOffer(text: String): ApproverOffer? {
        if (text.length > MAX_TEXT) return null
        val offer = parseQrOffer(text) ?: return null
        // An offer made before S4b-BL-144 has no platform; the device list keeps the earlier guess for it.
        return ApproverOffer(offer.publicKey, newDeviceName, offer.platform ?: LEGACY_PLATFORM, offer.psk, codeOf(offer.publicKey, offer.psk))
    }

    override fun encodeReply(wrap: EnrolmentWrap): String = buildJsonObject {
        put("wrapEnc", wrap.wrapEnc)
        put("wrapCt", wrap.wrapCt)
        put("epoch", wrap.epoch)
    }.toString()

    override fun parseReply(text: String, offer: NewcomerOffer): ReplyWrap? {
        if (text.length > MAX_TEXT) return null
        val obj = try {
            Json.parseToJsonElement(text.trim()) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val enc = (obj["wrapEnc"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val ct = (obj["wrapCt"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val epoch = (obj["epoch"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (enc.isNullOrBlank() || ct.isNullOrBlank() || epoch == null || epoch < 1) return null
        return ReplyWrap(enc, ct, epoch)
    }

    /** The code both phones show: [PairingCode.pairingCode] over the offer's key and secret, with a label that sets it apart from the 8-digit commit flow. */
    fun codeOf(publicKey: ByteArray, psk: ByteArray): String =
        codes.pairingCode(nNew = psk, nApprover = LABEL, pkNew = publicKey, pkApprover = ByteArray(0))

    companion object {
        const val NEW_DEVICE_NAME = "New phone"

        /** The platform listed for an offer that carries none: keys.json has no "unknown", so the earlier guess stays. */
        val LEGACY_PLATFORM = DevicePlatform.ANDROID

        /** Longer than any offer (97 bytes as text) or reply; stops a huge paste before it is parsed. */
        const val MAX_TEXT = 4096
        private val LABEL = "doorprints/dp1/offer-code".encodeToByteArray()
    }
}
