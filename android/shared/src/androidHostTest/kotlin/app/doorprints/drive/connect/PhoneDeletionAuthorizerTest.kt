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
import app.doorprints.deviceauth.DeletionContext
import app.doorprints.deviceauth.DriveGate
import app.doorprints.deviceauth.FakeDeviceAuth
import app.doorprints.deviceauth.FakeLock
import app.doorprints.deviceauth.LockState
import app.doorprints.deviceauth.RecordingActions
import app.doorprints.drive.delete.AuthorizationToken
import app.doorprints.drive.delete.DeletionLevel
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
    private val auth = FakeDeviceAuth()
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
        assertFalse(authorizer.isGenuine(t.copy(proof = "not-a-number")))
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
        val broken = object : app.doorprints.deviceauth.DeviceAuth {
            override fun isDeviceLockEnabled(): Boolean = error("boom")
            override suspend fun authenticate(reason: String, level: app.doorprints.deviceauth.DeleteLevel) = app.doorprints.deviceauth.AuthResult.SUCCESS
        }
        assertFalse(PhoneDeletionAuthorizer(DriveGate(AuthPlatform.PHONE, broken, lock, RecordingActions()) { now }, broken) { now }.isDeviceLockEnabled())
    }
}
