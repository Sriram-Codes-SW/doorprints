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

package app.doorprints.deviceauth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A scripted authenticator: answers [next] and counts the asks; [boom] throws. */
class FakeDeviceAuth(var lockEnabled: Boolean = true, var next: AuthResult = AuthResult.SUCCESS, var boom: Throwable? = null) : DeviceAuth {
    var asks = 0
    var lastLevel: DeleteLevel? = null
    override fun isDeviceLockEnabled() = lockEnabled
    override suspend fun authenticate(reason: String, level: DeleteLevel): AuthResult {
        asks++
        lastLevel = level
        boom?.let { throw it }
        return next
    }
}

class FakeLock(var state: LockState = LockState.PRESENT, var boom: Boolean = false) : LockLostDetector {
    override fun lockState(): LockState = if (boom) error("keystore unreachable") else state
}

class RecordingActions : LockLossActions {
    var dropped = 0
    var reenrol = 0
    override fun dropLocalKeys() { dropped++ }
    override fun requireReenrolment() { reenrol++ }
}

/** The flows around [DriveGate] with fakes (S4b-BL-127): a passed, denied, cancelled, timed out and removed-lock life. */
class DriveGateTest {
    private var now = 1_000L
    private val auth = FakeDeviceAuth()
    private val lock = FakeLock()
    private val actions = RecordingActions()
    private fun gate(platform: AuthPlatform = AuthPlatform.PHONE) = DriveGate(platform, auth, lock, actions) { now }
    private fun ctx(online: Boolean = true, left: Int? = 5, lockOn: Boolean = true, prf: Boolean = false, platform: AuthPlatform = AuthPlatform.PHONE) =
        DeletionContext(platform, lockOn, prf, online, left)

    @Test
    fun connectNeedsTheLock() {
        assertEquals(ConnectDecision.Allowed, gate().canConnect())
        auth.lockEnabled = false
        assertEquals(ConnectDecision.NeedsScreenLock, gate().canConnect())
        assertEquals(ConnectDecision.Allowed, gate(AuthPlatform.WEBSITE).canConnect())
    }

    @Test
    fun l1AsksNoFactor() = runTest {
        val a = gate().authorize(DeletionAction.DELETE_ONE_BACKUP, ctx(), "r")
        assertIs<Authorization.Granted>(a)
        assertEquals(0, auth.asks)
    }

    @Test
    fun l2PassedAsksOnceWithTheLevelAndIsRedeemedOnce() = runTest {
        val g = gate()
        val a = g.authorize(DeletionAction.DELETE_ALL_BACKUPS, ctx(), "Confirm it's you to delete")
        assertIs<Authorization.Granted>(a)
        assertEquals(1, auth.asks)
        assertEquals(DeleteLevel.L2, auth.lastLevel)
        now += 59_000
        assertEquals(Redeemed.OK, g.redeem(a.grant, DeletionAction.DELETE_ALL_BACKUPS))
        assertEquals(Redeemed.ALREADY_USED, g.redeem(a.grant, DeletionAction.DELETE_ALL_BACKUPS))
    }

    @Test
    fun l3AsksAtLevelThree() = runTest {
        assertIs<Authorization.Granted>(gate().authorize(DeletionAction.DELETE_EVERYTHING, ctx(), "r"))
        assertEquals(DeleteLevel.L3, auth.lastLevel)
    }

    @Test
    fun everyOtherResultDeniesAndNothingIsGranted() = runTest {
        for (r in AuthResult.entries.filter { it != AuthResult.SUCCESS }) {
            auth.next = r
            assertEquals(Authorization.Denied(r), gate().authorize(DeletionAction.DELETE_ALL_BACKUPS, ctx(), "r"), r.name)
        }
    }

    @Test
    fun anExceptionFromThePlatformIsFailedNotAPass() = runTest {
        auth.boom = IllegalStateException("prompt crashed")
        assertEquals(Authorization.Denied(AuthResult.FAILED), gate().authorize(DeletionAction.DELETE_ALL_BACKUPS, ctx(), "r"))
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        auth.boom = kotlinx.coroutines.CancellationException("scope gone")
        assertFailsWith<kotlinx.coroutines.CancellationException> { gate().authorize(DeletionAction.DELETE_ALL_BACKUPS, ctx(), "r") }
    }

