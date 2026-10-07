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

import app.doorprints.concurrent.PlatformLock
import app.doorprints.crypto.DevicePlatform
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.drive.auth.DriveTokenProvider
import app.doorprints.drive.auth.SignInException
import app.doorprints.drive.backup.ApproveDeviceOutcome
import app.doorprints.drive.backup.DriveBackupService
import app.doorprints.drive.backup.DriveConnection
import app.doorprints.drive.connect.DeviceEnrolment
import app.doorprints.drive.connect.DriveSignIn
import app.doorprints.drive.connect.EnrolmentApproval
import app.doorprints.drive.connect.EnrolmentRevoke
import app.doorprints.drive.connect.OperationBoundAuth
import app.doorprints.drive.connect.OperationProofValue
import app.doorprints.drive.connect.SignInResult
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.device.OperationProof
import app.doorprints.drive.device.OperationProver
import app.doorprints.drive.device.ProofOutcome
import kotlinx.coroutines.CancellationException

/** [DriveSignIn] over the token provider: signing in is asking for a token (quiet while Google's grant holds). */
class TokenDriveSignIn(
    private val tokens: DriveTokenProvider,
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
 * BiometricPrompt on Android 11+, so the check is bound to the hardware; the plain prompt below) for the operation the
 * authorizer bound ([bindNext]; else a one-off label) and **keeps the proof** the prover signed for
 * `PhoneDeletionAuthorizer` to put in the token (S4b-BL-135). [lockEnabled] is the keyguard (`isDeviceSecure`), asked
 * without any Activity.
 */
class ProverDeviceAuth(
    private val prover: OperationProver,
    private val lockEnabled: () -> Boolean,
    private val clock: () -> Long,
) : OperationBoundAuth {
    private val lock = PlatformLock()
    private var asked = 0L
    private var bound: String? = null
    private var proved: OperationProofValue? = null

    override fun isDeviceLockEnabled(): Boolean = lockEnabled()

    override fun bindNext(operationId: String?) {
        lock.withLock {
            bound = operationId
            proved = null
        }
    }

    override fun takeProof(): OperationProofValue? = lock.withLock { proved.also { proved = null } }

    override suspend fun authenticate(reason: String, level: DeleteLevel): AuthResult {
        val asLevel = when (level) {
            DeleteLevel.L2 -> DeletionLevel.L2
            DeleteLevel.L3 -> DeletionLevel.L3
            // Level 1 asks nothing; a call here is a bug, and a bug must not pass.
            DeleteLevel.L1 -> return AuthResult.FAILED
        }
        val operation = lock.withLock {
            proved = null
            bound ?: "$OPERATION_PREFIX${asLevel.name}/${++asked}"
        }
        val outcome = prover.prove(operation, asLevel, reason, clock)
        if (outcome is ProofOutcome.Proved) {
            // A pass whose proof is not 64 hex digits is no pass.
            if (!OperationProof.isProof(outcome.proof)) return AuthResult.FAILED
            lock.withLock { proved = OperationProofValue(outcome.issuedAtMs, outcome.proof) }
        }
        return resultOf(outcome)
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
