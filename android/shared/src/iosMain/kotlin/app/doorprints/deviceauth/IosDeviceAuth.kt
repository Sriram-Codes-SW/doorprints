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

package app.doorprints.deviceauth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import kotlin.coroutines.resume

/**
 * [DeviceAuth] on iOS: `LAPolicyDeviceOwnerAuthentication` (Face ID, Touch ID or the passcode), docs/15 10.2. The
 * reply comes on a private queue; resuming the coroutine from it is safe. Fails closed: anything unexpected is FAILED.
 * Not exercised here (needs an iPhone, S4b-BL-127 notes, TC-M-50/51); compiled by the klib task only.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDeviceAuth : DeviceAuth {
    /** Whether the device-owner policy (passcode, Face ID or Touch ID) can be evaluated. */
    override fun isDeviceLockEnabled(): Boolean = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)

    /**
     * Shows the system prompt for [reason]. Cancel, lockout and no-passcode have their own results; everything
     * else is FAILED, and the prompt is invalidated if the coroutine is cancelled.
     */
    override suspend fun authenticate(reason: String, level: DeleteLevel): AuthResult {
        if (!isDeviceLockEnabled()) return AuthResult.LOCK_NOT_SET
        return suspendCancellableCoroutine { cont ->
            val context = LAContext()
            cont.invokeOnCancellation { context.invalidate() }
            context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, localizedReason = reason) { passed, error ->
                context.invalidate()
                val result = if (passed) {
                    AuthResult.SUCCESS
                } else {
                    // LocalAuthentication has no timeout error: a prompt left alone is not ended by the system, and
                    // LAErrorSystemCancel (another app in front, the phone locked) is a cancel, so TIMED_OUT is never returned here (S4b-BL-140).
                    when (error?.code) {
                        LAErrorUserCancel, LAErrorSystemCancel, LAErrorAppCancel -> AuthResult.CANCELLED
                        LAErrorBiometryLockout -> AuthResult.LOCKED_OUT
                        LAErrorPasscodeNotSet -> AuthResult.LOCK_NOT_SET
                        else -> AuthResult.FAILED
                    }
                }
                if (cont.isActive) cont.resume(result)
            }
        }
    }
}

/** iOS's [LockLostDetector]: the device-owner policy can no longer be evaluated (the passcode was removed). */
@OptIn(ExperimentalForeignApi::class)
class IosLockLostDetector : LockLostDetector {
    override fun lockState(): LockState =
        if (LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)) LockState.PRESENT else LockState.REMOVED
}
