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
import app.doorprints.shared.export.iosArchiveTools
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard
import platform.Security.kSecRandomDefault
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

actual fun platformCryptoProvider(): CryptoProvider = IosCryptoProvider

/**
 * The iPhone's provider (S4b-BL-131): each primitive comes from the platform's own implementation where the platform
 * has one, and from [Gcm] and [P256Base] (pure Kotlin, no secret-dependent branch or index) only where it does not.
 * Nothing falls back: a call the platform refuses throws a [CryptoException], never a weaker result.
 *
 * - random: `SecRandomCopyBytes(kSecRandomDefault)`; a non-zero status is a failure, not a short read.
 * - SHA-256 (incremental) and HMAC-SHA-256: CommonCrypto (`CC_SHA256_*` through the shared archive tools, `CCHmac`).
 * - AES-GCM: AES from CommonCrypto (`CCCrypt`, ECB, for the counter blocks and the tag mask, hardware accelerated),
 *   GCM itself in [Gcm]: CommonCrypto's public API has no GCM mode.
 * - P-256 generation and ECDH: Security.framework `SecKey` (non-permanent software keys: nothing is stored in the
 *   Keychain here; Keychain and Secure Enclave storage of device keys is a separate ticket), `ECDHKeyExchangeStandard`
 *   (the raw 32-byte x-coordinate).
 * - P-256 from a scalar: [P256Base] computes the public point, which `SecKeyCreateWithData` needs next to the private
 *   scalar (`04 ‖ x ‖ y ‖ d`, Apple's documented private-key form); the point is also validated by [P256Base].
 * - point validation: [P256Base.isOnCurve] on the decoded coordinates, before Security.framework sees the point.
 *
 * What a simulator run must show (the host cannot run any of it): the shared known answers in
 * `PlatformCryptoProviderTest` and the HPKE vectors in `HpkePlatformVectorsTest` (commonTest), `IosP256Test` and
 * `IosGcmTest` (iosTest); see docs/ops/ios-crypto-notes.md.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal object IosCryptoProvider : CryptoProvider {
    private class IosAesKey(val raw: ByteArray) : AesKey {
        override val sizeBytes: Int get() = raw.size
        override fun toString() = "AesKey"
    }

    /** A `SecKey` released when the object is collected. */
    private class SecKeyBox(val ref: SecKeyRef) {
        @Suppress("unused")
        private val cleaner = createCleaner(ref) { CFRelease(it) }
    }

    private class IosP256Key(val box: SecKeyBox, private val pub: ByteArray) : P256PrivateKey {
        override val publicKey: ByteArray get() = pub.copyOf()
        override fun toString() = "P256PrivateKey"
    }

    // ---- random, hashes ----

    override fun randomBytes(size: Int): ByteArray {
        if (size < 0) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "size")
        val out = ByteArray(size)
        if (size == 0) return out
        val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
        if (status != errSecSuccess) throw CryptoException(CryptoException.Kind.UNAVAILABLE, "SecRandomCopyBytes failed")
        return out
    }

    override fun sha256(): Sha256 = iosArchiveTools.sha256()

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        if (key.isEmpty()) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "empty HMAC key")
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        key.usePinned { k ->
            withPointer(data) { d ->
                out.usePinned { o -> CCHmac(kCCHmacAlgSHA256, k.addressOf(0), key.size.convert(), d, data.size.convert(), o.addressOf(0)) }
            }
        }
        return out
    }

    /** A pointer for [bytes] that is valid inside [block]; a null one for an empty array (CommonCrypto accepts it with length 0). */
    private inline fun <T> withPointer(bytes: ByteArray, block: (COpaquePointer?) -> T): T =
        if (bytes.isEmpty()) block(null) else bytes.usePinned { block(it.addressOf(0)) }

    // ---- AES-GCM ----

    override fun aesKey(raw: ByteArray): AesKey {
        if (raw.size != 16 && raw.size != 32) throw CryptoException(CryptoException.Kind.INVALID_KEY, "AES key length")
        return IosAesKey(raw.copyOf())
    }

    private fun gcm(key: AesKey): Gcm {
        val k = key as? IosAesKey ?: throw CryptoException(CryptoException.Kind.INVALID_KEY, "foreign key")
        return Gcm { blocks -> aesEcb(k.raw, blocks) }
    }

    /** AES-ECB of [blocks] (a multiple of 16 bytes, no padding) under [key]. */
    private fun aesEcb(key: ByteArray, blocks: ByteArray): ByteArray {
        require(blocks.size % 16 == 0)
        val out = ByteArray(blocks.size)
        if (blocks.isEmpty()) return out
        memScoped {
            val moved = alloc<platform.posix.size_tVar>()
            val status = key.usePinned { k ->
                blocks.usePinned { i ->
                    out.usePinned { o ->
                        CCCrypt(
                            kCCEncrypt, kCCAlgorithmAES, kCCOptionECBMode, k.addressOf(0), key.size.convert(), null,
                            i.addressOf(0), blocks.size.convert(), o.addressOf(0), out.size.convert(), moved.ptr,
                        )
                    }
                }
            }
            if (status != kCCSuccess || moved.value.toLong() != blocks.size.toLong()) {
                throw CryptoException(CryptoException.Kind.UNAVAILABLE, "CCCrypt failed")
            }
        }
        return out
    }

    override fun aesGcmSeal(key: AesKey, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        gcm(key).seal(nonce, aad, plaintext)

    override fun aesGcmOpen(key: AesKey, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray =
        gcm(key).open(nonce, aad, sealed)

    // ---- P-256 ----

    override fun p256Generate(): P256PrivateKey {
        val ref = withDictionary(
            kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits to number(256),
        ) { SecKeyCreateRandomKey(it, null) } ?: throw CryptoException(CryptoException.Kind.UNAVAILABLE, "SecKeyCreateRandomKey failed")
        val box = SecKeyBox(ref)
        val publicRef = SecKeyCopyPublicKey(ref) ?: throw CryptoException(CryptoException.Kind.UNAVAILABLE, "no public key")
        val pub = try {
            externalRepresentation(publicRef)
        } finally {
            CFRelease(publicRef)
        }
        // Whatever the platform returned must be a point on the curve, or the key is not used.
        validate(pub)
        return IosP256Key(box, pub)
    }

    override fun p256FromScalar(scalar: ByteArray): P256PrivateKey {
        if (scalar.size != 32 || !P256Scalar.isValid(scalar)) throw CryptoException(CryptoException.Kind.INVALID_KEY, "scalar out of range")
        val pub = P256Base.publicKey(scalar)
        // The import wants public point and scalar in one buffer: that copy of d is wiped as soon as the platform has taken it.
        val material = Bytes.concat(pub, scalar)
        val ref = try {
            importKey(material, kSecAttrKeyClassPrivate)
        } finally {
            material.fill(0)
        } ?: throw CryptoException(CryptoException.Kind.INVALID_KEY, "private key refused")
        val box = SecKeyBox(ref)
        // The platform's view of the public key must be the one computed here, or the import did not mean the same d.
        val publicRef = SecKeyCopyPublicKey(ref) ?: throw CryptoException(CryptoException.Kind.INVALID_KEY, "no public key")
        val platformPub = try {
            externalRepresentation(publicRef)
        } finally {
            CFRelease(publicRef)
        }
        if (!constantTimeEquals(platformPub, pub)) throw CryptoException(CryptoException.Kind.INVALID_KEY, "public key mismatch")
        return IosP256Key(box, pub)
    }

    override fun p256ValidatePublic(encoded: ByteArray): ByteArray {
        validate(encoded)
        return encoded.copyOf()
    }

    private fun validate(encoded: ByteArray) {
        if (encoded.size != 65 || encoded[0] != 0x04.toByte()) {
            throw CryptoException(CryptoException.Kind.INVALID_KEY, "not an uncompressed P-256 point")
        }
        // Range (below p) and curve equation; the pair (0, 0) and every non-point fail the equation.
        if (!P256Base.isOnCurve(encoded.copyOfRange(1, 33), encoded.copyOfRange(33, 65))) {
            throw CryptoException(CryptoException.Kind.INVALID_KEY, "point not on P-256")
        }
    }

    override fun p256Agree(privateKey: P256PrivateKey, peerPublic: ByteArray): ByteArray {
        val k = privateKey as? IosP256Key ?: throw CryptoException(CryptoException.Kind.INVALID_KEY, "foreign key")
        validate(peerPublic)
        val peer = importKey(peerPublic, kSecAttrKeyClassPublic) ?: throw CryptoException(CryptoException.Kind.INVALID_KEY, "public key refused")
        try {
            val parameters = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            val result = try {
                SecKeyCopyKeyExchangeResult(k.box.ref, kSecKeyAlgorithmECDHKeyExchangeStandard, peer, parameters, null)
            } finally {
                CFRelease(parameters)
            } ?: throw CryptoException(CryptoException.Kind.INVALID_KEY, "key agreement failed")
            val secret = try {
                bytesOf(result)
            } finally {
                CFRelease(result)
            }
            if (secret.size != 32) throw CryptoException(CryptoException.Kind.INVALID_KEY, "unexpected shared secret size")
            return secret
        } finally {
            CFRelease(peer)
        }
    }

    // ---- Core Foundation helpers ----

    /** A `SecKey` of the given class from its X9.63 bytes, or null when the platform refuses; the caller releases it. */
    internal fun importKey(x963: ByteArray, keyClass: CFTypeRef?): SecKeyRef? {
        val data = x963.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret<UByteVar>(), x963.size.convert()) }
            ?: throw CryptoException(CryptoException.Kind.UNAVAILABLE, "CFDataCreate failed")
        try {
            return withDictionary(
                kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeyClass to keyClass,
                kSecAttrKeySizeInBits to number(256),
            ) { SecKeyCreateWithData(data, it, null) }
        } finally {
            CFRelease(data)
        }
    }

    internal fun externalRepresentation(key: SecKeyRef): ByteArray {
        val data = SecKeyCopyExternalRepresentation(key, null) ?: throw CryptoException(CryptoException.Kind.UNAVAILABLE, "no external representation")
        try {
            return bytesOf(data)
        } finally {
            CFRelease(data)
        }
    }

    internal fun bytesOf(data: CFDataRef): ByteArray {
        val length = CFDataGetLength(data).toInt()
        if (length == 0) return ByteArray(0)
        val pointer: CPointer<UByteVar> = CFDataGetBytePtr(data) ?: throw CryptoException(CryptoException.Kind.UNAVAILABLE, "empty CFData")
        return pointer.reinterpret<ByteVar>().readBytes(length)
    }

    internal fun number(value: Int): CFTypeRef? = memScoped {
        val v = alloc<kotlinx.cinterop.IntVar>()
        v.value = value
        CFNumberCreate(null, kCFNumberIntType, v.ptr)
    }

    /**
     * A temporary CF dictionary of [entries] for [block]. The key-size value is a `CFNumber` this file created, so it
     * is released here; every other key and value is a framework constant.
     */
    internal fun <T> withDictionary(
        vararg entries: Pair<CFTypeRef?, CFTypeRef?>,
        block: (CFMutableDictionaryRef?) -> T,
    ): T {
        val dictionary = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            entries.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
            return block(dictionary)
        } finally {
            CFRelease(dictionary)
            entries.filter { it.first == kSecAttrKeySizeInBits }.forEach { CFRelease(it.second) }
        }
    }
}
