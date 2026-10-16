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
import app.doorprints.deviceauth.LockLostDetector
import app.doorprints.deviceauth.LockState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
}
