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

package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

// The app lock on iOS (docs/11 5.19): LocalAuthentication's device-owner policy, which offers Face ID or Touch ID and
// falls back to the passcode. Face ID needs NSFaceIDUsageDescription in the app's Info.plist (ios/project.yml).

/**
 * iPhone's check: LocalAuthentication's device-owner policy (Face ID or Touch ID, then the passcode). A phone with no
 * passcode answers [CredentialCheck.NO_SCREEN_LOCK]; anything but a pass is [CredentialCheck.CANCELLED]. The reply is
 * moved to the main thread.
 */
@Composable
actual fun rememberDeviceCredentialCheck(title: String, onResult: (CredentialCheck) -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onResult)
    return remember(title) {
        {
            if (!iosHasScreenLock()) {
                latest(CredentialCheck.NO_SCREEN_LOCK)
            } else {
                // Held by its own reply block, so the context lives until the person has answered.
                val context = LAContext()
                context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, localizedReason = title) { passed, _ ->
                    context.invalidate()
                    // The reply comes on a private queue; the UI state is changed on the main one.
                    dispatch_async(dispatch_get_main_queue()) {
                        latest(if (passed) CredentialCheck.PASSED else CredentialCheck.CANCELLED)
                    }
                }
            }
        }
    }
}

/** Nothing on iOS: [AppLockHost] covers the app as it resigns active, before the app switcher's picture. */
@Composable
actual fun AppLockWindowGuard(on: Boolean) {}

/**
 * A passcode is set (Face ID and Touch ID need one), so the device-owner policy can be asked. The error out-parameter
 * is a C pointer (ExperimentalForeignApi); none is wanted, the answer is enough.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun iosHasScreenLock(): Boolean = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)
