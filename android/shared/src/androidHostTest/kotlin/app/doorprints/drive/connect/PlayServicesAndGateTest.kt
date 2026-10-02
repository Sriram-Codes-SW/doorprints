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

import app.doorprints.crypto.JvmCryptoProvider
import app.doorprints.deviceauth.AuthPlatform
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeletionContext
import app.doorprints.deviceauth.DriveGate
import app.doorprints.deviceauth.FakeDeviceAuth
import app.doorprints.deviceauth.FakeLock
import app.doorprints.deviceauth.LockState
import app.doorprints.deviceauth.RecordingActions
import app.doorprints.deviceauth.RefusalReason
import app.doorprints.drive.DriveException
import app.doorprints.drive.delete.AuthorizationToken
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlayServicesAndGateTest {
    private class FakePlay(var next: PlayAuthResult) : PlayServicesAuthorizer {
        var calls = 0
        override suspend fun authorize(): PlayAuthResult {
            calls++
            return next
        }
    }

    private val driveScope = setOf(GoogleAuthConfig.SCOPE_DRIVE_FILE)

    @Test
    fun playServicesFallbackKeepsNothingAndAsksAgainWhenTheTokenIsOld() = runTest {
        var now = 0L
        val play = FakePlay(PlayAuthResult.Granted("t1", driveScope, 3_600_000))
        val s = PlayServicesGoogleSignIn(play, { now })
        assertEquals(SignInResult.Connected, s.connect())
        assertEquals("t1", s.accessToken()); assertEquals(1, play.calls)
        now = 3_600_000
        play.next = PlayAuthResult.Granted("t2", driveScope, 7_200_000)
        assertEquals("t2", s.accessToken())
        s.onRejected("t2"); play.next = PlayAuthResult.Granted("t3", driveScope, 9_000_000)
        assertEquals("t3", s.accessToken())
        s.disconnect(); assertFalse(s.isConnected())
    }

    @Test
    fun playServicesRefusalsAndWideScopesFailClosed() = runTest {
        val play = FakePlay(PlayAuthResult.Cancelled)
        val s = PlayServicesGoogleSignIn(play, { 0 })
        assertEquals(SignInResult.Cancelled, s.connect())
        play.next = PlayAuthResult.Unavailable
        assertEquals(SignInResult.Failed(SignInFailure.NOT_AVAILABLE), s.connect())
        play.next = PlayAuthResult.Granted("t", driveScope + "https://www.googleapis.com/auth/drive", 9)
        assertEquals(SignInResult.Failed(SignInFailure.WRONG_SCOPE), s.connect())
        assertFailsWith<DriveException> { s.accessToken() }
    }

    // ---- DriveGateAuthorizer ---------------------------------------------------------------------------------------

    private var now = 1_000L
    private val auth = FakeDeviceAuth()
    private val lock = FakeLock()
    private val actions = RecordingActions()
    private fun gate(platform: AuthPlatform = AuthPlatform.PHONE) = DriveGate(platform, auth, lock, actions) { now }
    private fun ctx(platform: AuthPlatform = AuthPlatform.PHONE, prf: Boolean = false) = DeletionContext(platform, true, prf, true, 5)
    private fun authorizer(g: DriveGate = gate()) = DriveGateAuthorizer(g, JvmCryptoProvider) { now }

    private suspend fun token(a: DriveGateAuthorizer, op: String = "del-1", level: DeletionLevel = DeletionLevel.L2, action: DeletionAction = DeletionAction.AllBackups): AuthorizationToken =
        (a.authorize(action, level, op, ctx(), "r") as DriveGateAuthorizer.Outcome.Token).token

    @Test
    fun aGrantedCheckBecomesATokenThatWorksOnceForItsOwnOperation() = runTest {
        val a = authorizer()
        val t = token(a)
        assertEquals(DeletionLevel.L2, t.level); assertEquals("del-1", t.operationId)
        assertTrue(a.isGenuine(t))
        assertFalse(a.isGenuine(t), "a second start with the same token is refused")
    }

    @Test
    fun aTokenMadeUpOrEditedIsNotGenuine() = runTest {
        val a = authorizer()
        val t = token(a)
        assertFalse(a.isGenuine(AuthorizationToken(DeletionLevel.L3, t.issuedAtMs, t.operationId, t.proof)))
        assertFalse(a.isGenuine(AuthorizationToken(t.level, t.issuedAtMs, "del-other", t.proof)))
        assertFalse(a.isGenuine(AuthorizationToken(t.level, t.issuedAtMs, t.operationId, "00".repeat(32))))
        assertFalse(a.isGenuine(AuthorizationToken(t.level, t.issuedAtMs, t.operationId, t.proof + "0")))
        // Another process's authorizer (a different key) cannot make one this one accepts.
        val other = authorizer()
        assertFalse(a.isGenuine(token(other)))
    }

    @Test
    fun aTokenExpiresWithThePolicysMinuteAndNeedsTheLockStill() = runTest {
        val a = authorizer()
        val old = token(a)
        now += 61_000
        assertFalse(a.isGenuine(old))
        val fresh = token(a, "del-2")
        lock.state = LockState.REMOVED
        assertFalse(a.isGenuine(fresh), "the lock is gone: not redeemed")
        assertEquals(1, actions.dropped)
    }

    @Test
    fun stillHoldsStopsTheRunWhenTheLockGoes() = runTest {
        val a = authorizer()
        val t = token(a)
        assertTrue(a.isGenuine(t))
        assertTrue(a.stillHolds(t))
        lock.state = LockState.REMOVED
        assertFalse(a.stillHolds(t))
        assertFalse(a.stillHolds(AuthorizationToken(t.level, t.issuedAtMs, t.operationId, "nope")))
    }

    @Test
    fun levelOneAsksNothingAndMakesNoToken() = runTest {
        val a = authorizer()
        assertEquals(DriveGateAuthorizer.Outcome.NoneNeeded, a.authorize(DeletionAction.OneBackup("f"), DeletionLevel.L1, "op", ctx(), "r"))
        assertEquals(0, auth.asks)
        // The last backup is level two: the phone is asked.
        assertIs<DriveGateAuthorizer.Outcome.Token>(a.authorize(DeletionAction.OneBackup("f"), DeletionLevel.L2, "op", ctx(), "r"))
        assertEquals(1, auth.asks)
    }

    @Test
    fun deniedRefusedAndPausedAreReportedAsTheyAre() = runTest {
        val a = authorizer()
        auth.next = AuthResult.CANCELLED
        assertEquals(DriveGateAuthorizer.Outcome.Denied(AuthResult.CANCELLED), a.authorize(DeletionAction.AllBackups, DeletionLevel.L2, "op", ctx(), "r"))
        auth.next = AuthResult.SUCCESS
        assertEquals(DriveGateAuthorizer.Outcome.Refused(RefusalReason.USE_PHONE), authorizer(gate(AuthPlatform.WEBSITE)).authorize(DeletionAction.AllBackups, DeletionLevel.L2, "op", ctx(AuthPlatform.WEBSITE), "r"))
        lock.state = LockState.REMOVED
        assertIs<DriveGateAuthorizer.Outcome.Paused>(a.authorize(DeletionAction.AllBackups, DeletionLevel.L2, "op", ctx(), "r"))
    }

    @Test
    fun aPolicyLevelLowerThanThePlansIsDeniedNeverPassed() = runTest {
        // A plan that says everything (L3) can never ride on an L2 grant.
        val a = authorizer()
        val o = a.authorize(DeletionAction.AllBackups, DeletionLevel.L3, "op", ctx(), "r")
        assertEquals(DriveGateAuthorizer.Outcome.Denied(AuthResult.FAILED), o)
    }
}
