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

import app.doorprints.deviceauth.LockLostDetector
import app.doorprints.deviceauth.LockState
import app.doorprints.drive.delete.AuthorizationToken
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.DeletionPlan
import app.doorprints.drive.delete.DeletionRules
import app.doorprints.drive.delete.Refusal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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

class DeviceAuthorizationGateTest {
    private var now = 1_000_000L
    private val lock = MutableLock()
    private val proof = "a".repeat(64)
    private val prover = FakeProver { _, _, clock -> ProofOutcome.Proved(clock(), proof) }
    private val gate = DeviceAuthorizationGate(prover, lock, { now }, promptTimeoutMs = 200)

    private fun granted(r: DeleteAuthorization): AuthorizationToken = (r as DeleteAuthorization.Granted).token

    // ---- policy: which levels need authentication ----

    @Test fun `L1 needs no authentication and the prover is never asked`() = runBlocking {
        assertEquals(DeleteAuthorization.NotRequired, gate.authorize(plan(DeletionLevel.L1), "Delete"))
        assertEquals(0, prover.calls)
    }

    @Test fun `L2 and L3 ask the prover with the plan's operation and level`() = runBlocking {
        for (level in listOf(DeletionLevel.L2, DeletionLevel.L3)) {
            var seen: Pair<String, DeletionLevel>? = null
            prover.script = { op, lv, clock -> seen = op to lv; ProofOutcome.Proved(clock(), proof) }
            val p = plan(level)
            val token = granted(gate.authorize(p, "Delete everything"))
            assertEquals(p.operationId to level, seen)
            assertEquals(level, token.level)
            assertEquals(p.operationId, token.operationId)
            assertEquals("Delete everything", prover.lastReason)
        }
    }

    @Test fun `the token carries the moment the person authenticated`() = runBlocking {
        prover.script = { _, _, _ -> now += 7_000; ProofOutcome.Proved(now, proof) }
        assertEquals(1_007_000L, granted(gate.authorize(plan(DeletionLevel.L2), "x")).issuedAtMs)
    }

    // ---- distinct outcomes ----

    @Test fun `every way it can end is its own result`() = runBlocking {
        val cases = mapOf(
            ProofOutcome.Denied to DeleteAuthorization.Denied,
            ProofOutcome.Cancelled to DeleteAuthorization.Cancelled,
            ProofOutcome.TimedOut to DeleteAuthorization.TimedOut,
            ProofOutcome.NoLock to DeleteAuthorization.LockRemoved,
            ProofOutcome.Unavailable to DeleteAuthorization.Unavailable,
            ProofOutcome.Failed to DeleteAuthorization.Failed,
        )
        for ((outcome, expected) in cases) {
            prover.script = { _, _, _ -> outcome }
            assertEquals(outcome.toString(), expected, gate.authorize(plan(DeletionLevel.L2), "x"))
        }
        assertEquals(cases.size, cases.values.toSet().size)
    }

    @Test fun `a prover that throws is a failure and cancellation still propagates`() {
        prover.script = { _, _, _ -> throw IllegalStateException("platform") }
        assertEquals(DeleteAuthorization.Failed, runBlocking { gate.authorize(plan(DeletionLevel.L2), "x") })
        prover.script = { _, _, _ -> throw CancellationException("stop") }
        assertThrows(CancellationException::class.java) { runBlocking { gate.authorize(plan(DeletionLevel.L2), "x") } }
    }

    @Test fun `a prompt left open too long times out and is cancelled`() = runBlocking {
        val cancelled = CompletableDeferred<Boolean>()
        prover.script = { _, _, _ ->
            try { awaitCancellation() } finally { cancelled.complete(true) }
        }
        assertEquals(DeleteAuthorization.TimedOut, gate.authorize(plan(DeletionLevel.L2), "x"))
        assertTrue(cancelled.await())
    }

    @Test fun `a proof from the future or older than 60 seconds is not accepted`() = runBlocking {
        prover.script = { _, _, _ -> ProofOutcome.Proved(now + 1, proof) }
        assertEquals(DeleteAuthorization.Failed, gate.authorize(plan(DeletionLevel.L2), "x"))
        prover.script = { _, _, _ -> ProofOutcome.Proved(now - 60_001, proof) }
        assertEquals(DeleteAuthorization.TimedOut, gate.authorize(plan(DeletionLevel.L2), "x"))
    }

    @Test fun `a proof that is not 64 lower-case hex digits is a failure`() = runBlocking {
        for (bad in listOf("", "a".repeat(63), "A".repeat(64), "g".repeat(64))) {
            prover.script = { _, _, clock -> ProofOutcome.Proved(clock(), bad) }
            assertEquals(bad, DeleteAuthorization.Failed, gate.authorize(plan(DeletionLevel.L2), "x"))
        }
    }

    // ---- the lock ----

    @Test fun `no screen lock before the prompt means no prompt`() = runBlocking {
        lock.state = LockState.REMOVED
        assertEquals(DeleteAuthorization.LockRemoved, gate.authorize(plan(DeletionLevel.L2), "x"))
        assertEquals(0, prover.calls)
    }

    @Test fun `a lock that cannot be read fails closed with no prompt`() = runBlocking {
        lock.state = LockState.UNKNOWN
        assertEquals(DeleteAuthorization.Unavailable, gate.authorize(plan(DeletionLevel.L2), "x"))
        assertEquals(0, prover.calls)
    }

