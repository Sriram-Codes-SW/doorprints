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

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.CryptoProvider
import app.doorprints.deviceauth.AuthGrant
import app.doorprints.deviceauth.AuthPlatform
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.Authorization
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.deviceauth.DeletionContext
import app.doorprints.deviceauth.DriveGate
import app.doorprints.deviceauth.Redeemed
import app.doorprints.deviceauth.RefusalReason
import app.doorprints.deviceauth.RunDecision
import app.doorprints.drive.delete.AuthorizationGate
import app.doorprints.drive.delete.AuthorizationToken
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.deviceauth.DeletionAction as PolicyAction

/**
 * Connects [DriveGate] (S4b-BL-127: the device's own check, the lock watch) to [app.doorprints.drive.delete.DriveDeletionService]'s
 * [AuthorizationGate] (S4b-BL-119). The screen calls [authorize] after the person confirmed; a granted check becomes an
 * [AuthorizationToken] whose `proof` is an HMAC (a key random per process, never stored) over the token's fields and the
 * grant's number. [isGenuine] redeems the grant once, for its own action, inside the policy's time and with the lock still
 * there; [stillHolds] is asked before every file and stops the run when the lock is gone.
 *
 * A token cannot be made by anything that does not hold this object, and a token is useless for another operation,
 * a second run, or after a minute (all three are refused by the service's own checks, which this only adds to).
 */
class DriveGateAuthorizer(
    private val gate: DriveGate,
    private val p: CryptoProvider,
    private val clock: () -> Long,
) : AuthorizationGate {
    private val key = p.randomBytes(32)
    private val known = HashMap<String, Issued>()

    private class Issued(val grant: AuthGrant, val action: PolicyAction, val level: DeletionLevel, val operationId: String, val issuedAtMs: Long)

    sealed interface Outcome {
        /** L1: nothing to ask. */
        data object NoneNeeded : Outcome

        class Token(val token: AuthorizationToken) : Outcome

        /** "To do this, use Doorprints on your phone", no screen lock, or offline: the policy said no. */
        data class Refused(val reason: RefusalReason) : Outcome

        /** The check did not pass or was cancelled: "Nothing was deleted." */
        data class Denied(val result: AuthResult) : Outcome

        /** The lock went away: paused. */
        data class Paused(val decision: RunDecision) : Outcome
    }

    /** What [DeletionContext] to give the policy for [action] at [level]. [backupsLeft] only matters for one backup. */
    suspend fun authorize(
        action: DeletionAction,
        level: DeletionLevel,
        operationId: String,
        ctx: DeletionContext,
        reason: String,
    ): Outcome {
        val policyAction = policyActionOf(action)
        val ctxFor = if (action is DeletionAction.OneBackup) ctx.copy(backupsLeft = if (level == DeletionLevel.L1) 2 else 1) else ctx
        val granted = when (val a = gate.authorize(policyAction, ctxFor, reason)) {
            is Authorization.Refused -> return Outcome.Refused(a.reason)
            is Authorization.Denied -> return Outcome.Denied(a.result)
            is Authorization.Paused -> return Outcome.Paused(a.decision)
            is Authorization.Granted -> a.grant
        }
        // The policy and the plan must agree on the level; the stricter of the two never gives less.
        if (levelOf(granted.requirements.level) < level) return Outcome.Denied(AuthResult.FAILED)
        if (level == DeletionLevel.L1) return Outcome.NoneNeeded
        val issued = granted.grantedAtMs
        val proof = proofOf(level, issued, operationId, granted)
        known[proof] = Issued(granted, policyAction, level, operationId, issued)
        return Outcome.Token(AuthorizationToken(level, issued, operationId, proof))
    }

    override suspend fun isGenuine(token: AuthorizationToken): Boolean {
        val issued = known[token.proof] ?: return false
        if (issued.level != token.level || issued.operationId != token.operationId || issued.issuedAtMs != token.issuedAtMs) return false
        val expected = proofOf(issued.level, issued.issuedAtMs, issued.operationId, issued.grant)
        if (!OAuthFlow.constantTimeEquals(expected, token.proof)) return false
        val ok = gate.redeem(issued.grant, issued.action) == Redeemed.OK
        if (!ok) known.remove(token.proof)
        return ok
    }

    override suspend fun stillHolds(token: AuthorizationToken): Boolean {
        val issued = known[token.proof] ?: return false
        if (issued.operationId != token.operationId) return false
        return gate.beforeRun() == RunDecision.Run
    }

    /** Forgets a token that was not used (the dialog was dismissed). */
    fun forget(token: AuthorizationToken) {
        known.remove(token.proof)
    }

    private fun proofOf(level: DeletionLevel, issuedAtMs: Long, operationId: String, grant: AuthGrant): String {
        val data = Bytes.utf8("doorprints-delete-token/1\n${level.name}\n$issuedAtMs\n$operationId\n${grantNumber(grant)}")
        return Bytes.hex(p.hmacSha256(key, data))
    }

    // AuthGrant.id is internal to this module (shared), which is where this class lives.
    private fun grantNumber(grant: AuthGrant): Long = grant.id

    companion object {
        fun policyActionOf(action: DeletionAction): PolicyAction = when (action) {
            is DeletionAction.OneBackup -> PolicyAction.DELETE_ONE_BACKUP
            DeletionAction.OlderBackups, DeletionAction.AllBackups -> PolicyAction.DELETE_ALL_BACKUPS
            DeletionAction.Everything -> PolicyAction.DELETE_EVERYTHING
        }

        private fun levelOf(l: DeleteLevel): DeletionLevel = when (l) {
            DeleteLevel.L1 -> DeletionLevel.L1
            DeleteLevel.L2 -> DeletionLevel.L2
            DeleteLevel.L3 -> DeletionLevel.L3
        }

        fun platformOf(phone: Boolean): AuthPlatform = if (phone) AuthPlatform.PHONE else AuthPlatform.WEBSITE
    }
}
