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

import app.doorprints.deviceauth.AuthPlatform
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeletionContext
import app.doorprints.deviceauth.DriveGate
import app.doorprints.deviceauth.FakeLock
import app.doorprints.deviceauth.LockState
import app.doorprints.deviceauth.RecordingActions
import app.doorprints.drive.delete.AuthorizationToken
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.DeletionRules
import app.doorprints.drive.delete.Refusal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.doorprints.deviceauth.DeletionAction as PolicyAction

/** The phone's grant-to-token bridge (the twin of the web's `RealAuthorizationGate`). */
class PhoneDeletionAuthorizerTest {
    private var now = 1_000_000L
    private val auth = ProofingAuth { now }
    private val lock = FakeLock()
    private val authorizer = PhoneDeletionAuthorizer(DriveGate(AuthPlatform.PHONE, auth, lock, RecordingActions()) { now }, auth) { now }
    private val ctx = DeletionContext(AuthPlatform.PHONE, true, false, true, 5)

    private suspend fun token(op: String = "op-1", action: PolicyAction = PolicyAction.DELETE_ALL_BACKUPS): AuthorizationToken =
        (authorizer.authorize(action, ctx, op, "r") as DeleteAuthorization.Granted).token!!

    @Test
    fun aGrantBecomesATokenBoundToTheOperationAtTheGrantLevel() = runTest {
        val t = token()
        assertEquals(DeletionLevel.L2, t.level)
        assertEquals("op-1", t.operationId)
        assertEquals(now, t.issuedAtMs)
        assertEquals(DeletionLevel.L3, token(action = PolicyAction.DELETE_EVERYTHING).level)
    }

    @Test
    fun noOperationMeansNoToken() = runTest {
        val a = authorizer.authorize(PolicyAction.REVOKE_DEVICE, ctx, null, "r")
        assertIs<DeleteAuthorization.Granted>(a)
        assertNull(a.token)
    }

    @Test
    fun aTokenIsGenuineOnceAndOnlyOnce() = runTest {
        val t = token()
        assertTrue(authorizer.isGenuine(t))
        assertFalse(authorizer.isGenuine(t))
    }

    @Test
    fun aForgedTamperedStaleOrForgottenTokenIsNotGenuine() = runTest {
        val t = token()
        assertFalse(authorizer.isGenuine(t.copy(proof = "999")))
        assertFalse(authorizer.isGenuine(t.copy(proof = "not-a-hex-proof")))
        assertFalse(authorizer.isGenuine(t.copy(operationId = "op-2")))
        assertFalse(authorizer.isGenuine(t.copy(issuedAtMs = t.issuedAtMs + 1)))
        assertFalse(authorizer.isGenuine(t.copy(level = DeletionLevel.L3)))
        now += 60_001
        assertFalse(authorizer.isGenuine(t))
        now = 1_000_000
        val fresh = token()
        authorizer.forget()
        assertFalse(authorizer.isGenuine(fresh))
        assertFalse(authorizer.stillHolds(fresh))
    }

    @Test
    fun onlyTheNewestGrantsAreKeptSoTheMapDoesNotGrowForEver() = runTest {
        val tokens = (1..PhoneDeletionAuthorizer.MAX_ISSUED + 3).map { token("op-$it") }
        // The oldest ones were pushed out: their tokens are no longer known, so never genuine.
        for (old in tokens.take(3)) {
            assertFalse(authorizer.isGenuine(old), "an evicted grant is not redeemable: ${old.operationId}")
            assertFalse(authorizer.stillHolds(old))
        }
        for (recent in tokens.drop(3)) assertTrue(authorizer.stillHolds(recent), recent.operationId)
        assertTrue(authorizer.isGenuine(tokens.last()))
    }

    @Test
    fun aGrantWithinTheCapIsNotEvictedByOthers() = runTest {
        val first = token("op-first")
        repeat(PhoneDeletionAuthorizer.MAX_ISSUED - 1) { token("op-$it") }
        assertTrue(authorizer.isGenuine(first), "MAX_ISSUED grants fit")
    }