    @Test fun `a lock removed while the prompt was up grants nothing`() = runBlocking {
        prover.script = { _, _, clock -> lock.state = LockState.REMOVED; ProofOutcome.Proved(clock(), proof) }
        assertEquals(DeleteAuthorization.LockRemoved, gate.authorize(plan(DeletionLevel.L2), "x"))
    }

    @Test fun `a lock removed half way stops the run and stays stopped`() = runBlocking {
        val token = granted(gate.authorize(plan(DeletionLevel.L2), "x"))
        assertTrue(gate.isGenuine(token))
        assertTrue(gate.stillHolds(token))
        lock.state = LockState.REMOVED
        assertFalse(gate.stillHolds(token))
        lock.state = LockState.PRESENT
        assertFalse("a lock set again does not revive the grant", gate.stillHolds(token))
    }

    @Test fun `an unreadable lock half way stops the run`() = runBlocking {
        val token = granted(gate.authorize(plan(DeletionLevel.L2), "x"))
        gate.isGenuine(token)
        lock.state = LockState.UNKNOWN
        assertFalse(gate.stillHolds(token))
    }

    @Test fun `a lock check that throws is treated as unknown`() = runBlocking {
        val throwing = DeviceAuthorizationGate(prover, LockLostDetector { throw IllegalStateException("x") }, { now })
        assertEquals(DeleteAuthorization.Unavailable, throwing.authorize(plan(DeletionLevel.L2), "x"))
    }

    // ---- bound to the operation, single use, expiry ----

    @Test fun `a grant for plan A is refused for plan B`() = runBlocking {
        val a = plan(DeletionLevel.L2, listOf("a1"))
        val b = plan(DeletionLevel.L2, listOf("b1"))
        val tokenA = granted(gate.authorize(a, "x"))
        // the service's own check names it
        assertEquals(Refusal.AUTHORIZATION_OTHER_OPERATION, DeletionRules.authorizationProblem(tokenA, DeletionLevel.L2, b.operationId, now))
        // and a token re-labelled for B, carrying A's proof, is not genuine
        val relabelled = tokenA.copy(operationId = b.operationId)
        assertFalse(gate.isGenuine(relabelled))
        assertTrue(gate.isGenuine(tokenA))
    }

    @Test fun `a forged proof, level or time is not genuine`() = runBlocking {
        val token = granted(gate.authorize(plan(DeletionLevel.L2), "x"))
        assertFalse(gate.isGenuine(token.copy(proof = "b".repeat(64))))
        assertFalse(gate.isGenuine(token.copy(level = DeletionLevel.L3)))
        assertFalse(gate.isGenuine(token.copy(issuedAtMs = token.issuedAtMs + 1)))
        assertTrue(gate.isGenuine(token))
    }

    @Test fun `a token this gate never issued is not genuine`() = runBlocking {
        val p = plan(DeletionLevel.L2)
        assertFalse(gate.isGenuine(AuthorizationToken(DeletionLevel.L2, now, p.operationId, proof)))
        assertFalse(gate.stillHolds(AuthorizationToken(DeletionLevel.L2, now, p.operationId, proof)))
    }

    @Test fun `a grant starts one run only`() = runBlocking {
        val token = granted(gate.authorize(plan(DeletionLevel.L2), "x"))
        assertTrue(gate.isGenuine(token))
        assertFalse(gate.isGenuine(token))
        assertTrue("the run that started keeps going", gate.stillHolds(token))
    }

    @Test fun `a grant is not usable for a run before it started`() = runBlocking {
        val token = granted(gate.authorize(plan(DeletionLevel.L2), "x"))
        assertFalse("not started: nothing to continue", gate.stillHolds(token))
    }

    @Test fun `a grant expires after 60 seconds`() = runBlocking {
        val token = granted(gate.authorize(plan(DeletionLevel.L2), "x"))
        now += 60_000
        assertTrue(gate.isGenuine(token))
        val second = granted(gate.authorize(plan(DeletionLevel.L2, listOf("z")), "x"))
        now += 60_001
        assertFalse(gate.isGenuine(second))
    }

    @Test fun `a new authorization for the same operation replaces the old one`() = runBlocking {
        val p = plan(DeletionLevel.L2)
        val first = granted(gate.authorize(p, "x"))
        prover.script = { _, _, clock -> ProofOutcome.Proved(clock(), "c".repeat(64)) }
        val second = granted(gate.authorize(p, "x"))
        assertFalse(gate.isGenuine(first))
        assertTrue(gate.isGenuine(second))
    }

    @Test fun `revoking drops every grant`() = runBlocking {
        val token = granted(gate.authorize(plan(DeletionLevel.L2), "x"))
        gate.revokeAll()
        assertFalse(gate.isGenuine(token))
    }

    @Test fun `only a few grants are kept`() = runBlocking {
        val first = granted(gate.authorize(plan(DeletionLevel.L2, listOf("0")), "x"))
        repeat(DeviceAuthorizationGate.MAX_GRANTS) { gate.authorize(plan(DeletionLevel.L2, listOf("${it + 1}")), "x") }
        assertFalse(gate.isGenuine(first))
    }

    @Test fun `no token is made for a refusal`() = runBlocking {
        prover.script = { _, _, _ -> ProofOutcome.Cancelled }
        val p = plan(DeletionLevel.L2)
        gate.authorize(p, "x")
        assertNull((gate.authorize(p, "x") as? DeleteAuthorization.Granted)?.token)
        assertFalse(gate.isGenuine(AuthorizationToken(DeletionLevel.L2, now, p.operationId, proof)))
    }

}
