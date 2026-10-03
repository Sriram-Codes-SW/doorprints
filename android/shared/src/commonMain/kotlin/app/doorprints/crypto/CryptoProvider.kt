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

/**
 * The cryptographic primitives the Drive encryption needs (docs/15 §9.2, S4b-BL-125), and nothing else. Everything
 * above them (HKDF, HPKE, the `dpx/1` envelope, `keys.json`) is common code over this interface; the implementations
 * are thin calls into the platform: `javax.crypto` and `java.security` on Android (software keys, API 26+; the
 * Keystore path is a later ticket), WebCrypto on the website (`crypto-provider.ts`), and on the iPhone an `actual` that
 * fails closed until S4b-BL-131.
 *
 * Every failure is a [CryptoException] with a [CryptoException.Kind]; no method returns a partial or unauthenticated
 * result. Byte arrays passed in are not kept (implementations copy what they hold on to).
 */
interface CryptoProvider {
    /** [size] bytes from the platform's cryptographically secure generator. */
    fun randomBytes(size: Int): ByteArray

    /** An incremental SHA-256. */
    fun sha256(): Sha256

    /** HMAC-SHA-256 (RFC 2104) of [data] under [key]; [key] must not be empty (HKDF substitutes the zero salt). */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** An AES key for [aesGcmSeal]/[aesGcmOpen]: [raw] is 16 bytes (only for RFC 9180's AES-128 vectors) or 32. */
    fun aesKey(raw: ByteArray): AesKey

    /**
     * AES-GCM (NIST SP 800-38D) with a 96-bit [nonce] the caller chooses and a 128-bit tag: the ciphertext followed
     * by the tag. The caller guarantees that a (key, nonce) pair is never used twice.
     */
    fun aesGcmSeal(key: AesKey, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /**
     * The inverse of [aesGcmSeal]. Fails closed: a wrong key, nonce, AAD or a changed byte throws
     * [CryptoException.Kind.AUTH_FAILED] and no plaintext is returned.
     */
    fun aesGcmOpen(key: AesKey, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray

    /** A new random P-256 key pair (software key, kept in memory). */
    fun p256Generate(): P256PrivateKey

    /**
     * The P-256 key pair whose private scalar is [scalar] (32 bytes, big-endian, 1 <= d < n, else
     * [CryptoException.Kind.INVALID_KEY]), with its public key computed by the platform (HPKE's DeriveKeyPair and
     * the recovery key, docs/15 §9.4).
     */
    fun p256FromScalar(scalar: ByteArray): P256PrivateKey

    /**
     * Checks that [encoded] is an uncompressed SEC1 P-256 point (65 bytes, `0x04 ‖ x ‖ y`) with x, y < p, on the
     * curve and so not the point at infinity (P-256 has cofactor 1, so no subgroup check is needed), and returns a
     * copy. Anything else throws [CryptoException.Kind.INVALID_KEY].
     */
    fun p256ValidatePublic(encoded: ByteArray): ByteArray

    /** ECDH on P-256: the 32-byte x-coordinate of `d · peer`; [peerPublic] is validated as [p256ValidatePublic]. */
    fun p256Agree(privateKey: P256PrivateKey, peerPublic: ByteArray): ByteArray
}

/** An AES key held by a [CryptoProvider] (the website: a non-extractable `CryptoKey`). */
interface AesKey {
    val sizeBytes: Int
}

/** A P-256 private key held by a [CryptoProvider]; only its public key can be read. */
interface P256PrivateKey {
    /** The uncompressed SEC1 encoding (65 bytes); a copy. */
    val publicKey: ByteArray
}

/** Every failure of the primitives and the formats over them; the message never holds key or plaintext bytes. */
open class CryptoException(val kind: Kind, message: String, cause: Throwable? = null) :
    Exception("crypto ${kind.name}: $message", cause) {

    enum class Kind {
        /** This platform has no implementation yet (the iPhone until S4b-BL-131). */
        UNAVAILABLE,

        /** An AEAD tag did not verify: a wrong key, nonce or AAD, or a changed byte. */
        AUTH_FAILED,

        /** A key or point is not valid (wrong length, not on the curve, out of range). */
        INVALID_KEY,

        /** An argument is not valid (a nonce of the wrong length, an empty HMAC key, a size out of range). */
        INVALID_INPUT,
    }
}

/** The platform's provider (Android: `JvmCryptoProvider`; iPhone: fails closed until S4b-BL-131). */
expect fun platformCryptoProvider(): CryptoProvider

/** One-shot SHA-256. */
fun CryptoProvider.sha256Of(vararg parts: ByteArray): ByteArray = sha256().run {
    for (p in parts) update(p)
    digest()
}

/**
 * Compares two byte arrays in time that depends only on their lengths (for MACs and other secrets). Arrays of
 * different lengths are unequal; the length itself is not secret anywhere it is used.
 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
