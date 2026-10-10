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

@file:OptIn(ExperimentalEncodingApi::class)

package app.doorprints.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The enrolment QR (docs/15 §9.5 i, S4b-BL-134, S4b-BL-126; web twin: `qr-enrol.ts`). The text is `dp1.` plus
 * base64url (no padding) of `pk_new (65) ‖ s (32)` and, since S4b-BL-144, one platform byte ([platformByte]). `s` is 32
 * bytes: RFC 9180's minimum PSK for HKDF-SHA-256. `psk_id` is [QR_PSK_ID]. The PSK wrap itself is [Hpke.sealPsk] /
 * [Hpke.openPsk] below.
 */
const val QR_PREFIX = "dp1."
const val QR_PSK_LEN = 32
const val QR_PUBLIC_LEN = 65

/** The platform byte after `s` (S4b-BL-144): 0 unknown, 1 android, 2 ios, 3 web; any other value reads as unknown. */
const val QR_PLATFORM_LEN = 1
val QR_PSK_ID: ByteArray get() = Bytes.utf8("doorprints/dpx1/qr-psk")

private fun platformByte(platform: DevicePlatform?): Byte = when (platform) {
    null -> 0
    DevicePlatform.ANDROID -> 1
    DevicePlatform.IOS -> 2
    DevicePlatform.WEB -> 3
}

private fun platformOf(code: Int): DevicePlatform? = when (code) {
    1 -> DevicePlatform.ANDROID
    2 -> DevicePlatform.IOS
    3 -> DevicePlatform.WEB
    else -> null
}

/**
 * What a QR or pasted code holds: the new device's public key, the secret [psk] it showed and its [platform], null
 * when the offer carries none (an offer made before S4b-BL-144) or an unassigned value.
 */
class QrOffer(val publicKey: ByteArray, val psk: ByteArray, val platform: DevicePlatform? = null) {
    override fun toString() = "QrOffer(…)"
}

private val URL_SAFE = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/**
 * The text a new device shows as a QR code and as a code to copy. With [platform] it ends in the platform byte; with
 * null it is the earlier 97-byte form, which every reader still accepts.
 */
fun qrOfferText(publicKey: ByteArray, psk: ByteArray, platform: DevicePlatform? = null): String {
    if (publicKey.size != QR_PUBLIC_LEN || publicKey[0].toInt() != 4) throw IllegalArgumentException("public key")
    if (psk.size != QR_PSK_LEN) throw IllegalArgumentException("psk")
    val body = Bytes.concat(publicKey, psk)
    return QR_PREFIX + URL_SAFE.encode(if (platform == null) body else Bytes.concat(body, byteArrayOf(platformByte(platform))))
}

private fun isUrlSafeText(text: String): Boolean = text.isNotEmpty() && text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

/** The offer inside pasted text or a scanned URL, or null when it is not one. */
fun parseQrOffer(text: String): QrOffer? {
    val trimmed = text.trim()
    val at = trimmed.lastIndexOf(QR_PREFIX)
    if (at < 0) return null
    val body = trimmed.substring(at + QR_PREFIX.length).split(' ', '\t', '\n', '\r', '#', '?', '&').first()
    if (!isUrlSafeText(body)) return null
    // Canonical form only: the same bytes never have two spellings (as `Bytes.unb64` for the standard alphabet).
    val raw = try {
                URL_SAFE.decode(body)
    } catch (_: IllegalArgumentException) {
        return null
    }
        if (URL_SAFE.encode(raw) != body) return null
    val base = QR_PUBLIC_LEN + QR_PSK_LEN
    if ((raw.size != base && raw.size != base + QR_PLATFORM_LEN) || raw[0].toInt() != 4) return null
    val platform = if (raw.size > base) platformOf(raw[base].toInt() and 0xFF) else null
    return QrOffer(raw.copyOfRange(0, QR_PUBLIC_LEN), raw.copyOfRange(QR_PUBLIC_LEN, base), platform)
}

/** SetupPSKS (RFC 9180 §5.1.2, mode 1). [psk] is at least 32 bytes, the suite's Nsk. */
fun Hpke.setupPskS(pkR: ByteArray, info: ByteArray, psk: ByteArray, pskId: ByteArray): Hpke.Sender {
    val (shared, enc) = encap(pkR, generateKeyPair())
    return Hpke.Sender(enc, context(keySchedule(Hpke.MODE_PSK, shared, info, psk, pskId)))
}

/** SetupPSKR (RFC 9180 §5.1.2, mode 1). */
fun Hpke.setupPskR(enc: ByteArray, skR: P256PrivateKey, info: ByteArray, psk: ByteArray, pskId: ByteArray): Hpke.Context =
    context(keySchedule(Hpke.MODE_PSK, decap(enc, skR), info, psk, pskId))

/** Single-shot PSK-mode seal. */
fun Hpke.sealPsk(pkR: ByteArray, info: ByteArray, aad: ByteArray, plaintext: ByteArray, psk: ByteArray, pskId: ByteArray): Hpke.Sealed {
    val s = setupPskS(pkR, info, psk, pskId)
    return Hpke.Sealed(s.enc, s.context.seal(aad, plaintext))
}

/** Single-shot PSK-mode open; a wrong PSK, `psk_id`, key, `enc`, info, AAD or byte throws [CryptoException]. */
fun Hpke.openPsk(enc: ByteArray, skR: P256PrivateKey, info: ByteArray, aad: ByteArray, ciphertext: ByteArray, psk: ByteArray, pskId: ByteArray): ByteArray =
    setupPskR(enc, skR, info, psk, pskId).open(aad, ciphertext)
