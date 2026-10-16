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

package app.doorprints.drive.device

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import app.doorprints.crypto.CryptoProvider
import java.io.File
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * The Android Keystore adapters of the device key (S4b-BL-127). Thin by design: every decision is in `DeviceKey.kt` and
 * `WrappedScalarKeyBackend.kt`, which the JVM tests cover; the code here only calls the framework. The Keystore itself
 * has no Robolectric implementation, so these classes are compiled and reviewed, and exercised on a device or
 * emulator (docs/ops/android-drive-a2-notes.md, "Not verified").
 *
 * Keys are made with `setUserAuthenticationRequired(true)` and a 6-hour window after an unlock (docs/15 §10.3), which
 * Android permanently invalidates when the screen lock is removed. Making one without a screen lock fails, which is
 * the same rule as "Drive can only be switched on with a lock".
 */
internal object KeystoreErrors {
    /** What a framework error means for the key, walking the causes; null: nothing the key store has said about the key. */
    fun classify(e: Throwable?): DeviceKeyException.Kind? {
        var t = e
        var depth = 0
        while (t != null && depth++ < 6) {
            when (t) {
                is KeyPermanentlyInvalidatedException, is UnrecoverableKeyException -> return DeviceKeyException.Kind.LOST
                is UserNotAuthenticatedException -> return DeviceKeyException.Kind.NEEDS_UNLOCK
            }
            t = t.cause
        }
        return null
    }

    /** Wraps a framework failure as a [DeviceKeyException] of the kind [classify] finds. */
    fun toException(what: String, e: Exception): DeviceKeyException =
        // An unknown failure waits (NEEDS_UNLOCK) instead of declaring the key lost: only the key store's own word loses a key.
        DeviceKeyException(classify(e) ?: DeviceKeyException.Kind.NEEDS_UNLOCK, what, e)
}

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val WINDOW_SECONDS = 6 * 60 * 60

private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

private fun KeyGenParameterSpec.Builder.authWindow(): KeyGenParameterSpec.Builder = apply {
    setUserAuthenticationRequired(true)
    if (Build.VERSION.SDK_INT >= 30) {
        setUserAuthenticationParameters(WINDOW_SECONDS, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
    } else {
        @Suppress("DEPRECATION")
        setUserAuthenticationValidityDurationSeconds(WINDOW_SECONDS)
    }
}

/** API 31+: a non-exportable EC key that does the ECDH itself (`PURPOSE_AGREE_KEY`). */
class KeystoreAgreeBackend(private val alias: String = "doorprints_drive_device_ec") : DeviceKeyBackend {

    override fun status(): DeviceKeyStatus {
        val ks = keyStore()
        if (!ks.containsAlias(alias)) return DeviceKeyStatus.ABSENT
        return try {
            agree(publicKey())
            DeviceKeyStatus.READY
        } catch (e: DeviceKeyException) {
            if (e.kind == DeviceKeyException.Kind.LOST) DeviceKeyStatus.INVALIDATED else DeviceKeyStatus.NEEDS_UNLOCK
        }
    }

    override fun create(): ByteArray {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .authWindow()
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
        return publicKey()
    }

    override fun publicKey(): ByteArray {
        val cert = keyStore().getCertificate(alias) ?: throw DeviceKeyException(DeviceKeyException.Kind.LOST, "no key")
        val w = (cert.publicKey as ECPublicKey).w
        return encode(w.affineX, w.affineY)
    }

    override fun agree(peerPublic: ByteArray): ByteArray = try {
        val key = keyStore().getKey(alias, null) as? PrivateKey ?: throw DeviceKeyException(DeviceKeyException.Kind.LOST, "no key")
        val params = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
        val point = ECPoint(BigInteger(1, peerPublic.copyOfRange(1, 33)), BigInteger(1, peerPublic.copyOfRange(33, 65)))
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val secret = KeyAgreement.getInstance("ECDH", ANDROID_KEYSTORE).run {
            init(key)
            doPhase(peer, true)
            generateSecret()
        }
        if (secret.size >= 32) secret.copyOfRange(secret.size - 32, secret.size) else ByteArray(32 - secret.size) + secret
    } catch (e: DeviceKeyException) {
        throw e
    } catch (e: Exception) {
        throw KeystoreErrors.toException("ECDH in the Keystore", e)
    }

    override fun discard() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun encode(x: BigInteger, y: BigInteger): ByteArray {
        fun fixed(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            return if (raw.size >= 32) raw.copyOfRange(raw.size - 32, raw.size) else ByteArray(32 - raw.size) + raw
        }
        return byteArrayOf(0x04) + fixed(x) + fixed(y)
    }
}

/** API 26-30: a Keystore AES-GCM key that wraps the scalar of [WrappedScalarKeyBackend]. */
class KeystoreSecretWrapper(private val alias: String = "doorprints_drive_device_wrap") : SecretWrapper {

    override fun status(): DeviceKeyStatus {
        if (!keyStore().containsAlias(alias)) return DeviceKeyStatus.ABSENT
        return try {
            Cipher.getInstance(TRANSFORM).init(Cipher.ENCRYPT_MODE, key())
            DeviceKeyStatus.READY
        } catch (e: Exception) {
            if (KeystoreErrors.classify(e) == DeviceKeyException.Kind.LOST) DeviceKeyStatus.INVALIDATED else DeviceKeyStatus.NEEDS_UNLOCK
        }
    }

    override fun ensureKey() {
        if (keyStore().containsAlias(alias)) return
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .authWindow()
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    override fun wrap(secret: ByteArray, aad: ByteArray): ByteArray = try {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad)
        c.iv + c.doFinal(secret)
    } catch (e: Exception) {
        throw KeystoreErrors.toException("wrap", e)
    }

    override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray = try {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, wrapped, 0, IV))
        c.updateAAD(aad)
        c.doFinal(wrapped, IV, wrapped.size - IV)
    } catch (e: Exception) {
        // A tag that does not verify is a changed blob, which is as lost as a missing key; the Keystore's own words decide the rest.
        throw DeviceKeyException(KeystoreErrors.classify(e) ?: DeviceKeyException.Kind.LOST, "unwrap", e)
    }

    override fun deleteKey() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun key(): SecretKey =
        keyStore().getKey(alias, null) as? SecretKey ?: throw DeviceKeyException(DeviceKeyException.Kind.LOST, "no wrapping key")

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
    }
}

/** One private file in the app's no-backup directory, so a restored phone never gets a blob without its Keystore key. */
class FileBlobStore(private val file: File) : BlobStore {
    override fun read(): ByteArray? = if (file.isFile) file.readBytes() else null

    override fun write(data: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("could not write the device key file")
        }
    }

    override fun delete() {
        file.delete()
    }
}

/** The right backend for this phone: the Keystore's own ECDH from API 31, a Keystore-wrapped scalar below. */
object AndroidDeviceKeys {
    /** The device-key backend for [sdk]; [provider] is the crypto the wrapped-scalar backend uses below API 31. */
    fun backend(context: Context, provider: CryptoProvider, sdk: Int = Build.VERSION.SDK_INT): DeviceKeyBackend =
        if (sdk >= 31) {
            KeystoreAgreeBackend()
        } else {
            WrappedScalarKeyBackend(KeystoreSecretWrapper(), FileBlobStore(File(context.noBackupFilesDir, "drive-device-key.bin")), provider)
        }
}