    @Test
    fun stillHoldsFollowsFreshnessAndTheLock() = runTest {
        val t = token()
        assertTrue(authorizer.stillHolds(t))
        assertTrue(authorizer.isGenuine(t))
        assertTrue(authorizer.stillHolds(t), "redeeming at the start does not end the run's own checks")
        now += 30_000
        assertTrue(authorizer.stillHolds(t))
        now += 40_000
        assertFalse(authorizer.stillHolds(t))
        now = 1_000_000
        val t2 = token()
        lock.state = LockState.REMOVED
        assertFalse(authorizer.stillHolds(t2))
        assertNotNull(t2)
    }

    @Test
    fun aThrowingLockQuestionMeansNoLock() {
        val broken = object : OperationBoundAuth {
            override fun bindNext(operationId: String?) = Unit
            override fun takeProof(): OperationProofValue? = null
            override fun isDeviceLockEnabled(): Boolean = error("boom")
            override suspend fun authenticate(reason: String, level: app.doorprints.deviceauth.DeleteLevel) = app.doorprints.deviceauth.AuthResult.SUCCESS
        }
        assertFalse(PhoneDeletionAuthorizer(DriveGate(AuthPlatform.PHONE, broken, lock, RecordingActions()) { now }, broken) { now }.isDeviceLockEnabled())
    }

    // ---- S4b-BL-135: the proof the device check signed is in the token ----

    @Test
    fun theTokenCarriesTheProofTheDeviceCheckSignedAtThePass() = runTest {
        now = 5_000_000
        val t = token()
        assertEquals("%064x".format(1), t.proof)
        assertEquals(5_000_000L, t.issuedAtMs)
    }

    @Test
    fun theTokenIsStampedAndAgedFromThePassNotFromTheGrantBeingRecorded() = runTest {
        auth.passedAt = now - 20_000
        val t = token()
        assertEquals(now - 20_000, t.issuedAtMs)
        now += 40_000
        assertTrue(authorizer.stillHolds(t))
        now += 1
        assertFalse(authorizer.isGenuine(t), "61 s after the pass")
    }

    @Test
    fun theControllersOperationIdReachesTheDeviceCheckAndIsClearedAfter() = runTest {
        token("del-plan-a")
        assertEquals(listOf<String?>("del-plan-a", null), auth.boundHistory)
        authorizer.authorize(PolicyAction.REVOKE_DEVICE, ctx, null, "r")
        assertEquals(listOf<String?>("del-plan-a", null, null, null), auth.boundHistory)
    }

    @Test
    fun levelOneAsksNothingAndCarriesNoProof() = runTest {
        val t = token(action = PolicyAction.DELETE_ONE_BACKUP)
        assertEquals(DeletionLevel.L1, t.level)
        assertEquals("", t.proof)
        assertEquals(0, auth.asks)
        assertTrue(authorizer.isGenuine(t))
        assertNull(DeletionRules.authorizationProblem(null, DeletionLevel.L1, "op-1", now))
    }

    @Test
    fun aCheckThatPassedWithoutSigningTheOperationIsRefusedForLevelTwoAndThree() = runTest {
        auth.signs = false
        for (action in listOf(PolicyAction.DELETE_ALL_BACKUPS, PolicyAction.DELETE_EVERYTHING)) {
            assertEquals(DeleteAuthorization.Refused(DriveReason.AUTH_FAILED), authorizer.authorize(action, ctx, "op-1", "r"))
        }
        assertFalse(authorizer.isGenuine(AuthorizationToken(DeletionLevel.L2, now, "op-1", "")))
    }

    @Test
    fun noTokenAtAllRefusesLevelTwoAndThreeAndOnlyThose() {
        assertEquals(Refusal.NOT_AUTHORIZED, DeletionRules.authorizationProblem(null, DeletionLevel.L2, "op-1", now))
        assertEquals(Refusal.NOT_AUTHORIZED, DeletionRules.authorizationProblem(null, DeletionLevel.L3, "op-1", now))
        assertNull(DeletionRules.authorizationProblem(null, DeletionLevel.L1, "op-1", now))
    }

