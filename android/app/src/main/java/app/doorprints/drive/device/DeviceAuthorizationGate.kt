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

import app.doorprints.crypto.constantTimeEquals
import app.doorprints.deviceauth.LockLostDetector
import app.doorprints.deviceauth.LockState
import app.doorprints.drive.delete.AuthorizationGate
import app.doorprints.drive.delete.AuthorizationToken
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.DeletionPlan
import app.doorprints.drive.delete.DeletionRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Device authentication for deletes on Android (S4b-BL-127, docs/15 §10.1, §10.2). The decisions (which level asks,
 * what a grant is bound to, single use, 60 seconds, the lock) are here and run on the JVM over a fake [OperationProver];
 * the platform prompt is in `AndroidOperationProvers.kt`. Web twin: `web-authorizer.ts` / `deletion-proof.ts`.
 */

/** What the platform said when asked to prove [operationId] for a person at the device. */
sealed interface ProofOutcome {
    /** The person authenticated at [issuedAtMs]; [proof] is 64 lower-case hex digits bound to the operation. */
    data class Proved(val issuedAtMs: Long, val proof: String) : ProofOutcome

    /** The platform refused the person (too many wrong tries; it locks them out for a while). */
    data object Denied : ProofOutcome

    /** The person dismissed the prompt. */
    data object Cancelled : ProofOutcome

    /** The prompt ended by itself without an answer. */
    data object TimedOut : ProofOutcome

    /** The platform found no screen lock. */
    data object NoLock : ProofOutcome

    /** No way to ask on this phone (no activity to show it on, no credential screen wired). Fails closed. */
    data object Unavailable : ProofOutcome

    /** Anything else that went wrong. */
    data object Failed : ProofOutcome
}

/**
 * Asks the person and, when they pass, signs the operation (docs/15 §10.2: a Keystore HMAC key through a `CryptoObject`
 * on Android 11+, a process-local key below). [now] is read **after** the person passed, for the proof's time.
 */
interface OperationProver {
    suspend fun prove(operationId: String, level: DeletionLevel, reason: String, now: () -> Long): ProofOutcome
}

/** What [DeviceAuthorizationGate.authorize] says. Everything but [Granted] and [NotRequired] means "Nothing was deleted". */
sealed interface DeleteAuthorization {
    /** L1: the dialog is the whole check. */
    data object NotRequired : DeleteAuthorization

    data class Granted(val token: AuthorizationToken) : DeleteAuthorization
    data object Denied : DeleteAuthorization
    data object Cancelled : DeleteAuthorization
    data object TimedOut : DeleteAuthorization

    /** The screen lock is gone (before, or while the prompt was up): Drive is paused (docs/15 §10.3). */
    data object LockRemoved : DeleteAuthorization
    data object Unavailable : DeleteAuthorization
    data object Failed : DeleteAuthorization
}

/** The proof's message, as on the website (`proofMessage`): `utf8(operationId) ‖ 0x00 ‖ u64be(issuedAtMs)`. */
object OperationProof {
    fun message(operationId: String, issuedAtMs: Long): ByteArray {
        require(issuedAtMs >= 0) { "time" }
        val time = ByteArray(8) { i -> (issuedAtMs ushr (56 - 8 * i)).toByte() }
        return operationId.encodeToByteArray() + byteArrayOf(0) + time
    }

    fun isProof(text: String): Boolean = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }

    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) out.append(digits[(b.toInt() shr 4) and 15]).append(digits[b.toInt() and 15])
        return out.toString()
    }
}

/**
 * The [AuthorizationGate] of the deletion service plus the call that asks the person. A grant is made by [authorize]
 * for **one plan** (its operation id, which already hashes the level, the root and the ordered ids), is good for 60
 * seconds ([DeletionRules.AUTHORIZATION_MAX_AGE_MS]) to **start** one run, and is dropped when the lock goes (docs/15
 * §10.2). Grants live in memory only: a restart asks again, which is the rule ("never cached").
 */
