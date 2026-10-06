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
import app.doorprints.deviceauth.DeletionAction as PolicyAction

/**
 * The phones' authorisation: [DriveGate] asks for the device check, and this class turns its grant into the
 * [AuthorizationToken] the deletion service wants and, as the [AuthorizationGate], checks it again when the run starts
 * (one use, 60 seconds, bound to the operation) and before every file. The Kotlin twin of the web's
 * `RealAuthorizationGate`; the proof is the grant id (the phone's HMAC under the device lock is S4b-BL-135).
 */
class PhoneDeletionAuthorizer(
    private val gate: DriveGate,
    private val auth: DeviceAuth,
    private val clock: () -> Long,
) : DeleteAuthorizer, AuthorizationGate {
    private class Issued(val grant: AuthGrant, val action: PolicyAction, val operationId: String)

    /** Insertion order, so the oldest goes first when [MAX_ISSUED] is reached (a person starts one deletion at a time). */
    private val issued = LinkedHashMap<Long, Issued>()

    override fun isDeviceLockEnabled(): Boolean = try {
        auth.isDeviceLockEnabled()
    } catch (_: Exception) {
        false
    }

    override suspend fun authorize(action: PolicyAction, ctx: DeletionContext, operationId: String?, promptReason: String): DeleteAuthorization =
        when (val a = gate.authorize(action, ctx, promptReason)) {
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
                if (operationId == null) {
                    DeleteAuthorization.Granted(null)
                } else {
                    issued[grant.id] = Issued(grant, action, operationId)
                    while (issued.size > MAX_ISSUED) issued.remove(issued.keys.first())
                    DeleteAuthorization.Granted(
                        AuthorizationToken(levelOf(grant.requirements.level), grant.grantedAtMs, operationId, grant.id.toString()),
                    )
                }
            }
        }

    override fun forget() {
        issued.clear()
    }

    override suspend fun isGenuine(token: AuthorizationToken): Boolean {
        val entry = entryOf(token) ?: return false
        if (!fresh(entry, token)) return false
        return gate.redeem(entry.grant, entry.action) == Redeemed.OK
    }

    override suspend fun stillHolds(token: AuthorizationToken): Boolean {
        val entry = entryOf(token) ?: return false
        if (!fresh(entry, token)) return false
        return gate.beforeRun() == RunDecision.Run
    }

    private fun entryOf(token: AuthorizationToken): Issued? {
        val entry = issued[token.proof.toLongOrNull() ?: return null] ?: return null
        if (entry.operationId != token.operationId) return null
        if (entry.grant.grantedAtMs != token.issuedAtMs) return null
        if (levelOf(entry.grant.requirements.level) != token.level) return null
        return entry
    }

    private fun fresh(entry: Issued, token: AuthorizationToken): Boolean {
        val age = clock() - entry.grant.grantedAtMs
        return age >= 0 && age <= app.doorprints.drive.delete.DeletionRules.AUTHORIZATION_MAX_AGE_MS && token.operationId == entry.operationId
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
