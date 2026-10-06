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

import app.doorprints.deviceauth.AuthPlatform
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeletionContext
import app.doorprints.deviceauth.DriveGate
import app.doorprints.deviceauth.LockLossActions
import app.doorprints.deviceauth.LockLostDetector
import app.doorprints.deviceauth.LockState
import app.doorprints.drive.connect.DeleteAuthorization
import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.connect.PhoneDeletionAuthorizer
import app.doorprints.drive.delete.AuthorizationToken
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.DeletionPlan
import app.doorprints.drive.delete.DeletionRules
import app.doorprints.drive.delete.Refusal
import app.doorprints.drive.wiring.ProverDeviceAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import app.doorprints.deviceauth.DeletionAction as PolicyAction

class FakeProver(var script: suspend (String, DeletionLevel, () -> Long) -> ProofOutcome) : OperationProver {
    var calls = 0
    var lastReason: String? = null
    override suspend fun prove(operationId: String, level: DeletionLevel, reason: String, now: () -> Long): ProofOutcome {
        calls++
        lastReason = reason
        return script(operationId, level, now)
    }
}

class MutableLock(var state: LockState = LockState.PRESENT) : LockLostDetector {
    override fun lockState() = state
}

internal fun plan(level: DeletionLevel, ids: List<String> = listOf("f1", "f2"), root: String = "root"): DeletionPlan = DeletionPlan(
    action = DeletionAction.AllBackups, level = level, rootId = root, items = emptyList(), totals = emptyMap(),
    foreignKept = 0, createdAtMs = 0, operationId = DeletionRules.operationId(level, root, ids),
)

/**
 * The phone's deletion proof end to end over production code (S4b-BL-135, docs/15 §10.2): the real [DriveGate], the real
 * [PhoneDeletionAuthorizer] and the real [ProverDeviceAuth] around a scripted [OperationProver] (the platform prompt).
 * The same object is the controller's `DeleteAuthorizer` and the service's `AuthorizationGate`.
 */
class PhoneAuthorizationProofTest {
    private var now = 1_000_000L
    private val lock = MutableLock()
    // A stand-in for the Keystore HMAC: bound to the operation and the time, so two plans never share a proof.
    private fun signed(op: String, at: Long) =
        OperationProof.hex(java.security.MessageDigest.getInstance("SHA-256").digest(OperationProof.message(op, at)))
    private val proof = "a".repeat(64)
    private val prover = FakeProver { op, _, clock -> clock().let { ProofOutcome.Proved(it, signed(op, it)) } }
    private val auth = ProverDeviceAuth(prover, { true }, { now })
    private val gate = DriveGate(AuthPlatform.PHONE, auth, lock, object : LockLossActions {
        override fun dropLocalKeys() = Unit
        override fun requireReenrolment() = Unit
    }) { now }
    private val authorizer = PhoneDeletionAuthorizer(gate, auth) { now }
    private val ctx = DeletionContext(AuthPlatform.PHONE, true, false, true, 5)

    private suspend fun ask(p: DeletionPlan, reason: String = "x"): DeleteAuthorization {
        val action = when (p.level) {
            DeletionLevel.L1 -> PolicyAction.DELETE_ONE_BACKUP
            DeletionLevel.L2 -> PolicyAction.DELETE_ALL_BACKUPS
            DeletionLevel.L3 -> PolicyAction.DELETE_EVERYTHING
        }
        return authorizer.authorize(action, ctx, p.operationId, reason)
    }

    private fun token(r: DeleteAuthorization): AuthorizationToken = (r as DeleteAuthorization.Granted).token!!

    private fun refusal(r: DeleteAuthorization): DriveReason = (r as DeleteAuthorization.Refused).reason

    // ---- which levels ask, and what they are asked for ----

    @Test fun `L1 needs no authentication and no proof`() = runBlocking {
        val t = token(ask(plan(DeletionLevel.L1)))
        assertEquals(0, prover.calls)
        assertEquals("", t.proof)
        assertNull(DeletionRules.authorizationProblem(null, DeletionLevel.L1, "any", now))
    }

    @Test fun `L2 and L3 ask the prover with the plan's operation id and level`() = runBlocking {
        for (level in listOf(DeletionLevel.L2, DeletionLevel.L3)) {
            var seen: Pair<String, DeletionLevel>? = null
            prover.script = { op, lv, clock -> seen = op to lv; ProofOutcome.Proved(clock(), signed(op, clock())) }
            val p = plan(level)
            val t = token(ask(p, "Delete everything"))
            assertEquals(p.operationId to level, seen)
            assertEquals(level, t.level)
            assertEquals(p.operationId, t.operationId)
            assertEquals("Delete everything", prover.lastReason)
        }
    }

