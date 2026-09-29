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

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

// The app lock on Android (docs/11 5.19) with the platform's own APIs, no extra library: BiometricPrompt from API 29,
// which offers the fingerprint or face and falls back to the PIN, pattern or password; on API 26-28 the keyguard's
// confirm-credential screen (PIN, pattern or password; that screen offers the fingerprint where the phone allows).

@Composable
actual fun rememberDeviceCredentialCheck(title: String, onResult: (CredentialCheck) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onResult)
    // API 26-28 only: the keyguard's own activity, whose answer comes back as an activity result.
    val keyguardScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        latest(if (result.resultCode == Activity.RESULT_OK) CredentialCheck.PASSED else CredentialCheck.CANCELLED)
    }
    return remember(context, title) {
        {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (keyguard?.isDeviceSecure != true) {
                latest(CredentialCheck.NO_SCREEN_LOCK)
            } else if (Build.VERSION.SDK_INT >= 29) {
                val builder = BiometricPrompt.Builder(context).setTitle(title)
                if (Build.VERSION.SDK_INT >= 30) {
                    builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION")
                    builder.setDeviceCredentialAllowed(true)
                }
                builder.build().authenticate(
                    CancellationSignal(),
                    context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                            latest(CredentialCheck.PASSED)

                        // Cancelled, timed out or locked out after too many tries. A single wrong finger is
                        // onAuthenticationFailed, and the prompt stays up for another try.
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                            latest(CredentialCheck.CANCELLED)
                    },
                )
            } else {
                @Suppress("DEPRECATION")
                val intent = keyguard.createConfirmDeviceCredentialIntent(title, null)
                if (intent == null) latest(CredentialCheck.NO_SCREEN_LOCK) else keyguardScreen.launch(intent)
            }
        }
    }
}

@Composable
actual fun AppLockWindowGuard(on: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, on) {
        if (Build.VERSION.SDK_INT >= 33) {
            // Only the recents picture: the person can still take screenshots of their own list.
            activity.setRecentsScreenshotEnabled(!on)
        } else if (on) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { }
    }
}

