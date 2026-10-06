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

package app.doorprints.drive.wiring

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.doorprints.crypto.DevicePlatform
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.deviceauth.DeviceAuth
import app.doorprints.drive.auth.AndroidDriveTokenProvider
import app.doorprints.drive.auth.AuthorizerResult
import app.doorprints.drive.auth.ConsentResolver
import app.doorprints.drive.auth.PendingConsent
import app.doorprints.drive.auth.PlayPendingConsent
import app.doorprints.drive.auth.SignInException
import app.doorprints.drive.backup.ApproveDeviceOutcome
import app.doorprints.drive.backup.DriveBackupService
import app.doorprints.drive.backup.DriveConnection
import app.doorprints.drive.connect.DeviceEnrolment
import app.doorprints.drive.connect.DriveSignIn
import app.doorprints.drive.connect.EnrolmentApproval
import app.doorprints.drive.connect.EnrolmentRevoke
import app.doorprints.drive.connect.SignInResult
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.device.OperationProver
import app.doorprints.drive.device.ProofOutcome
import app.doorprints.drive.photo.NetworkConditions
import app.doorprints.drive.photo.NetworkState
import app.doorprints.drive.photo.PhotoNetworkPolicy
import kotlinx.coroutines.CancellationException

/*
 * The thin adapters between the controller's seams (`DriveConnectSeams.kt`) and what Android has. Each one only maps; the
 * decisions stay in the piece it adapts (the token provider, the backup service, the prover).
 */

/** [DriveSignIn] over the token provider: signing in is asking for a token (quiet while Google's grant holds). */
class TokenDriveSignIn(
    private val tokens: AndroidDriveTokenProvider,
    /** Drive can only be switched on with a screen lock (docs/15 §10.3): without one nothing is asked of Google. */
    private val canConnect: () -> Boolean = { true },
) : DriveSignIn {
    override suspend fun signIn(): SignInResult = if (!canConnect()) SignInResult.UNAVAILABLE else try {
        tokens.accessToken()
        SignInResult.SIGNED_IN
    } catch (e: SignInException) {
        resultOf(e.kind)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        SignInResult.FAILED
    }

    override suspend fun signOut() = tokens.forget()

    override suspend fun revokeAccess() = tokens.revokeAccess()

    companion object {
        /**
         * A closed prompt is [SignInResult.CANCELLED] (not an error: the card goes back), a person who unticked the Drive
         * permission is [SignInResult.DENIED]; the two are never merged. "Consent needed with nobody to show it" is
         * [SignInResult.UNAVAILABLE] (a background run cannot ask).
         */
        fun resultOf(kind: SignInException.Kind): SignInResult = when (kind) {
            SignInException.Kind.DENIED -> SignInResult.DENIED
            SignInException.Kind.CANCELLED -> SignInResult.CANCELLED
            SignInException.Kind.CONSENT_REQUIRED -> SignInResult.UNAVAILABLE
            SignInException.Kind.OFFLINE -> SignInResult.OFFLINE
            SignInException.Kind.UNAVAILABLE -> SignInResult.UNAVAILABLE
        }
    }
}

/** [DeviceEnrolment] over [DriveBackupService]: the outcome types differ only in name, the rules stay in the service. */
class BackupDeviceEnrolment(private val service: DriveBackupService) : DeviceEnrolment {
    override suspend fun approveDevice(publicKey: ByteArray, name: String, platform: DevicePlatform): EnrolmentApproval =
        approval(service.approveDevice(publicKey, name, platform))

    override suspend fun approveDevicePsk(publicKey: ByteArray, name: String, platform: DevicePlatform, psk: ByteArray): EnrolmentApproval =
        approval(service.approveDevicePsk(publicKey, name, platform, psk))

    override suspend fun joinFromWrap(enc: ByteArray, ct: ByteArray, epoch: Int): DriveConnection = service.joinFromWrap(enc, ct, epoch)

    override suspend fun joinFromPsk(enc: ByteArray, ct: ByteArray, epoch: Int, psk: ByteArray): DriveConnection =
        service.joinFromPsk(enc, ct, epoch, psk)

    override suspend fun revokeDevice(kid: ByteArray): EnrolmentRevoke {
        val out = service.revokeDevice(kid)
        return EnrolmentRevoke(out.connection, out.recoveryKey)
    }

    private fun approval(out: ApproveDeviceOutcome): EnrolmentApproval = when (out) {
        is ApproveDeviceOutcome.Approved -> EnrolmentApproval.Approved(out.connection, out.wrapEnc, out.wrapCt, out.epoch)
        is ApproveDeviceOutcome.Error -> EnrolmentApproval.Failed(out.problem)
    }
}

