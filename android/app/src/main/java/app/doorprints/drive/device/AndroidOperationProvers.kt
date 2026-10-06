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
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import app.doorprints.crypto.CryptoProvider
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.deviceauth.DeviceAuth
import app.doorprints.drive.delete.DeletionLevel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlin.coroutines.resume

/*
 * The two ways to prove an operation (docs/15 §10.2). Android 11+ (API 30): a Keystore HMAC key with per-use
 * authentication (timeout 0) signs the operation through a `CryptoObject`, so the signature exists only when the person
 * passed on this prompt for this operation. Below API 30: the plain pass result of the existing [DeviceAuth] (as the app
 * lock does) and an HMAC under a key that lives in this process only; the proof then binds the grant to the operation
 * inside the app but is not hardware-bound (§10.6: a changed app can skip any check). No library: platform classes only.
 */

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/** BiometricPrompt's final error codes as [ProofOutcome]. Pure, so the host tests cover it. */
object PromptErrors {
    /** BiometricConstants.ERROR_NEGATIVE_BUTTON (13); the platform class has no public constant for it. */
    const val ERROR_NEGATIVE_BUTTON = 13

    fun map(code: Int): ProofOutcome = when (code) {
        BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, ERROR_NEGATIVE_BUTTON -> ProofOutcome.Cancelled
        BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> ProofOutcome.TimedOut
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> ProofOutcome.Denied
        BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL -> ProofOutcome.NoLock
        BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT, BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
        BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS -> ProofOutcome.Unavailable
        else -> ProofOutcome.Failed
    }
}

/** Android 10 and below: the pass result of [auth] and an in-process HMAC key. Pure over the two seams, so JVM-tested. */
class SoftwareOperationProver(private val auth: DeviceAuth, private val p: CryptoProvider) : OperationProver {
    private val key: ByteArray by lazy { p.randomBytes(32) }

    override suspend fun prove(operationId: String, level: DeletionLevel, reason: String, now: () -> Long): ProofOutcome {
        val result = try {
            auth.authenticate(reason, if (level == DeletionLevel.L3) DeleteLevel.L3 else DeleteLevel.L2)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return ProofOutcome.Failed
        }
        return when (result) {
            AuthResult.SUCCESS -> {
                val issued = now()
                ProofOutcome.Proved(issued, OperationProof.hex(p.hmacSha256(key, OperationProof.message(operationId, issued))))
            }
            AuthResult.CANCELLED -> ProofOutcome.Cancelled
            AuthResult.LOCKED_OUT -> ProofOutcome.Denied
            AuthResult.LOCK_NOT_SET -> ProofOutcome.NoLock
            AuthResult.NOT_AVAILABLE -> ProofOutcome.Unavailable
            AuthResult.FAILED -> ProofOutcome.Failed
        }
    }
}

/**
 * Android 11+: `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` and a `CryptoObject` over a per-use
 * Keystore HMAC key. [context] gives the foreground activity (null: nothing to show the prompt on, so unavailable).
 * Needs a device or emulator to run (no Keystore under Robolectric); see the notes file.
 */
@RequiresApi(Build.VERSION_CODES.R)
class KeystoreOperationProver(
    private val context: () -> Context?,
    private val alias: String = "doorprints_drive_delete_hmac",
) : OperationProver {

    override suspend fun prove(operationId: String, level: DeletionLevel, reason: String, now: () -> Long): ProofOutcome {
        val ctx = context() ?: return ProofOutcome.Unavailable
        val mac = try {
            openMac()
        } catch (e: Exception) {
            return ProofOutcome.Failed
        }
        return suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            val prompt = BiometricPrompt.Builder(ctx)
                .setTitle(reason)
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                .build()
            prompt.authenticate(
                BiometricPrompt.CryptoObject(mac), signal, ctx.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (!cont.isActive) return
                        val signed = result.cryptoObject?.mac
                        if (signed == null) {
                            cont.resume(ProofOutcome.Failed)
                            return
                        }
                        try {
                            val issued = now()
                            cont.resume(ProofOutcome.Proved(issued, OperationProof.hex(signed.doFinal(OperationProof.message(operationId, issued)))))
                        } catch (e: Exception) {
                            cont.resume(ProofOutcome.Failed)
                        }
                    }

                    // A wrong finger is onAuthenticationFailed and the prompt stays up; only a final error ends it.
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(PromptErrors.map(errorCode))
                    }
                },
            )
        }
    }

    /** A Mac ready for the prompt. A key Android invalidated is replaced: it holds no state, a new one only signs new operations. */
    private fun openMac(): Mac {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(alias)) createKey()
        return try {
            Mac.getInstance("HmacSHA256").apply { init(ks.getKey(alias, null) as SecretKey) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            ks.deleteEntry(alias)
            createKey()
            Mac.getInstance("HmacSHA256").apply { init(ks.getKey(alias, null) as SecretKey) }
        }
    }

    private fun createKey() {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setUserAuthenticationRequired(true)
            // Timeout 0: every use needs its own authentication, through the CryptoObject.
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }
}

/** The prover for this phone: the Keystore one from Android 11, the plain pass result below (docs/15 §10.2). */
object OperationProvers {
    fun forThisDevice(context: () -> Context?, auth: DeviceAuth, p: CryptoProvider, sdk: Int = Build.VERSION.SDK_INT): OperationProver =
        if (sdk >= Build.VERSION_CODES.R) {
            @Suppress("NewApi")
            KeystoreOperationProver(context)
        } else {
            SoftwareOperationProver(auth, p)
        }
}
