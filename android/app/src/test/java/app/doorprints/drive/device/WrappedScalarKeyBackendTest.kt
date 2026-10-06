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

import app.doorprints.crypto.AesKey
import app.doorprints.crypto.platformCryptoProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A wrapping key with real AES-GCM (the provider's), standing in for the Keystore AES key. */
class FakeWrapper : SecretWrapper {
    private val p = platformCryptoProvider()
    private val key: AesKey = p.aesKey(ByteArray(32) { it.toByte() })
    var state = DeviceKeyStatus.ABSENT
    var deleted = 0

    override fun status() = state
    override fun ensureKey() {
        if (state == DeviceKeyStatus.ABSENT) state = DeviceKeyStatus.READY
    }
    override fun wrap(secret: ByteArray, aad: ByteArray): ByteArray {
        val nonce = p.randomBytes(12)
        return nonce + p.aesGcmSeal(key, nonce, aad, secret)
    }
    override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray {
        if (state == DeviceKeyStatus.NEEDS_UNLOCK) throw DeviceKeyException(DeviceKeyException.Kind.NEEDS_UNLOCK, "locked")
        if (state != DeviceKeyStatus.READY) throw DeviceKeyException(DeviceKeyException.Kind.LOST, "gone")
        return try {
            p.aesGcmOpen(key, wrapped.copyOfRange(0, 12), aad, wrapped.copyOfRange(12, wrapped.size))
        } catch (e: app.doorprints.crypto.CryptoException) {
            throw DeviceKeyException(DeviceKeyException.Kind.LOST, "wrapped key does not open", e)
        }
    }
    override fun deleteKey() {
        deleted++
        state = DeviceKeyStatus.ABSENT
    }
}

class MemoryBlob : BlobStore {
    var bytes: ByteArray? = null
    override fun read() = bytes?.copyOf()
    override fun write(data: ByteArray) { bytes = data.copyOf() }
    override fun delete() { bytes = null }
}

class WrappedScalarKeyBackendTest {
    private val p = platformCryptoProvider()
    private val wrapper = FakeWrapper()
    private val blob = MemoryBlob()
    private val backend = WrappedScalarKeyBackend(wrapper, blob, p)

    @Test fun `nothing stored is absent`() {
        assertEquals(DeviceKeyStatus.ABSENT, backend.status())
    }

    @Test fun `create stores a wrapped key and returns the public point`() {
        val pub = backend.create()
        assertEquals(65, pub.size)
        assertEquals(DeviceKeyStatus.READY, backend.status())
        assertArrayEquals(pub, backend.publicKey())
        assertTrue(blob.bytes!!.size > 65 + 32)
    }

    @Test fun `the stored bytes hold no scalar the provider can read back`() {
        val pub = backend.create()
        val peer = p.p256Generate()
        val expected = backend.agree(peer.publicKey)
        // the blob is public point then AES-GCM output; flipping the scalar part must make the agreement fail
        val stored = blob.bytes!!
        stored[stored.size - 1] = (stored[stored.size - 1] + 1).toByte()
        blob.bytes = stored
        assertThrows(DeviceKeyException::class.java) { backend.agree(peer.publicKey) }
        assertEquals(32, expected.size)
        assertEquals(65, pub.size)
    }

    @Test fun `the agreement equals the software ECDH with the same peer`() {
        val pub = backend.create()
        val peer = p.p256Generate()
        assertArrayEquals(p.p256Agree(peer, pub), backend.agree(peer.publicKey))
    }

    @Test fun `a changed public point is refused because it is the AAD`() {
        backend.create()
        val stored = blob.bytes!!
        stored[10] = (stored[10] + 1).toByte()
        blob.bytes = stored
        val e = assertThrows(DeviceKeyException::class.java) { backend.agree(p.p256Generate().publicKey) }
        assertEquals(DeviceKeyException.Kind.LOST, e.kind)
    }

    @Test fun `a stored public point that is not the wrapped scalar's is refused even when it authenticates`() {
        wrapper.ensureKey()
        val scalar = p.randomBytes(32)
        val other = p.p256Generate().publicKey
        blob.bytes = other + wrapper.wrap(scalar, other)
        val e = assertThrows(DeviceKeyException::class.java) { backend.agree(p.p256Generate().publicKey) }
        assertEquals(DeviceKeyException.Kind.LOST, e.kind)
    }

    @Test fun `a wrapping key that is gone while the blob remains is invalidated`() {
        backend.create()
        wrapper.state = DeviceKeyStatus.INVALIDATED
        assertEquals(DeviceKeyStatus.INVALIDATED, backend.status())
        wrapper.state = DeviceKeyStatus.ABSENT
        assertEquals(DeviceKeyStatus.INVALIDATED, backend.status())
    }

    @Test fun `a wrapping key waiting for an unlock is reported and the blob is kept`() {
        backend.create()
        wrapper.state = DeviceKeyStatus.NEEDS_UNLOCK
        assertEquals(DeviceKeyStatus.NEEDS_UNLOCK, backend.status())
        assertThrows(DeviceKeyException::class.java) { backend.agree(p.p256Generate().publicKey) }.also {
            assertEquals(DeviceKeyException.Kind.NEEDS_UNLOCK, it.kind)
        }
        assertTrue(blob.bytes != null)
    }

    @Test fun `a wrapping key with no blob is absent`() {
        wrapper.state = DeviceKeyStatus.READY
        assertEquals(DeviceKeyStatus.ABSENT, backend.status())
    }

    @Test fun `discard removes both and is idempotent`() {
        backend.create()
        backend.discard()
        backend.discard()
        assertNull(blob.bytes)
        assertEquals(DeviceKeyStatus.ABSENT, backend.status())
        assertFalse(wrapper.deleted == 0)
    }

    @Test fun `each create makes a different key`() {
        val a = backend.create()
        backend.discard()
        val b = backend.create()
        assertFalse(a.contentEquals(b))
    }
}
