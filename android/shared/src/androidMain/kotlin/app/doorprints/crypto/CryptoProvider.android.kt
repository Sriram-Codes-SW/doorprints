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
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual fun platformCryptoProvider(): CryptoProvider = JvmCryptoProvider

/**
 * The Android (and JVM test) provider: `javax.crypto` and `java.security` with software keys, so it works on every
 * Android version from API 26 and in the host tests. Keystore-held device keys are a later ticket (docs/15 §9.2,
 * §10.3); this object never touches the Keystore.
 */
object JvmCryptoProvider : CryptoProvider {
    private val random = SecureRandom()

    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }
    private val fieldP: BigInteger by lazy { (params.curve.field as ECFieldFp).p }
    // KeyFactory is not thread-safe: one per thread, made once.
    private val keyFactories = ThreadLocal.withInitial { KeyFactory.getInstance("EC") }
    private val keyFactory: KeyFactory get() = keyFactories.get()

    private class JvmAesKey(val spec: SecretKeySpec, override val sizeBytes: Int) : AesKey

    private class JvmP256Key(val key: PrivateKey, private val pub: ByteArray) : P256PrivateKey {
        override val publicKey: ByteArray get() = pub.copyOf()
        override fun toString() = "P256PrivateKey"
    }

    override fun randomBytes(size: Int): ByteArray {
        if (size < 0) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "size")
        return ByteArray(size).also { random.nextBytes(it) }
    }

    override fun sha256(): Sha256 = object : Sha256 {
        private val md = MessageDigest.getInstance("SHA-256")
        override fun update(bytes: ByteArray, offset: Int, length: Int) = md.update(bytes, offset, length)
        override fun digest(): ByteArray = md.digest()
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        if (key.isEmpty()) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "empty HMAC key")
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
    }

    override fun aesKey(raw: ByteArray): AesKey {
        if (raw.size != 16 && raw.size != 32) throw CryptoException(CryptoException.Kind.INVALID_KEY, "AES key length")
        return JvmAesKey(SecretKeySpec(raw.copyOf(), "AES"), raw.size)
    }

    private fun gcm(mode: Int, key: AesKey, nonce: ByteArray, aad: ByteArray): Cipher {
        if (nonce.size != NONCE) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "nonce length")
        val k = key as? JvmAesKey ?: throw CryptoException(CryptoException.Kind.INVALID_KEY, "foreign key")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, k.spec, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
    }

    override fun aesGcmSeal(key: AesKey, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        gcm(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    override fun aesGcmOpen(key: AesKey, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < TAG_BITS / 8) throw CryptoException(CryptoException.Kind.AUTH_FAILED, "shorter than a tag")
        val cipher = gcm(Cipher.DECRYPT_MODE, key, nonce, aad)
        return try {
            cipher.doFinal(sealed)
        } catch (e: AEADBadTagException) {
            throw CryptoException(CryptoException.Kind.AUTH_FAILED, "tag mismatch", e)
        } catch (e: GeneralSecurityException) {
            throw CryptoException(CryptoException.Kind.AUTH_FAILED, "AES-GCM open", e)
        }
    }

    override fun p256Generate(): P256PrivateKey {
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), random)
            generateKeyPair()
        }
        val w = (pair.public as ECPublicKey).w
        return JvmP256Key(pair.private, encodePoint(w.affineX, w.affineY))
    }

    /**
     * The platform has no call that gives the public key of a raw scalar, so it is found with two of its own ECDH
     * operations, both constant-time inside the platform: x1 = x(d·G) and x2 = x((d+1)·G). x1 fixes the point up to
     * its sign (y or p − y, the square roots of x1³ − 3·x1 + b; p ≡ 3 mod 4); x2 = x(P + G) picks the sign, by one
     * affine addition of the two public candidates and G. Only public values (the candidates, G) enter the
     * `BigInteger` arithmetic; d itself goes only to the platform's key objects.
     */
    override fun p256FromScalar(scalar: ByteArray): P256PrivateKey {
        if (scalar.size != 32 || !P256Scalar.isValid(scalar)) throw CryptoException(CryptoException.Kind.INVALID_KEY, "scalar out of range")
        val d = BigInteger(1, scalar)
        val priv = privateKey(d)
        val g = params.generator
        val gPub = encodePoint(g.affineX, g.affineY)
        val x1 = BigInteger(1, p256Agree(JvmP256Key(priv, ByteArray(0)), gPub))
        val p = fieldP
        val rhs = x1.pow(3).subtract(x1.multiply(THREE)).add(params.curve.b).mod(p)
        val y0 = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        if (y0.multiply(y0).mod(p) != rhs) throw CryptoException(CryptoException.Kind.INVALID_KEY, "no square root")
        val y = if (x1 == g.affineX) {
            // P = ±G, so d is 1 or n − 1 (probability 2⁻²⁵⁵ for a random scalar).
            if (d == BigInteger.ONE) g.affineY else p.subtract(g.affineY)
        } else {
            val x2 = BigInteger(1, p256Agree(JvmP256Key(privateKey(d.add(BigInteger.ONE)), ByteArray(0)), gPub))
            val plus = addX(x1, y0, g.affineX, g.affineY)
            val minus = addX(x1, p.subtract(y0).mod(p), g.affineX, g.affineY)
            when {
                plus == x2 && minus != x2 -> y0
                minus == x2 && plus != x2 -> p.subtract(y0).mod(p)
                else -> throw CryptoException(CryptoException.Kind.INVALID_KEY, "public key not determined")
            }
        }
        val encoded = encodePoint(x1, y)
        p256ValidatePublic(encoded)
        return JvmP256Key(priv, encoded)
    }

    /** x(P + Q) for two distinct affine points with different x (public values only). */
    private fun addX(x1: BigInteger, y1: BigInteger, x2: BigInteger, y2: BigInteger): BigInteger {
        val p = fieldP
        val lambda = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(p)).mod(p)
        return lambda.multiply(lambda).subtract(x1).subtract(x2).mod(p)
    }

    private fun privateKey(d: BigInteger): PrivateKey = keyFactory.generatePrivate(ECPrivateKeySpec(d, params))

    override fun p256ValidatePublic(encoded: ByteArray): ByteArray {
        decodePoint(encoded)
        return encoded.copyOf()
    }

    private fun decodePoint(encoded: ByteArray): ECPoint {
        if (encoded.size != 65 || encoded[0] != 0x04.toByte()) throw CryptoException(CryptoException.Kind.INVALID_KEY, "not an uncompressed P-256 point")
        val x = BigInteger(1, encoded.copyOfRange(1, 33))
        val y = BigInteger(1, encoded.copyOfRange(33, 65))
        val p = fieldP
        if (x >= p || y >= p) throw CryptoException(CryptoException.Kind.INVALID_KEY, "coordinate out of range")
        val lhs = y.multiply(y).mod(p)
        val rhs = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        if (lhs != rhs) throw CryptoException(CryptoException.Kind.INVALID_KEY, "point not on P-256")
        return ECPoint(x, y)
    }

    override fun p256Agree(privateKey: P256PrivateKey, peerPublic: ByteArray): ByteArray {
        val k = privateKey as? JvmP256Key ?: throw CryptoException(CryptoException.Kind.INVALID_KEY, "foreign key")
        val peer = keyFactory.generatePublic(ECPublicKeySpec(decodePoint(peerPublic), params))
        val secret = try {
            KeyAgreement.getInstance("ECDH").run {
                init(k.key)
                doPhase(peer, true)
                generateSecret()
            }
        } catch (e: GeneralSecurityException) {
            throw CryptoException(CryptoException.Kind.INVALID_KEY, "ECDH", e)
        }
        return when {
            secret.size == 32 -> secret
            secret.size < 32 -> ByteArray(32 - secret.size) + secret
            else -> throw CryptoException(CryptoException.Kind.INVALID_KEY, "ECDH output length")
        }
    }

    private fun encodePoint(x: BigInteger, y: BigInteger): ByteArray {
        val out = ByteArray(65)
        out[0] = 0x04
        fixed32(x).copyInto(out, 1)
        fixed32(y).copyInto(out, 33)
        return out
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size == 33 && b[0] == 0.toByte() -> b.copyOfRange(1, 33)
            b.size < 32 -> ByteArray(32 - b.size) + b
            else -> throw CryptoException(CryptoException.Kind.INVALID_KEY, "coordinate too long")
        }
    }

    private const val NONCE = 12
    private const val TAG_BITS = 128
    private val THREE = BigInteger.valueOf(3)
}
