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

/*
 * The device key where the Android Keystore cannot do ECDH itself (API 26-30: `PURPOSE_AGREE_KEY` exists from API 31).
 * The P-256 scalar is made here, **wrapped under a Keystore AES-GCM key** and stored; it is unwrapped only for one
 * agreement and overwritten at once. What this protects, and what it does not, is in docs/ops/android-drive-a2-notes.md:
 * at rest and while the lock is removed it is as strong as the Keystore; in the process during an agreement the scalar
 * is in memory for milliseconds, which a rooted phone could read.
 */

/** A Keystore AES-GCM key that wraps small secrets. Implementations never reveal the key. */
interface SecretWrapper {
    /** The key store key's state. */
    fun status(): DeviceKeyStatus
    /** Makes the wrapping key if it is not there. */
    fun ensureKey()

    /** AES-GCM of [secret] with [aad]; the result carries its own nonce. */
    fun wrap(secret: ByteArray, aad: ByteArray): ByteArray

    /** The inverse; a wrong [aad] or a changed byte is [DeviceKeyException.Kind.LOST]; a locked key is `NEEDS_UNLOCK`. */
    @Throws(DeviceKeyException::class)
    fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray
    /** Removes the wrapping key, which makes every wrapped secret unreadable. */
    fun deleteKey()
}

/** One small private file (the app's no-backup directory). */
interface BlobStore {
    /** The stored bytes, or null when nothing is stored. */
    fun read(): ByteArray?
    /** Replaces the stored bytes. */
    fun write(data: ByteArray)
    /** Removes the stored bytes. */
    fun delete()
}

/** [DeviceKeyBackend] over a [SecretWrapper] and a [BlobStore]: the blob is `public point (65) ‖ wrapped scalar`. */
class WrappedScalarKeyBackend(
    private val wrapper: SecretWrapper,
    private val blob: BlobStore,
    private val p: CryptoProvider,
) : DeviceKeyBackend {

    override fun status(): DeviceKeyStatus {
        val data = blob.read() ?: return DeviceKeyStatus.ABSENT
        if (data.size <= POINT) return DeviceKeyStatus.INVALIDATED
        return when (val w = wrapper.status()) {
            DeviceKeyStatus.READY, DeviceKeyStatus.NEEDS_UNLOCK -> w
            // The blob is there but its key is gone: Android removed it with the screen lock (docs/15 §10.3).
            DeviceKeyStatus.ABSENT, DeviceKeyStatus.INVALIDATED -> DeviceKeyStatus.INVALIDATED
        }
    }

    /**
      * Makes a fresh P-256 scalar, stores it wrapped (the public point is the AAD) and returns the public point. The
      * scalar is wiped.
     */
    override fun create(): ByteArray {
        wrapper.ensureKey()
        val scalar = newScalar()
        try {
            val pub = p.p256FromScalar(scalar).publicKey
            blob.write(pub + wrapper.wrap(scalar, pub))
            return pub
        } finally {
            scalar.fill(0)
        }
    }

    override fun publicKey(): ByteArray {
        val data = blob.read()
        if (data == null || data.size <= POINT) throw DeviceKeyException(DeviceKeyException.Kind.LOST, "no key stored")
        return data.copyOfRange(0, POINT)
    }

    /**
      * Unwraps the scalar for this one agreement and wipes it afterwards. A scalar that does not match the stored
      * public point
     * is treated as a lost key.
     */
    override fun agree(peerPublic: ByteArray): ByteArray {
        val data = blob.read()
        if (data == null || data.size <= POINT) throw DeviceKeyException(DeviceKeyException.Kind.LOST, "no key stored")
        val pub = data.copyOfRange(0, POINT)
        val scalar = wrapper.unwrap(data.copyOfRange(POINT, data.size), pub)
        try {
            val key = p.p256FromScalar(scalar)
            if (!key.publicKey.contentEquals(pub)) throw DeviceKeyException(DeviceKeyException.Kind.LOST, "stored key does not match its public point")
            return p.p256Agree(key, peerPublic)
        } finally {
            scalar.fill(0)
        }
    }

    override fun discard() {
        blob.delete()
        wrapper.deleteKey()
    }

    private fun newScalar(): ByteArray {
        repeat(8) {
            val s = p.randomBytes(32)
            try {
                p.p256FromScalar(s)
                return s
            } catch (e: CryptoException) {
                s.fill(0)
            }
        }
        throw IllegalStateException("no valid P-256 scalar from the random source")
    }

    private companion object {
        const val POINT = 65
    }
}