    @Test
    fun aTokenForPlanAIsRefusedForPlanB() = runTest {
        val a = token("op-a")
        assertEquals(Refusal.AUTHORIZATION_OTHER_OPERATION, DeletionRules.authorizationProblem(a, DeletionLevel.L2, "op-b", now))
        val b = token("op-b")
        // A's proof under B's name is not B's proof, and B's under A's name is not A's.
        assertFalse(authorizer.isGenuine(a.copy(operationId = "op-b")))
        assertFalse(authorizer.isGenuine(b.copy(operationId = "op-a")))
        assertTrue(authorizer.isGenuine(a))
        assertTrue(authorizer.isGenuine(b))
    }

    @Test
    fun aTokenForLevelTwoIsRefusedForLevelThree() = runTest {
        val t = token()
        assertEquals(Refusal.AUTHORIZATION_TOO_WEAK, DeletionRules.authorizationProblem(t, DeletionLevel.L3, "op-1", now))
        assertFalse(authorizer.isGenuine(t.copy(level = DeletionLevel.L3)))
    }

    @Test
    fun aFlippedProofCharacterIsRefused() = runTest {
        val t = token()
        val flipped = t.proof.dropLast(1) + (if (t.proof.last() == '0') '1' else '0')
        assertFalse(authorizer.isGenuine(t.copy(proof = flipped)))
        assertFalse(authorizer.isGenuine(t.copy(proof = "")))
        assertTrue(authorizer.isGenuine(t))
    }

    @Test
    fun aTokenOfAnotherAskOfTheSamePlanIsRefusedAfterANewAsk() = runTest {
        val first = token("op-1")
        val second = token("op-1")
        assertFalse(authorizer.isGenuine(first))
        assertTrue(authorizer.isGenuine(second))
    }

    @Test
    fun aTokenOlderThanSixtySecondsOrFromTheFutureIsRefused() = runTest {
        val t = token()
        now += 60_000
        assertNull(DeletionRules.authorizationProblem(t, DeletionLevel.L2, "op-1", now))
        assertTrue(authorizer.stillHolds(t))
        now += 1
        assertEquals(Refusal.AUTHORIZATION_STALE, DeletionRules.authorizationProblem(t, DeletionLevel.L2, "op-1", now))
        assertFalse(authorizer.isGenuine(t))
        val future = token("op-2")
        now -= 5_000
        assertEquals(Refusal.AUTHORIZATION_STALE, DeletionRules.authorizationProblem(future, DeletionLevel.L2, "op-2", now))
        assertFalse(authorizer.isGenuine(future))
        assertFalse(authorizer.stillHolds(future))
    }

    @Test
    fun aLockRemovedBetweenTheGrantAndTheStartRefusesTheStartAndEndsIt() = runTest {
        val t = token()
        lock.state = LockState.REMOVED
        assertFalse(authorizer.isGenuine(t))
        assertFalse(authorizer.stillHolds(t))
    }

    @Test
    fun aLockRemovedBeforeThePromptMeansNoPrompt() = runTest {
        lock.state = LockState.REMOVED
        assertEquals(DeleteAuthorization.Refused(DriveReason.AUTH_PAUSED_NO_LOCK), authorizer.authorize(PolicyAction.DELETE_ALL_BACKUPS, ctx, "op-1", "r"))
        assertEquals(0, auth.asks)
    }

    @Test
    fun cancelledDeniedAndNoLockAreThreeDifferentRefusals() = runTest {
        val seen = listOf(AuthResult.CANCELLED, AuthResult.LOCKED_OUT, AuthResult.LOCK_NOT_SET, AuthResult.NOT_AVAILABLE, AuthResult.FAILED).map { r ->
            auth.next = r
            (authorizer.authorize(PolicyAction.DELETE_ALL_BACKUPS, ctx, "op-1", "r") as DeleteAuthorization.Refused).reason
        }
        assertEquals(5, seen.toSet().size)
        assertEquals(listOf(DriveReason.AUTH_CANCELLED, DriveReason.AUTH_LOCKED_OUT, DriveReason.AUTH_LOCK_NOT_SET), seen.take(3))
        auth.next = AuthResult.SUCCESS
        assertNull(auth.takeProof(), "a refusal leaves no proof behind")
    }
}