    @Test
    fun policyRefusalsAskNothing() = runTest {
        assertEquals(Authorization.Refused(RefusalReason.OFFLINE), gate().authorize(DeletionAction.DELETE_ALL_BACKUPS, ctx(online = false), "r"))
        assertEquals(Authorization.Refused(RefusalReason.NO_DEVICE_LOCK), gate().authorize(DeletionAction.DELETE_ALL_BACKUPS, ctx(lockOn = false), "r"))
        assertEquals(0, auth.asks)
    }

    @Test
    fun aWebsiteL2WithoutAFactorInThisGateIsNotAvailableNeverAPass() = runTest {
        val g = gate(AuthPlatform.WEBSITE)
        assertEquals(Authorization.Refused(RefusalReason.USE_PHONE), g.authorize(DeletionAction.DELETE_ALL_BACKUPS, ctx(platform = AuthPlatform.WEBSITE), "r"))
        assertEquals(
            Authorization.Denied(AuthResult.NOT_AVAILABLE),
            g.authorize(DeletionAction.DELETE_ALL_BACKUPS, ctx(platform = AuthPlatform.WEBSITE, prf = true), "r"),
        )
        assertEquals(0, auth.asks)
    }

    @Test
    fun aGrantExpiresAfterAMinuteAndIsForOneAction() = runTest {
        val g = gate()
        val a = g.authorize(DeletionAction.DELETE_ALL_BACKUPS, ctx(), "r") as Authorization.Granted
        assertEquals(Redeemed.WRONG_ACTION, g.redeem(a.grant, DeletionAction.DELETE_EVERYTHING))
        now += 60_001
        assertEquals(Redeemed.EXPIRED, g.redeem(a.grant, DeletionAction.DELETE_ALL_BACKUPS))
    }

    @Test
    fun aClockThatWentBackIsRefused() = runTest {
        val g = gate()
        val a = g.authorize(DeletionAction.DELETE_ALL_BACKUPS, ctx(), "r") as Authorization.Granted
        now -= 1
        assertEquals(Redeemed.NOT_YET, g.redeem(a.grant, DeletionAction.DELETE_ALL_BACKUPS))
    }

    @Test
    fun theLockRemovedHalfWayStopsTheRedeemAndDropsOnlyLocalKeys() = runTest {
        val g = gate()
        val a = g.authorize(DeletionAction.DELETE_ALL_BACKUPS, ctx(), "r") as Authorization.Granted
        lock.state = LockState.REMOVED
        assertEquals(Redeemed.PAUSED, g.redeem(a.grant, DeletionAction.DELETE_ALL_BACKUPS))
        assertEquals(1, actions.dropped)
        assertEquals(1, actions.reenrol)
    }

    @Test
    fun theLockRemovedWhileAskingPauses() = runTest {
        lock.state = LockState.REMOVED
        assertEquals(Authorization.Paused(RunDecision.PausedNoLock), gate().authorize(DeletionAction.DELETE_ALL_BACKUPS, ctx(), "r"))
        assertEquals(0, auth.asks)
    }

    @Test
    fun beforeRunDropsKeysOnlyOnAPositiveRemoval() {
        val g = gate()
        assertEquals(RunDecision.Run, g.beforeRun())
        assertEquals(0, actions.dropped)
        lock.state = LockState.UNKNOWN
        assertEquals(RunDecision.PausedUnknown, g.beforeRun())
        lock.boom = true
        assertEquals(RunDecision.PausedUnknown, g.beforeRun())
        assertEquals(0, actions.dropped)
        assertEquals(0, actions.reenrol)
        lock.boom = false
        lock.state = LockState.REMOVED
        assertEquals(RunDecision.PausedNoLock, g.beforeRun())
        assertEquals(1, actions.dropped)
        assertEquals(1, actions.reenrol)
    }

    @Test
    fun theWebsiteIgnoresTheDetector() {
        lock.state = LockState.REMOVED
        assertEquals(RunDecision.Run, gate(AuthPlatform.WEBSITE).beforeRun())
        assertEquals(0, actions.dropped)
    }

    @Test
    fun disconnectingWorksWithNoLockAndALostLock() = runTest {
        lock.state = LockState.REMOVED
        val a = gate().authorize(DeletionAction.DISCONNECT_THIS_DEVICE, ctx(lockOn = false, online = false), "r")
        assertIs<Authorization.Granted>(a)
        assertTrue(gate().redeem(a.grant, DeletionAction.DISCONNECT_THIS_DEVICE) == Redeemed.OK)
    }
}
