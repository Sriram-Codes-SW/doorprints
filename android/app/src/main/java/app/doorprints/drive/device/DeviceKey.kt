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

import app.doorprints.crypto.CryptoException
import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.P256PrivateKey
import app.doorprints.crypto.DevicePlatform
import app.doorprints.drive.backup.DeviceIdentity

/*
 * The device's P-256 key for Drive (S4b-BL-126/-127, docs/15 §9.3, §10.3). All decisions live here, over a small
 * [DeviceKeyBackend]; the Android Keystore is only in `AndroidDeviceKeys.kt`, so every rule below runs on the JVM with
 * a fake backend.
 */

/** What the platform key store says about this device's key, without using it. */
enum class DeviceKeyStatus {
    /** No key (never made, or the key store was emptied). */
    ABSENT,

    /** The key is there and usable now. */
    READY,

    /** The key is there, but Android wants an unlock first (the 6-hour window of docs/15 §10.3); wait, do not recreate. */
    NEEDS_UNLOCK,

    /** Android invalidated the key (the screen lock was removed, §10.3). It can never be used again. */
    INVALIDATED,
}

/** The platform's key store for the one device key. Implementations never reveal a private key to the caller. */
interface DeviceKeyBackend {
    fun status(): DeviceKeyStatus

    /** Makes the key (the caller has checked [status] is not [DeviceKeyStatus.READY]); returns the 65-byte public point. */
    fun create(): ByteArray

    /** The 65-byte uncompressed public point of an existing key; needs no unlock. */
    fun publicKey(): ByteArray

    /** ECDH of the private key with [peerPublic] (already validated): the 32-byte x coordinate. */
    @Throws(DeviceKeyException::class)
    fun agree(peerPublic: ByteArray): ByteArray

    /** Removes the key entry (an invalidated one, or after a disconnect). Idempotent. */
    fun discard()
}

/** Why the device key could not be used. Never carries key bytes. */
class DeviceKeyException(val kind: Kind, message: String, cause: Throwable? = null) : Exception("device key ${kind.name}: $message", cause) {
    enum class Kind {
        /**
         * The key is gone or invalidated while a folder is pinned to it. The person must connect again and enrol (docs/15
         * §10.3 "a new device"); the app never makes a new key behind their back, and no Drive data is touched.
         */
        LOST,

        /** Android asks for an unlock first; background work waits (§10.3). */
        NEEDS_UNLOCK,
    }
}

/**
 * This device's identity in the folder, over a [DeviceKeyBackend]. The key is made on first use, once ([ensure] is
 * idempotent). A lost key is [DeviceKeyException.Kind.LOST] when [folderPinned] says a folder is bound to it, and is
 * **never** replaced in that case; with no folder it is simply made again.
 */
class KeystoreDeviceIdentity(
    private val backend: DeviceKeyBackend,
    override val name: String,
    override val platform: DevicePlatform = DevicePlatform.ANDROID,
    private val folderPinned: () -> Boolean,
) : DeviceIdentity {

    private var cached: KeystoreP256Key? = null

    /**
     * True once the key store answered [DeviceKeyStatus.READY] and nothing has failed since. A status check is a full ECDH in the
     * key store (a call into the secure hardware), and a sync pass asks for the key and the lock several times, so READY is
     * remembered and the key store is asked again only after a use of the key failed (the guarded backend below) or the key
     * was discarded; any other answer (absent, invalidated, waiting for an unlock) is never remembered.
     */
    @Volatile
    private var readyKnown = false

    /** The backend the keys hand out: a failed use of the key drops the remembered READY and the cached key. */
    private val guarded = object : DeviceKeyBackend by backend {
        override fun agree(peerPublic: ByteArray): ByteArray = try {
            backend.agree(peerPublic)
        } catch (e: DeviceKeyException) {
            forget()
            throw e
        }
    }

    @Synchronized
    private fun forget() {
        readyKnown = false
        cached = null
    }

    /** The state to show: unlike [key] this never creates or throws. */
    fun status(): DeviceKeyStatus {
        if (readyKnown) return DeviceKeyStatus.READY
        return backend.status().also { readyKnown = it == DeviceKeyStatus.READY }
    }

    /** True when a folder is pinned and the key it was enrolled with is gone: the "connect again" state. */
    fun isLost(): Boolean = folderPinned() && status().let { it == DeviceKeyStatus.ABSENT || it == DeviceKeyStatus.INVALIDATED }

    /** Removes the key entry (a removed lock, a disconnect) and forgets what was remembered about it. */
    @Synchronized
    fun discard() {
        forget()
        backend.discard()
    }

    @Synchronized
    fun ensure(): KeystoreP256Key {
        val status = status()
        cached?.let { if (status == DeviceKeyStatus.READY || status == DeviceKeyStatus.NEEDS_UNLOCK) return it }
        cached = null
        val pub = when (status) {
            DeviceKeyStatus.READY, DeviceKeyStatus.NEEDS_UNLOCK -> backend.publicKey()
            DeviceKeyStatus.ABSENT -> {
                if (folderPinned()) throw DeviceKeyException(DeviceKeyException.Kind.LOST, "the key is missing while a folder is pinned")
                backend.create()
            }
            DeviceKeyStatus.INVALIDATED -> {
                if (folderPinned()) throw DeviceKeyException(DeviceKeyException.Kind.LOST, "the key was invalidated while a folder is pinned")
                backend.discard()
                backend.create()
            }
        }
        require(pub.size == 65 && pub[0] == 0x04.toByte()) { "public key must be an uncompressed P-256 point" }
        return KeystoreP256Key(pub.copyOf(), guarded).also { cached = it }
    }

    override val key: P256PrivateKey get() = ensure()
}

/** The [P256PrivateKey] of a key store key: only the public point can be read; ECDH goes through [DeviceKeyCryptoProvider]. */
class KeystoreP256Key internal constructor(private val pub: ByteArray, internal val backend: DeviceKeyBackend) : P256PrivateKey {
    override val publicKey: ByteArray get() = pub.copyOf()
    override fun toString() = "P256PrivateKey(keystore)"
}

/**
 * A [CryptoProvider] that runs the ECDH of a [KeystoreP256Key] in the key store and hands everything else to [delegate]
 * (HPKE's ephemeral keys, AES-GCM, HKDF stay the software provider's). Give **this** provider, not the plain one, to
 * the Drive services on Android: `JvmCryptoProvider.p256Agree` refuses a key it did not make.
 */
class DeviceKeyCryptoProvider(private val delegate: CryptoProvider) : CryptoProvider by delegate {
    override fun p256Agree(privateKey: P256PrivateKey, peerPublic: ByteArray): ByteArray {
        if (privateKey !is KeystoreP256Key) return delegate.p256Agree(privateKey, peerPublic)
        val peer = delegate.p256ValidatePublic(peerPublic)
        val secret = try {
            privateKey.backend.agree(peer)
        } catch (e: DeviceKeyException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException(CryptoException.Kind.INVALID_KEY, "ECDH in the key store", e)
        }
        if (secret.size != 32) throw CryptoException(CryptoException.Kind.INVALID_KEY, "ECDH output length")
        return secret
    }
}
