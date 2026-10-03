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

import app.doorprints.shared.export.Sha256

actual fun platformCryptoProvider(): CryptoProvider = UnavailableCryptoProvider

/**
 * The iPhone has no provider yet: every call fails closed with [CryptoException.Kind.UNAVAILABLE], so nothing can
 * be encrypted, opened or wrapped by mistake with a half-built implementation.
 *
 * TODO(S4b-BL-131): iOS CryptoProvider via a CryptoKit shim (AES-GCM, P-256 key agreement with
 * `P256.KeyAgreement.PrivateKey(rawRepresentation:)` for the recovery key, the Secure Enclave for device keys,
 * CommonCrypto for SHA-256 and HMAC), run against the same vectors (docs/schemas/hpke-vectors.json and
 * dpx-vectors.json). The iPhone needs it only once the iOS connect ticket (S4b-BL-117) lands.
 */
internal object UnavailableCryptoProvider : CryptoProvider {
    private fun no(): Nothing =
        throw CryptoException(CryptoException.Kind.UNAVAILABLE, "no CryptoProvider on iOS yet (S4b-BL-131)")

    override fun randomBytes(size: Int): ByteArray = no()
    override fun sha256(): Sha256 = no()
    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = no()
    override fun aesKey(raw: ByteArray): AesKey = no()
    override fun aesGcmSeal(key: AesKey, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray = no()
    override fun aesGcmOpen(key: AesKey, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray = no()
    override fun p256Generate(): P256PrivateKey = no()
    override fun p256FromScalar(scalar: ByteArray): P256PrivateKey = no()
    override fun p256ValidatePublic(encoded: ByteArray): ByteArray = no()
    override fun p256Agree(privateKey: P256PrivateKey, peerPublic: ByteArray): ByteArray = no()
}
