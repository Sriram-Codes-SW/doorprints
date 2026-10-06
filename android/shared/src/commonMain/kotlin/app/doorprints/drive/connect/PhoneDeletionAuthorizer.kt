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

package app.doorprints.drive.connect

import app.doorprints.crypto.constantTimeEquals
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.AuthGrant
import app.doorprints.deviceauth.Authorization
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.deviceauth.DeletionContext
import app.doorprints.deviceauth.DeviceAuth
import app.doorprints.deviceauth.DriveGate
import app.doorprints.deviceauth.RefusalReason
import app.doorprints.deviceauth.Redeemed
import app.doorprints.deviceauth.RunDecision
import app.doorprints.drive.delete.AuthorizationGate
import app.doorprints.drive.delete.AuthorizationToken
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.DeletionRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.doorprints.deviceauth.DeletionAction as PolicyAction

/** What the device check signed: the moment the person passed and the HMAC of the operation at that moment (docs/15 §10.2). */
class OperationProofValue(val issuedAtMs: Long, val proof: String) {
    override fun toString(): String = "OperationProofValue(issuedAtMs=$issuedAtMs)"
}

/**
 * A [DeviceAuth] that can be told which operation the next check is for and hands back what it signed. [DriveGate]
 * only knows `authenticate(reason, level)`; this is how the plan's operation id reaches the Keystore HMAC and the proof
 * comes back out, without a second prompt. The phone's is `ProverDeviceAuth`.
 */
interface OperationBoundAuth : DeviceAuth {
    /** The next [authenticate] is for [operationId] (null: not for a plan). Clears any proof left from before. */
    fun bindNext(operationId: String?)

    /** The proof of the last check that passed, once; null when none passed since [bindNext]. */
    fun takeProof(): OperationProofValue?
}

/**
 * The phones' authorisation: [DriveGate] decides and asks for the device check, bound to the plan's operation id; this
 * class turns its grant plus the check's HMAC proof into the [AuthorizationToken] the deletion service wants and, as
 * the [AuthorizationGate], checks it again when the run starts (one use, 60 seconds, bound to the operation, the level and
 * the proof) and before every file (the lock still there). The Kotlin twin of the web's `RealAuthorizationGate`. The
 * proof is the secret only a real pass produced (S4b-BL-135); L1 carries none.
 */
class PhoneDeletionAuthorizer(
    private val gate: DriveGate,
    private val auth: OperationBoundAuth,
    private val clock: () -> Long,
) : DeleteAuthorizer, AuthorizationGate {
    private class Issued(val grant: AuthGrant, val action: PolicyAction, val token: AuthorizationToken)

    /** By operation id (a second authorisation of a plan replaces the first). Oldest goes first at [MAX_ISSUED]. */
    private val issued = LinkedHashMap<String, Issued>()
    private val asking = Mutex()

    override fun isDeviceLockEnabled(): Boolean = try {
        auth.isDeviceLockEnabled()
    } catch (_: Exception) {
        false
    }

    override suspend fun authorize(action: PolicyAction, ctx: DeletionContext, operationId: String?, promptReason: String): DeleteAuthorization {
        // One ask at a time: the bound operation and the proof taken back belong to the same prompt.
        val (a, proved) = asking.withLock {
            auth.bindNext(operationId)
            try {
                val result = gate.authorize(action, ctx, promptReason)
                result to if (result is Authorization.Granted) auth.takeProof() else null
            } finally {
                auth.bindNext(null)
            }
        }
        return when (a) {
            is Authorization.Refused -> DeleteAuthorization.Refused(
                when (a.reason) {
                    RefusalReason.NO_DEVICE_LOCK -> DriveReason.NO_DEVICE_LOCK
                    RefusalReason.OFFLINE -> DriveReason.DELETE_OFFLINE
                    RefusalReason.USE_PHONE -> DriveReason.AUTH_NOT_AVAILABLE
                },
            )
            is Authorization.Denied -> DeleteAuthorization.Refused(
                when (a.result) {
                    AuthResult.CANCELLED -> DriveReason.AUTH_CANCELLED
                    AuthResult.LOCK_NOT_SET -> DriveReason.AUTH_LOCK_NOT_SET
                    AuthResult.NOT_AVAILABLE -> DriveReason.AUTH_NOT_AVAILABLE
                    AuthResult.LOCKED_OUT -> DriveReason.AUTH_LOCKED_OUT
                    AuthResult.FAILED, AuthResult.SUCCESS -> DriveReason.AUTH_FAILED
                },
            )
            is Authorization.Paused -> DeleteAuthorization.Refused(
                if (a.decision == RunDecision.PausedNoLock) DriveReason.AUTH_PAUSED_NO_LOCK else DriveReason.AUTH_PAUSED_UNKNOWN,
            )
            is Authorization.Granted -> {
                val grant = a.grant
                val level = levelOf(grant.requirements.level)
                when {
                    operationId == null -> DeleteAuthorization.Granted(null)
                    // A check that passed without signing the operation is no check: fail closed.
                    level != DeletionLevel.L1 && proved == null -> DeleteAuthorization.Refused(DriveReason.AUTH_FAILED)
                    else -> {
                        val token = AuthorizationToken(level, proved?.issuedAtMs ?: grant.grantedAtMs, operationId, proved?.proof.orEmpty())
                        issued.remove(operationId)
                        issued[operationId] = Issued(grant, action, token)
                        while (issued.size > MAX_ISSUED) issued.remove(issued.keys.first())
                        DeleteAuthorization.Granted(token)
                    }
                }
            }
        }
    }

    override fun forget() {
        issued.clear()
    }

    override suspend fun isGenuine(token: AuthorizationToken): Boolean {
        val entry = entryOf(token) ?: return false
        if (!fresh(token)) return false
        return gate.redeem(entry.grant, entry.action) == Redeemed.OK
    }

    override suspend fun stillHolds(token: AuthorizationToken): Boolean {
        val entry = entryOf(token) ?: return false
        if (!fresh(token)) return false
        return gate.beforeRun() == RunDecision.Run
    }

    /** The grant this token was issued for: same operation, level, time and proof (compared in constant time). */
    private fun entryOf(token: AuthorizationToken): Issued? {
        val entry = issued[token.operationId] ?: return null
        val own = entry.token
        if (own.level != token.level || own.issuedAtMs != token.issuedAtMs) return null
        if (!constantTimeEquals(own.proof.encodeToByteArray(), token.proof.encodeToByteArray())) return null
        return entry
    }

    private fun fresh(token: AuthorizationToken): Boolean {
        val age = clock() - token.issuedAtMs
        return age >= 0 && age <= DeletionRules.AUTHORIZATION_MAX_AGE_MS
    }

    companion object {
        /** Grants kept at once, as `DeviceAuthorizationGate.MAX_GRANTS`; a forgotten or unused one no longer grows the map for ever. */
        const val MAX_ISSUED = 4

        fun levelOf(level: DeleteLevel): DeletionLevel = when (level) {
            DeleteLevel.L1 -> DeletionLevel.L1
            DeleteLevel.L2 -> DeletionLevel.L2
            DeleteLevel.L3 -> DeletionLevel.L3
        }
    }
}