/**
 * The phone's device check for the gate (`DriveGate`): asks through the [OperationProver] (a Keystore HMAC key behind
 * BiometricPrompt on Android 11+, so the check is bound to the hardware; the plain prompt below) and says only whether
 * the person passed. The proof it makes is thrown away: the gate's own grant is what the deletion service checks.
 * [lockEnabled] is the keyguard (`isDeviceSecure`), asked without any Activity.
 */
class ProverDeviceAuth(
    private val prover: OperationProver,
    private val lockEnabled: () -> Boolean,
    private val clock: () -> Long,
) : DeviceAuth {
    private var asked = 0L

    override fun isDeviceLockEnabled(): Boolean = lockEnabled()

    override suspend fun authenticate(reason: String, level: DeleteLevel): AuthResult {
        val asLevel = when (level) {
            DeleteLevel.L2 -> DeletionLevel.L2
            DeleteLevel.L3 -> DeletionLevel.L3
            // Level 1 asks nothing; a call here is a bug, and a bug must not pass.
            DeleteLevel.L1 -> return AuthResult.FAILED
        }
        val operation = synchronized(this) { "$OPERATION_PREFIX${asLevel.name}/${++asked}" }
        return resultOf(prover.prove(operation, asLevel, reason, clock))
    }

    companion object {
        const val OPERATION_PREFIX = "doorprints/device-check/"

        fun resultOf(outcome: ProofOutcome): AuthResult = when (outcome) {
            is ProofOutcome.Proved -> AuthResult.SUCCESS
            ProofOutcome.Denied -> AuthResult.LOCKED_OUT
            ProofOutcome.Cancelled, ProofOutcome.TimedOut -> AuthResult.CANCELLED
            ProofOutcome.NoLock -> AuthResult.LOCK_NOT_SET
            ProofOutcome.Unavailable -> AuthResult.NOT_AVAILABLE
            ProofOutcome.Failed -> AuthResult.FAILED
        }
    }
}

/** What an Activity answered to a screen the app started for a result. */
class ActivityOutcome(val resultCode: Int, val data: Intent?)

/** Starts Google's consent screen or the keyguard's confirm screen on the foreground Activity and waits for its answer. */
interface ActivityLauncher {
    suspend fun launch(consent: PendingIntent): ActivityOutcome
    suspend fun launch(intent: Intent): ActivityOutcome
}

/**
 * The Activity side of Google's consent (A1 notes): the token provider asks, this starts the screen on the foreground
 * Activity ([launcher], null in the background) and has [interpret] (`PlayGoogleAuthorizer.fromActivityResult`) read the
 * answer, so a closed screen stays "cancelled" and a screen with the Drive permission unticked stays "granted without it"
 * for the provider's own checks. Nobody to show it to is "unavailable", never a refusal.
 */
class ConsentBridge(
    private val launcher: () -> ActivityLauncher?,
    private val interpret: (resultCode: Int, data: Intent?) -> AuthorizerResult,
) : ConsentResolver {
    /** The resolver to give the token provider: null while no Activity can show the screen. */
    fun resolverOrNull(): ConsentResolver? = if (launcher() != null) this else null

    override suspend fun resolve(consent: PendingConsent): AuthorizerResult {
        val play = consent as? PlayPendingConsent ?: return AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE)
        val activity = launcher() ?: return AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE)
        val out = activity.launch(play.intent)
        return interpret(out.resultCode, out.data)
    }
}

/** The network as `ConnectivityManager` shows it, for the photo policy (Wi-Fi only by default, Data Saver and roaming respected). */
class ConnectivityNetworkState(private val context: Context) : NetworkState {
    override fun current(): NetworkConditions {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkConditions.OFFLINE
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return NetworkConditions.OFFLINE) ?: return NetworkConditions.OFFLINE
        return conditions(
            internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            notRoaming = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
            restrictBackground = cm.restrictBackgroundStatus,
        )
    }

    companion object {
        /** Online needs a validated internet connection (a captive portal is not online). Data Saver counts when it is on for this app. */
        fun conditions(internet: Boolean, validated: Boolean, notMetered: Boolean, notRoaming: Boolean, restrictBackground: Int): NetworkConditions =
            PhotoNetworkPolicy.fromAndroid(
                hasInternet = internet && validated,
                notMetered = notMetered,
                notRoaming = notRoaming,
                dataSaverEnabled = restrictBackground == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            )
    }
}