class DeviceAuthorizationGate(
    private val prover: OperationProver,
    private val lock: LockLostDetector,
    private val clock: () -> Long,
    private val promptTimeoutMs: Long = DeletionRules.AUTHORIZATION_MAX_AGE_MS,
) : AuthorizationGate {

    private class Grant(val level: DeletionLevel, val issuedAtMs: Long, val proof: String) {
        var started = false
    }

    private val grants = LinkedHashMap<String, Grant>()

    /** Which plans ask for a factor: L2 and L3 (docs/15 §10.1); L1 is the dialog alone. */
    fun requiresFactor(level: DeletionLevel): Boolean = level != DeletionLevel.L1

    suspend fun authorize(plan: DeletionPlan, reason: String): DeleteAuthorization {
        if (!requiresFactor(plan.level)) return DeleteAuthorization.NotRequired
        when (lockState()) {
            LockState.PRESENT -> Unit
            LockState.REMOVED -> return DeleteAuthorization.LockRemoved
            LockState.UNKNOWN -> return DeleteAuthorization.Unavailable
        }
        val outcome = try {
            withTimeoutOrNull(promptTimeoutMs) { prover.prove(plan.operationId, plan.level, reason, clock) } ?: ProofOutcome.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ProofOutcome.Failed
        }
        return when (outcome) {
            ProofOutcome.Denied -> DeleteAuthorization.Denied
            ProofOutcome.Cancelled -> DeleteAuthorization.Cancelled
            ProofOutcome.TimedOut -> DeleteAuthorization.TimedOut
            ProofOutcome.NoLock -> DeleteAuthorization.LockRemoved
            ProofOutcome.Unavailable -> DeleteAuthorization.Unavailable
            ProofOutcome.Failed -> DeleteAuthorization.Failed
            is ProofOutcome.Proved -> grant(plan, outcome)
        }
    }

    private fun grant(plan: DeletionPlan, p: ProofOutcome.Proved): DeleteAuthorization {
        if (!OperationProof.isProof(p.proof)) return DeleteAuthorization.Failed
        // The lock may have gone while the prompt was up: nothing is granted then.
        if (lockState() != LockState.PRESENT) return DeleteAuthorization.LockRemoved
        val age = clock() - p.issuedAtMs
        if (age < 0) return DeleteAuthorization.Failed
        if (age > DeletionRules.AUTHORIZATION_MAX_AGE_MS) return DeleteAuthorization.TimedOut
        synchronized(grants) {
            grants.remove(plan.operationId)
            grants[plan.operationId] = Grant(plan.level, p.issuedAtMs, p.proof)
            while (grants.size > MAX_GRANTS) grants.remove(grants.keys.first())
        }
        return DeleteAuthorization.Granted(AuthorizationToken(plan.level, p.issuedAtMs, plan.operationId, p.proof))
    }

    /** The service asks once when a run starts: the token is the one issued here, within 60 s, and not yet used. */
    override suspend fun isGenuine(token: AuthorizationToken): Boolean = synchronized(grants) {
        val g = matching(token) ?: return false
        val age = clock() - g.issuedAtMs
        if (g.started || age < 0 || age > DeletionRules.AUTHORIZATION_MAX_AGE_MS) return false
        if (lockState() != LockState.PRESENT) return false
        g.started = true
        true
    }

    /** Asked before every file: the run that started goes on only while the lock is there. A lost lock ends the grant for good. */
    override suspend fun stillHolds(token: AuthorizationToken): Boolean = synchronized(grants) {
        val g = matching(token) ?: return false
        if (!g.started) return false
        if (lockState() != LockState.PRESENT) {
            grants.remove(token.operationId)
            return false
        }
        true
    }

    fun revokeAll() = synchronized(grants) { grants.clear() }

    private fun matching(token: AuthorizationToken): Grant? {
        val g = grants[token.operationId] ?: return null
        if (g.level != token.level || g.issuedAtMs != token.issuedAtMs) return null
        if (!constantTimeEquals(g.proof.encodeToByteArray(), token.proof.encodeToByteArray())) return null
        return g
    }

    private fun lockState(): LockState = try {
        lock.lockState()
    } catch (_: Exception) {
        LockState.UNKNOWN
    }

    companion object {
        /** Grants kept at once (a person starts one deletion at a time); the oldest goes first. */
        const val MAX_GRANTS = 4
    }
}
