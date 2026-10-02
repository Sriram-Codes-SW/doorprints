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

/** How a request to authenticate ended (docs/15 10.2). Everything but [SUCCESS] means: do nothing, "Nothing was deleted". */
enum class AuthResult {
    SUCCESS,

    /** The person dismissed the prompt, or it timed out. */
    CANCELLED,

    /** The phone has no screen lock. */
    LOCK_NOT_SET,

    /** No way to ask on this platform or phone (also: the platform call is not wired). Fails closed. */
    NOT_AVAILABLE,

    /** Too many wrong tries; the platform refuses for a while. */
    LOCKED_OUT,

    /** Anything else that went wrong, including an exception from the platform. */
    FAILED,
}

/** The three levels of docs/15 10.1. L1 asks only the dialog; a factor is asked for [L2] and [L3]. */
enum class DeleteLevel { L1, L2, L3 }

/**
 * The phone's own authentication (docs/15 10.2): Android BiometricPrompt with BIOMETRIC_STRONG or DEVICE_CREDENTIAL,
 * iOS `LAPolicyDeviceOwnerAuthentication`. Each call asks once; a result is never cached here (the 60-second,
 * one-operation rule is [DriveGate]'s). [level] only picks the wording and the strictness class; [DeleteLevel.L1]
 * needs no factor and callers do not ask for it.
 */
interface DeviceAuth {
    /** A screen lock (PIN, pattern, password, fingerprint or face) is set (docs/15 10.3). */
    fun isDeviceLockEnabled(): Boolean

    suspend fun authenticate(reason: String, level: DeleteLevel): AuthResult
}
