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
import app.doorprints.deviceauth.RunDecision

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

    /** The person re-enrolled: forget the pause. Only that action calls this. */
    fun clear() {
        paused = false
        needsReenrolment = false
        keyDropped = false
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

/** The documented words (docs/15 §10.3). The four-language strings belong in resources (see the notes file). */
object DriveLockNotice {
    const val NEEDS_LOCK_EN =
        "Google Drive backup needs a screen lock on this phone (a PIN, pattern, password, fingerprint or face). Set one in the phone's settings, then come back."
    const val PAUSED_EN =
        "Google Drive backup is paused because this phone no longer has a screen lock. Your houses are safe on this phone. Set a screen lock to continue."

    /** The words for a run decision; null when the run may go on. */
    fun forDecision(d: RunDecision): String? = when (d) {
        RunDecision.Run -> null
        RunDecision.PausedNoLock, RunDecision.PausedUnknown -> PAUSED_EN
    }
}

object DeviceLockDetectors {
    /** The keyguard (`isDeviceSecure`) and, when given, whether the lock-bound device key is still usable. */
    fun forContext(context: () -> Context?, keyProbe: (() -> Boolean)?): LockLostDetector = AndroidLockLostDetector(context, keyProbe)

    /**
     * True unless Android invalidated the key, or removed it while a folder is pinned to it (Android deletes lock-bound
     * keys with the lock). A key not made yet with no folder, or waiting for an unlock, says nothing about the lock.
     */
    fun keyUsable(identity: KeystoreDeviceIdentity): () -> Boolean =
        { identity.status() != DeviceKeyStatus.INVALIDATED && !identity.isLost() }
}
