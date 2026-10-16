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

package app.doorprints.drive.auth.browser

import app.doorprints.drive.device.BlobStore
import app.doorprints.drive.device.DeviceKeyException
import app.doorprints.drive.device.DeviceKeyStatus
import app.doorprints.drive.device.SecretWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SealedRefreshTokenStoreTest {
    /** AES-GCM on the JVM with the wrapper's contract (nonce first, AAD bound); the Keystore itself needs a device. */
    private class JvmWrapper : SecretWrapper {
        private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        var ensured = 0
        override fun status() = DeviceKeyStatus.READY
        override fun ensureKey() {
            ensured++
        }

        override fun wrap(secret: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            c.updateAAD(aad)
            return iv + c.doFinal(secret)
        }

        override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, 12))
            c.updateAAD(aad)
            c.doFinal(wrapped, 12, wrapped.size - 12)
        } catch (e: Exception) {
            throw DeviceKeyException(DeviceKeyException.Kind.LOST, "unwrap", e)
        }

        override fun deleteKey() = Unit
    }

    private class MemBlob : BlobStore {
        var bytes: ByteArray? = null
        override fun read() = bytes
        override fun write(data: ByteArray) {
            bytes = data
        }

        override fun delete() {
            bytes = null
        }
    }

    private val wrapper = JvmWrapper()
    private val blob = MemBlob()
    private val store = SealedRefreshTokenStore(wrapper, blob)

    @Test
    fun roundTrips() {
        store.write("1//refresh-token-value")
        assertEquals("1//refresh-token-value", store.read())
        assertEquals(1, wrapper.ensured)
    }

    @Test
    fun theFileNeverHoldsThePlainToken() {
        store.write("1//refresh-token-value")
        val raw = String(blob.bytes!!, Charsets.ISO_8859_1)
        assertFalse(raw.contains("refresh-token-value"))
    }

    @Test
    fun emptyReadsAsNull() {
        assertNull(store.read())
    }

    @Test
    fun clearDeletesTheFile() {
        store.write("t")
        store.clear()
        assertNull(blob.bytes)
        assertNull(store.read())
    }

    @Test
    fun aChangedFileIsLostNotReturned() {
        store.write("t")
        blob.bytes = blob.bytes!!.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try {
            store.read()
            fail("tampering must not read as a token")
        } catch (e: DeviceKeyException) {
            assertEquals(DeviceKeyException.Kind.LOST, e.kind)
        }
    }

    @Test
    fun aBlobSealedForAnotherPurposeDoesNotOpen() {
        blob.bytes = wrapper.wrap("device-scalar".toByteArray(), "doorprints-drive-device-key/1".toByteArray())
        try {
            store.read()
            fail("another purpose's blob must not open")
        } catch (e: DeviceKeyException) {
            assertTrue(e.kind == DeviceKeyException.Kind.LOST)
        }
    }

    @Test
    fun theFileIsBoundToTheRefreshTokenPurposeNamed() {
        store.write("t")
        val opened = wrapper.unwrap(blob.bytes!!, "doorprints-drive-refresh-token/1".toByteArray(Charsets.US_ASCII))
        assertEquals("t", String(opened))
    }

    @Test
    fun memoryStoreKeepsAndClears() {
        val m = MemoryRefreshTokenStore()
        m.write("x")
        assertEquals("x", m.read())
        m.clear()
        assertNull(m.read())
    }
}
