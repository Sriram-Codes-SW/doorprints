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

import app.doorprints.crypto.CryptoProvider
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.deviceauth.DeviceAuth
import app.doorprints.drive.delete.DeletionLevel

/** Android 10 and below: the pass result of [auth] and an in-process HMAC key. Pure over the two seams (common code since S4b-BL-139 O10, so :shared's end-to-end Drive tests use it). */
class SoftwareOperationProver(private val auth: DeviceAuth, private val p: CryptoProvider) : OperationProver {
    private val key: ByteArray by lazy { p.randomBytes(32) }

    /**
      * Authenticates (L3 for a level 3 deletion, else L2), then signs the operation with a random key held in memory by
      * this prover.
     */
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
            AuthResult.TIMED_OUT -> ProofOutcome.TimedOut
            AuthResult.LOCKED_OUT -> ProofOutcome.Denied
            AuthResult.LOCK_NOT_SET -> ProofOutcome.NoLock
            AuthResult.NOT_AVAILABLE -> ProofOutcome.Unavailable
            AuthResult.FAILED -> ProofOutcome.Failed
        }
    }
}