    @Test fun `the token carries the proof the prover signed and the moment the person passed`() = runBlocking {
        prover.script = { _, _, _ -> now += 7_000; ProofOutcome.Proved(now, "c".repeat(64)) }
        val t = token(ask(plan(DeletionLevel.L2)))
        assertEquals(1_007_000L, t.issuedAtMs)
        assertEquals("c".repeat(64), t.proof)
    }

    @Test fun `a policy action with no plan is still asked, under a one-off label, and makes no token`() = runBlocking {
        var seen = ""
        prover.script = { op, _, clock -> seen = op; ProofOutcome.Proved(clock(), proof) }
        val a = authorizer.authorize(PolicyAction.REVOKE_DEVICE, ctx, null, "Revoke")
        assertNull((a as DeleteAuthorization.Granted).token)
        assertTrue(seen.startsWith(ProverDeviceAuth.OPERATION_PREFIX))
    }

    // ---- distinct outcomes ----

    @Test fun `cancelled and denied and no lock are different refusals, and a timed out prompt is a cancel`() = runBlocking {
        val cases = mapOf(
            ProofOutcome.Cancelled to DriveReason.AUTH_CANCELLED,
            ProofOutcome.Denied to DriveReason.AUTH_LOCKED_OUT,
            ProofOutcome.NoLock to DriveReason.AUTH_LOCK_NOT_SET,
            ProofOutcome.Unavailable to DriveReason.AUTH_NOT_AVAILABLE,
            ProofOutcome.Failed to DriveReason.AUTH_FAILED,
            ProofOutcome.TimedOut to DriveReason.AUTH_CANCELLED,
        )
        for ((outcome, reason) in cases) {
            prover.script = { _, _, _ -> outcome }
            assertEquals(outcome.toString(), reason, refusal(ask(plan(DeletionLevel.L2))))
        }
        assertEquals(5, cases.values.toSet().size)
    }

    @Test fun `a prover that throws is a failure and cancellation still propagates`() {
        prover.script = { _, _, _ -> throw IllegalStateException("platform") }
        assertEquals(DriveReason.AUTH_FAILED, refusal(runBlocking { ask(plan(DeletionLevel.L2)) }))
        prover.script = { _, _, _ -> throw CancellationException("stop") }
        assertThrows(CancellationException::class.java) { runBlocking { ask(plan(DeletionLevel.L2)) } }
    }

    @Test fun `a proof that is not 64 lower-case hex digits is a failure and makes no token`() = runBlocking {
        for (bad in listOf("", "a".repeat(63), "A".repeat(64), "g".repeat(64))) {
            prover.script = { _, _, clock -> ProofOutcome.Proved(clock(), bad) }
            assertEquals(bad, DriveReason.AUTH_FAILED, refusal(ask(plan(DeletionLevel.L2))))
        }
    }

    @Test fun `a refusal leaves no proof for the next ask to pick up`() = runBlocking {
        val p = plan(DeletionLevel.L2)
        token(ask(p))
        prover.script = { _, _, _ -> ProofOutcome.Cancelled }
        assertEquals(DriveReason.AUTH_CANCELLED, refusal(ask(p)))
        assertNull(auth.takeProof())
    }

    // ---- the lock ----

    @Test fun `no screen lock before the prompt means no prompt`() = runBlocking {
        lock.state = LockState.REMOVED
        assertEquals(DriveReason.AUTH_PAUSED_NO_LOCK, refusal(ask(plan(DeletionLevel.L2))))
        assertEquals(0, prover.calls)
    }

    @Test fun `a lock that cannot be read fails closed with no prompt`() = runBlocking {
        lock.state = LockState.UNKNOWN
        assertEquals(DriveReason.AUTH_PAUSED_UNKNOWN, refusal(ask(plan(DeletionLevel.L2))))
        assertEquals(0, prover.calls)
    }

    @Test fun `a lock removed between the grant and the start refuses the start`() = runBlocking {
        val t = token(ask(plan(DeletionLevel.L2)))
        lock.state = LockState.REMOVED
        assertFalse(authorizer.isGenuine(t))
        assertFalse(authorizer.stillHolds(t))
    }

    @Test fun `a lock removed half way stops the run`() = runBlocking {
        val t = token(ask(plan(DeletionLevel.L2)))
        assertTrue(authorizer.isGenuine(t))
        assertTrue(authorizer.stillHolds(t))
        lock.state = LockState.REMOVED
        assertFalse(authorizer.stillHolds(t))
    }

    // ---- bound to the operation, the level, the proof; single use; 60 seconds ----

