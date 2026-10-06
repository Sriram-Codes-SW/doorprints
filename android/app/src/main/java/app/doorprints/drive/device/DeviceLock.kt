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
import app.doorprints.deviceauth.AndroidLockLostDetector
import app.doorprints.deviceauth.LockLossActions
import app.doorprints.deviceauth.LockLostDetector
import app.doorprints.deviceauth.LockState

/*
 * The device lock of Drive on Android (S4b-BL-127, docs/15 §10.3). The rules themselves are the shared `GateRules` and
 * `DriveGate` (no screen lock: Drive cannot be switched on; a lock removed later: pause); this file is what Android adds:
 * what a removed lock does **locally**, and the detector over the keyguard and the device key.
 *
 * Nothing here can reach Drive: [DeviceLockActions] is built from a key-discard function and a [DriveLockStore], so a
 * removed lock can never delete, revoke or re-key anything remote ("Your houses are safe on this phone").
 */

/** The device's own memory of a pause (the platform keeps it in a private, non-backed-up place). */
interface DriveLockStore {
    /** Drive is paused for want of a screen lock; shown on opening the app and in Settings. */
    var paused: Boolean

    /** This phone connects again and enrols again (docs/15 §10.3 "a new device"). */
    var needsReenrolment: Boolean

    /** The device key was dropped because Android invalidated it. */
    var keyDropped: Boolean

    /**
     * The key store lost or invalidated the key **while the phone still has its screen lock** (a vendor Keystore error, a
     * wiped key store): the person is told "the key store lost the key; connect again", not "the lock was removed".
     */
    var keyStoreFault: Boolean

    /** The person re-enrolled: forget the pause. Only that action calls this. */
    fun clear() {
        paused = false
        needsReenrolment = false
        keyDropped = false
        keyStoreFault = false
    }
}

/** What a removed lock does on the phone: forget the dead key, remember the pause. Idempotent. */
class DeviceLockActions(private val discardKey: () -> Unit, private val store: DriveLockStore) : LockLossActions {
    override fun dropLocalKeys() {
        discardKey()
        store.keyDropped = true
    }

    override fun requireReenrolment() {
        store.needsReenrolment = true
        store.paused = true
    }
}

object DeviceLockDetectors {
    /**
     * The keyguard (`isDeviceSecure`) and, when given, whether the lock-bound device key is still usable. A key that is not
     * usable while the keyguard **is** secure is not a removed lock but a key store fault: it still answers "removed" (the
     * key is gone either way) and calls [onKeyFault] first, so the words say what happened. The keyguard is asked before the
     * probe, so a probe that ran and failed always means the lock was there.
     */
    fun forContext(context: () -> Context?, keyProbe: (() -> Boolean)?, onKeyFault: () -> Unit = {}): LockLostDetector {
        if (keyProbe == null) return AndroidLockLostDetector(context, null)
        return LockLostDetector {
            var probeFailed = false
            val state = AndroidLockLostDetector(context) { keyProbe().also { usable -> probeFailed = !usable } }.lockState()
            if (state == LockState.REMOVED && probeFailed) onKeyFault()
            state
        }
    }

    /**
     * True unless Android invalidated the key, or removed it while a folder is pinned to it (Android deletes lock-bound
     * keys with the lock). A key not made yet with no folder, or waiting for an unlock, says nothing about the lock.
     */
    fun keyUsable(identity: KeystoreDeviceIdentity): () -> Boolean =
        { identity.status() != DeviceKeyStatus.INVALIDATED && !identity.isLost() }
}
