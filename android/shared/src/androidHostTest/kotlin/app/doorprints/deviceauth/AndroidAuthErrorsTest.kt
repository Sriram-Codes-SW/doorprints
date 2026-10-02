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

import android.hardware.biometrics.BiometricPrompt
import kotlin.test.Test
import kotlin.test.assertEquals

/** BiometricPrompt's final errors as [AuthResult] (S4b-BL-127); the prompt itself needs a phone (TC-M-50). */
class AndroidAuthErrorsTest {
    @Test
    fun errorsMapAndUnknownFailsClosed() {
        val m = AndroidAuthErrors
        assertEquals(AuthResult.CANCELLED, m.map(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED))
        assertEquals(AuthResult.CANCELLED, m.map(BiometricPrompt.BIOMETRIC_ERROR_CANCELED))
        assertEquals(AuthResult.CANCELLED, m.map(AndroidAuthErrors.ERROR_NEGATIVE_BUTTON))
        assertEquals(AuthResult.CANCELLED, m.map(BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT))
        assertEquals(AuthResult.LOCKED_OUT, m.map(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT))
        assertEquals(AuthResult.LOCKED_OUT, m.map(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT))
        assertEquals(AuthResult.LOCK_NOT_SET, m.map(BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL))
        assertEquals(AuthResult.NOT_AVAILABLE, m.map(BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT))
        assertEquals(AuthResult.NOT_AVAILABLE, m.map(BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertEquals(AuthResult.NOT_AVAILABLE, m.map(BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS))
        assertEquals(AuthResult.FAILED, m.map(BiometricPrompt.BIOMETRIC_ERROR_VENDOR))
        assertEquals(AuthResult.FAILED, m.map(-12345))
    }
}