    @Test fun `a token for plan A is refused for plan B`() = runBlocking {
        val a = plan(DeletionLevel.L2, listOf("a1"))
        val b = plan(DeletionLevel.L2, listOf("b1"))
        val tokenA = token(ask(a))
        val tokenB = token(ask(b))
        assertEquals(Refusal.AUTHORIZATION_OTHER_OPERATION, DeletionRules.authorizationProblem(tokenA, DeletionLevel.L2, b.operationId, now))
        assertNotEquals(tokenA.proof, tokenB.proof)
        // A's proof under B's name is not B's token
        assertFalse(authorizer.isGenuine(tokenA.copy(operationId = b.operationId)))
        assertTrue(authorizer.isGenuine(tokenA))
        assertTrue(authorizer.isGenuine(tokenB))
    }

    @Test fun `a token for level two is refused for level three`() = runBlocking {
        val t = token(ask(plan(DeletionLevel.L2)))
        assertEquals(Refusal.AUTHORIZATION_TOO_WEAK, DeletionRules.authorizationProblem(t, DeletionLevel.L3, t.operationId, now))
        assertFalse(authorizer.isGenuine(t.copy(level = DeletionLevel.L3)))
    }

    @Test fun `a replayed token is refused`() = runBlocking {
        val t = token(ask(plan(DeletionLevel.L2)))
        assertTrue(authorizer.isGenuine(t))
        assertFalse(authorizer.isGenuine(t))
        assertTrue("the run that started keeps going", authorizer.stillHolds(t))
    }

    @Test fun `a token older than 60 seconds is refused and one at 60 seconds is not`() = runBlocking {
        val t = token(ask(plan(DeletionLevel.L2)))
        now += 60_000
        assertNull(DeletionRules.authorizationProblem(t, DeletionLevel.L2, t.operationId, now))
        val late = token(ask(plan(DeletionLevel.L2, listOf("z"))))
        now += 60_001
        assertEquals(Refusal.AUTHORIZATION_STALE, DeletionRules.authorizationProblem(late, DeletionLevel.L2, late.operationId, now))
        assertFalse(authorizer.isGenuine(late))
    }

    @Test fun `a token from the future is refused`() = runBlocking {
        val t = token(ask(plan(DeletionLevel.L2)))
        now -= 1
        assertEquals(Refusal.AUTHORIZATION_STALE, DeletionRules.authorizationProblem(t, DeletionLevel.L2, t.operationId, now))
        assertFalse(authorizer.isGenuine(t))
    }

    @Test fun `a flipped proof byte or a forged time is refused`() = runBlocking {
        val t = token(ask(plan(DeletionLevel.L2)))
        assertFalse(authorizer.isGenuine(t.copy(proof = "b" + t.proof.drop(1))))
        assertFalse(authorizer.isGenuine(t.copy(proof = t.proof.dropLast(1) + "b")))
        assertFalse(authorizer.isGenuine(t.copy(issuedAtMs = t.issuedAtMs + 1)))
        assertTrue(authorizer.isGenuine(t))
    }

    @Test fun `no token at all refuses L2 and L3 but not L1`() {
        assertEquals(Refusal.NOT_AUTHORIZED, DeletionRules.authorizationProblem(null, DeletionLevel.L2, "x", now))
        assertEquals(Refusal.NOT_AUTHORIZED, DeletionRules.authorizationProblem(null, DeletionLevel.L3, "x", now))
        assertNull(DeletionRules.authorizationProblem(null, DeletionLevel.L1, "x", now))
        assertFalse(runBlocking { authorizer.isGenuine(AuthorizationToken(DeletionLevel.L2, now, "x", proof)) })
    }

    @Test fun `a new authorization of the same plan replaces the old one`() = runBlocking {
        val p = plan(DeletionLevel.L2)
        val first = token(ask(p))
        prover.script = { _, _, clock -> ProofOutcome.Proved(clock(), "c".repeat(64)) }
        val second = token(ask(p))
        assertNotEquals(first.proof, second.proof)
        assertFalse(authorizer.isGenuine(first))
        assertTrue(authorizer.isGenuine(second))
    }

    @Test fun `forgetting drops every grant`() = runBlocking {
        val t = token(ask(plan(DeletionLevel.L2)))
        authorizer.forget()
        assertFalse(authorizer.isGenuine(t))
    }

    @Test fun `the phone never asks for another level than the policy needs`() = runBlocking {
        assertEquals(AuthResult.FAILED, auth.authenticate("x", app.doorprints.deviceauth.DeleteLevel.L1))
        assertEquals(0, prover.calls)
    }
}
