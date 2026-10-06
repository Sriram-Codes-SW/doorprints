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

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.doorprints.crypto.platformCryptoProvider
import app.doorprints.deviceauth.AuthPlatform
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.ConnectDecision
import app.doorprints.deviceauth.DeviceAuth
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.deviceauth.DriveGate
import app.doorprints.deviceauth.LockLostDetector
import app.doorprints.deviceauth.LockState
import app.doorprints.deviceauth.RunDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class MemoryLockStore : DriveLockStore {
    override var paused = false
    override var needsReenrolment = false
    override var keyDropped = false
    override var keyStoreFault = false
}

class DeviceLockActionsTest {
    private val store = MemoryLockStore()
    private val backend = FakeKeyBackend().also { it.create() }
    private val lock = MutableLock()
    private val auth = object : DeviceAuth {
        var enabled = true
        override fun isDeviceLockEnabled() = enabled
        override suspend fun authenticate(reason: String, level: DeleteLevel) = AuthResult.SUCCESS
    }
    private val gate = DriveGate(AuthPlatform.PHONE, auth, lock, DeviceLockActions({ backend.discard() }, store)) { 0L }

    @Test fun `no screen lock refuses to switch Drive on`() {
        auth.enabled = false
        assertEquals(ConnectDecision.NeedsScreenLock, gate.canConnect())
        auth.enabled = true
        assertEquals(ConnectDecision.Allowed, gate.canConnect())
    }

    @Test fun `a removed lock pauses, drops the dead key and asks to enrol again`() {
        lock.state = LockState.REMOVED
        assertEquals(RunDecision.PausedNoLock, gate.beforeRun())
        assertTrue(store.paused)
        assertTrue(store.needsReenrolment)
        assertTrue(store.keyDropped)
        assertEquals(DeviceKeyStatus.ABSENT, backend.status())
    }

    @Test fun `a lock that is there changes nothing`() {
        assertEquals(RunDecision.Run, gate.beforeRun())
        assertFalse(store.paused)
        assertFalse(store.keyDropped)
        assertEquals(DeviceKeyStatus.READY, backend.status())
    }

    @Test fun `a lock that cannot be read pauses but keeps every key`() {
        lock.state = LockState.UNKNOWN
        assertEquals(RunDecision.PausedUnknown, gate.beforeRun())
        assertFalse(store.keyDropped)
        assertFalse(store.needsReenrolment)
        assertEquals(DeviceKeyStatus.READY, backend.status())
    }

    @Test fun `a pause does not undo itself when the lock comes back`() {
        lock.state = LockState.REMOVED
        gate.beforeRun()
        lock.state = LockState.PRESENT
        gate.beforeRun()
        assertTrue("re-enrolment is the person's action", store.paused)
        assertTrue(store.needsReenrolment)
    }

    @Test fun `clearing is explicit and clears all four`() {
        store.paused = true; store.needsReenrolment = true; store.keyDropped = true; store.keyStoreFault = true
        store.clear()
        assertFalse(store.paused || store.needsReenrolment || store.keyDropped || store.keyStoreFault)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KeyguardLockDetectorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keyguard get() = shadowOf(context.getSystemService(KeyguardManager::class.java))
    private var keyUsable = true
    private val detector: LockLostDetector = DeviceLockDetectors.forContext({ context }, keyProbe = { keyUsable })

    @Test fun `a secure keyguard and a usable key is present`() {
        keyguard.setIsDeviceSecure(true)
        assertEquals(LockState.PRESENT, detector.lockState())
    }

    @Test fun `no secure keyguard is removed`() {
        keyguard.setIsDeviceSecure(false)
        assertEquals(LockState.REMOVED, detector.lockState())
    }

    @Test fun `a key Android invalidated is removed even when a lock is back`() {
        keyguard.setIsDeviceSecure(true)
        keyUsable = false
        assertEquals(LockState.REMOVED, detector.lockState())
    }

    @Test fun `a key the key store lost while the lock is there is a key fault, not only a removed lock`() {
        keyguard.setIsDeviceSecure(true)
        keyUsable = false
        var faults = 0
        val d = DeviceLockDetectors.forContext({ context }, { keyUsable }) { faults++ }
        assertEquals(LockState.REMOVED, d.lockState())
        assertEquals(1, faults)
    }

    @Test fun `a removed keyguard is never a key fault, even with the key gone with it`() {
        keyguard.setIsDeviceSecure(false)
        keyUsable = false
        var faults = 0
        val d = DeviceLockDetectors.forContext({ context }, { keyUsable }) { faults++ }
        assertEquals(LockState.REMOVED, d.lockState())
        assertEquals(0, faults)
    }

    @Test fun `a lock and a usable key are not a fault`() {
        keyguard.setIsDeviceSecure(true)
        var faults = 0
        val d = DeviceLockDetectors.forContext({ context }, { keyUsable }) { faults++ }
        assertEquals(LockState.PRESENT, d.lockState())
        assertEquals(0, faults)
    }

    @Test fun `no context cannot be read`() {
        assertEquals(LockState.UNKNOWN, DeviceLockDetectors.forContext({ null }, null).lockState())
    }

    @Test fun `the probe maps an identity's status`() {
        val backend = FakeKeyBackend()
        var pinned = false
        val identity = KeystoreDeviceIdentity(backend, "x", folderPinned = { pinned })
        val probe = DeviceLockDetectors.keyUsable(identity)
        assertTrue("no key yet and no folder is not a lost key", probe())
        backend.create()
        pinned = true
        assertTrue(probe())
        fun failUse() {
            val peer = platformCryptoProvider().p256Generate().publicKey
            assertThrows(DeviceKeyException::class.java) { DeviceKeyCryptoProvider(platformCryptoProvider()).p256Agree(identity.key, peer) }
        }
        backend.state = DeviceKeyStatus.NEEDS_UNLOCK
        failUse()
        assertTrue("waiting for an unlock is not a lost lock", probe())
        backend.state = DeviceKeyStatus.INVALIDATED
        failUse()
        assertFalse(probe())
        backend.state = DeviceKeyStatus.ABSENT
        assertFalse("removed with the lock while a folder is pinned", probe())
        pinned = false
        assertTrue(probe())
    }
}
