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

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Where the keyguard's confirm-credential screen runs on API 26-28, which has no BiometricPrompt: the UI starts the
 * keyguard's activity and says whether the person confirmed. Null in [AndroidDeviceAuth] means "not wired": fail closed.
 */
fun interface ConfirmCredentialLauncher {
    suspend fun confirm(title: String): Boolean
}

/**
 * [DeviceAuth] on Android with the platform's own APIs and no extra library (androidx.biometric is not a dependency;
 * the app lock uses the same classes, docs/15 10.2): `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`
 * from API 30, the device-credential flag on API 29, [launcher] on API 26-28. [context] gives the foreground activity
 * (null: nothing to show the prompt on, so NOT_AVAILABLE).
 *
 * Not done here (needs a device, S4b-BL-127 notes): the Keystore HMAC key that signs the operation id through a
 * CryptoObject on API 30+.
 */
class AndroidDeviceAuth(
    private val context: () -> Context?,
    private val launcher: ConfirmCredentialLauncher? = null,
) : DeviceAuth {

    override fun isDeviceLockEnabled(): Boolean =
        context()?.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override suspend fun authenticate(reason: String, level: DeleteLevel): AuthResult {
        val ctx = context() ?: return AuthResult.NOT_AVAILABLE
        if (ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) return AuthResult.LOCK_NOT_SET
        return try {
            if (Build.VERSION.SDK_INT >= 29) prompt(ctx, reason) else legacy(reason)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            AuthResult.FAILED
        }
    }

    private suspend fun legacy(reason: String): AuthResult {
        val l = launcher ?: return AuthResult.NOT_AVAILABLE
        return if (l.confirm(reason)) AuthResult.SUCCESS else AuthResult.CANCELLED
    }

    private suspend fun prompt(ctx: Context, reason: String): AuthResult = suspendCancellableCoroutine { cont ->
        val builder = BiometricPrompt.Builder(ctx).setTitle(reason)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        builder.build().authenticate(signal, ctx.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(AuthResult.SUCCESS)
            }

            // A wrong finger is onAuthenticationFailed and the prompt stays up; only a final error ends it.
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (cont.isActive) cont.resume(AndroidAuthErrors.map(errorCode))
            }
        })
    }
}

/** BiometricPrompt's final error codes as [AuthResult]. Pure, so the host tests cover it. */
object AndroidAuthErrors {
    /** BiometricConstants.ERROR_NEGATIVE_BUTTON (13); the platform class has no public constant for it. */
    const val ERROR_NEGATIVE_BUTTON = 13

    fun map(code: Int): AuthResult = when (code) {
        BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
        ERROR_NEGATIVE_BUTTON -> AuthResult.CANCELLED
        BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> AuthResult.TIMED_OUT
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> AuthResult.LOCKED_OUT
        BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL -> AuthResult.LOCK_NOT_SET
        BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT, BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
        BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS -> AuthResult.NOT_AVAILABLE
        else -> AuthResult.FAILED
    }
}

/** Android's [LockLostDetector]: the keyguard is not secure any more. [keyProbe] may add "the Keystore key is invalid". */
class AndroidLockLostDetector(
    private val context: () -> Context?,
    private val keyProbe: (() -> Boolean)? = null,
) : LockLostDetector {
    override fun lockState(): LockState {
        val keyguard = context()?.getSystemService(KeyguardManager::class.java) ?: return LockState.UNKNOWN
        if (!keyguard.isDeviceSecure) return LockState.REMOVED
        // The key probe says whether the lock-bound key is still usable; false means Android invalidated it.
        return if (keyProbe?.invoke() == false) LockState.REMOVED else LockState.PRESENT
    }
}
