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
import app.doorprints.crypto.platformCryptoProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A key store in memory: the key is a real software P-256 key, so the ECDH can be compared with the provider's. */
class FakeKeyBackend : DeviceKeyBackend {
    private val p = platformCryptoProvider()
    var state = DeviceKeyStatus.ABSENT
    var creates = 0
    var discards = 0
    private var key = p.p256Generate()
    var agrees = 0

    override fun status() = state
    override fun create(): ByteArray {
        creates++
        key = p.p256Generate()
        state = DeviceKeyStatus.READY
        return key.publicKey
    }
    override fun publicKey(): ByteArray = key.publicKey
    override fun agree(peerPublic: ByteArray): ByteArray {
        agrees++
        if (state == DeviceKeyStatus.NEEDS_UNLOCK) throw DeviceKeyException(DeviceKeyException.Kind.NEEDS_UNLOCK, "locked")
        if (state != DeviceKeyStatus.READY) throw DeviceKeyException(DeviceKeyException.Kind.LOST, "gone")
        return p.p256Agree(key, peerPublic)
    }
    override fun discard() {
        discards++
        state = DeviceKeyStatus.ABSENT
    }
}

class DeviceKeyTest {
    private val backend = FakeKeyBackend()
    private var pinned = false
    private val identity = KeystoreDeviceIdentity(backend, "Pixel", folderPinned = { pinned })

    @Test fun `the key is made on first use, once, and the public key is a 65 byte point`() {
        assertEquals(0, backend.creates)
        val a = identity.key.publicKey
        val b = identity.key.publicKey
        assertEquals(1, backend.creates)
        assertEquals(65, a.size)
        assertEquals(0x04.toByte(), a[0])
        assertArrayEquals(a, b)
    }

    @Test fun `an existing key is used and not made again`() {
        backend.create()
        val before = backend.creates
        identity.key
        assertEquals(before, backend.creates)
        assertArrayEquals(backend.publicKey(), identity.key.publicKey)
    }

    @Test fun `the public key returned is a copy`() {
        val k = identity.key
        val p = k.publicKey
        p[1] = (p[1] + 1).toByte()
        assertArrayEquals(backend.publicKey(), k.publicKey)
        assertNotSame(k.publicKey, k.publicKey)
    }

    @Test fun `an invalidated key with a pinned folder is lost and is never replaced`() {
        identity.key
        pinned = true
        backend.state = DeviceKeyStatus.INVALIDATED
        val creates = backend.creates
        val e = assertThrows(DeviceKeyException::class.java) { identity.key }
        assertEquals(DeviceKeyException.Kind.LOST, e.kind)
        assertEquals(creates, backend.creates)
        assertEquals(0, backend.discards)
        assertTrue(identity.isLost())
    }

    @Test fun `a missing key with a pinned folder is lost too`() {
        pinned = true
        assertEquals(DeviceKeyException.Kind.LOST, assertThrows(DeviceKeyException::class.java) { identity.key }.kind)
        assertEquals(0, backend.creates)
        assertTrue(identity.isLost())
    }

    @Test fun `with no folder an invalidated key is replaced`() {
        identity.key
        backend.state = DeviceKeyStatus.INVALIDATED
        val old = backend.publicKey()
        val fresh = identity.key.publicKey
        assertEquals(2, backend.creates)
        assertEquals(1, backend.discards)
        assertFalse(old.contentEquals(fresh))
        assertFalse(identity.isLost())
    }

    @Test fun `a key waiting for an unlock is used and never recreated`() {
        identity.key
        pinned = true
        backend.state = DeviceKeyStatus.NEEDS_UNLOCK
        val creates = backend.creates
        assertEquals(65, identity.key.publicKey.size)
        assertEquals(creates, backend.creates)
        assertEquals(0, backend.discards)
        assertFalse(identity.isLost())
        assertEquals(DeviceKeyStatus.NEEDS_UNLOCK, identity.status())
    }

    @Test fun `a lost key stays lost until the folder is released`() {
        identity.key
        pinned = true
        backend.state = DeviceKeyStatus.INVALIDATED
        assertThrows(DeviceKeyException::class.java) { identity.key }
        pinned = false
        assertEquals(65, identity.key.publicKey.size)
        assertEquals(2, backend.creates)
    }

    @Test fun `the provider runs the agreement in the key store and matches the software result`() {
        val sw = platformCryptoProvider()
        val provider = DeviceKeyCryptoProvider(sw)
        val peer = sw.p256Generate()
        val mine = identity.key
        val viaStore = provider.p256Agree(mine, peer.publicKey)
        assertEquals(1, backend.agrees)
        assertArrayEquals(viaStore, sw.p256Agree(peer, mine.publicKey))
    }

    @Test fun `the provider validates the peer before the key store sees it`() {
        val provider = DeviceKeyCryptoProvider(platformCryptoProvider())
        val bad = ByteArray(65).also { it[0] = 4 }
        val e = assertThrows(CryptoException::class.java) { provider.p256Agree(identity.key, bad) }
        assertEquals(CryptoException.Kind.INVALID_KEY, e.kind)
        assertEquals(0, backend.agrees)
    }

    @Test fun `a key store failure reaches the caller as a device key error, not a crypto result`() {
        val provider = DeviceKeyCryptoProvider(platformCryptoProvider())
        val mine = identity.key
        backend.state = DeviceKeyStatus.NEEDS_UNLOCK
        val peer = platformCryptoProvider().p256Generate().publicKey
        assertEquals(DeviceKeyException.Kind.NEEDS_UNLOCK, assertThrows(DeviceKeyException::class.java) { provider.p256Agree(mine, peer) }.kind)
    }

    @Test fun `software keys still go to the delegate`() {
        val sw = platformCryptoProvider()
        val provider = DeviceKeyCryptoProvider(sw)
        val a = sw.p256Generate()
        val b = sw.p256Generate()
        assertArrayEquals(sw.p256Agree(a, b.publicKey), provider.p256Agree(a, b.publicKey))
    }
}
