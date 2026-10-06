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

package app.doorprints.drive.wiring

import app.doorprints.deviceauth.RunDecision
import app.doorprints.drive.connect.ConnectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions of the Drive wiring (S4b-BL-117/-118/-127): in use or not, which sync target, whether a background run goes. */
class DriveDecisionsTest {

    // ---- Drive in use ----

    private fun after(vararg states: ConnectState, start: Engagement = Engagement()): Engagement =
        states.fold(start) { m, s -> DriveEngagement.next(m, s) }

    @Test
    fun anOpenFolderEngagesDrive() {
        assertTrue(after(ConnectState.CONNECTING, ConnectState.READY).engaged)
        assertTrue(after(ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY).engaged)
    }

    @Test
    fun aFreshProcessStartingDisconnectedKeepsWhatWasRemembered() {
        // The controller starts at DISCONNECTED before it has reconnected: that must not forget that Drive was on.
        val remembered = Engagement(engaged = true)
        assertTrue(after(ConnectState.DISCONNECTED, start = remembered).engaged)
        assertTrue(after(ConnectState.DISCONNECTED, ConnectState.CONNECTING, ConnectState.DISCONNECTED, start = remembered).engaged)
    }

    @Test
    fun aFolderGoneIsNeverADisconnect() {
        val open = after(ConnectState.READY)
        val gone = DriveEngagement.next(open, ConnectState.DISCONNECTED, folderGone = true)
        assertTrue(gone.engaged)
        // The person's answer (the flag cleared, the state still disconnected) is the Disconnect.
        assertFalse(DriveEngagement.next(gone, ConnectState.DISCONNECTED, folderGone = false).engaged)
    }

    @Test
    fun aFreshProcessThatFindsTheFolderGoneStillHonoursTheDisconnectAfterIt() {
        val fresh = Engagement(engaged = true)
        val gone = DriveEngagement.next(fresh, ConnectState.DISCONNECTED, folderGone = true)
        assertTrue(gone.engaged)
        assertFalse(DriveEngagement.next(gone, ConnectState.DISCONNECTED, folderGone = false).engaged)
    }

    @Test
    fun disconnectingAfterTheFolderWasOpenDisengagesDrive() {
        val m = after(ConnectState.READY, ConnectState.DISCONNECTED)
        assertFalse(m.engaged)
        assertFalse(m.seenConnected)
        // And a later closed connect attempt stays disengaged.
        assertFalse(after(ConnectState.CONNECTING, ConnectState.DISCONNECTED, start = m).engaged)
    }

    @Test
    fun unavailableAlsoDisengagesAfterTheFolderWasOpen() {
        assertFalse(after(ConnectState.READY, ConnectState.UNAVAILABLE).engaged)
    }

    @Test
    fun anErrorOrAJoinFormChangesNothing() {
        val on = after(ConnectState.READY)
        for (s in listOf(ConnectState.ERROR, ConnectState.CONNECTING, ConnectState.NEEDS_ENROLMENT, ConnectState.NEEDS_RECOVERY_KEY)) {
            assertEquals(on, DriveEngagement.next(on, s))
        }
        val off = Engagement()
        for (s in listOf(ConnectState.ERROR, ConnectState.NEEDS_ENROLMENT)) assertEquals(off, DriveEngagement.next(off, s))
    }

    // ---- which sync backend ----

    @Test
    fun withDriveInUseTheDriveBackendIsUsedAndNeverTheServer() {
        val drive = Any()
        val server = Any()
        assertSame(drive, DriveSyncChoice.choose(true, drive) { server })
    }

    @Test
    fun withDriveInUseButNotReconnectedNothingSyncsAndTheServerIsNotUsed() {
        var askedServer = false
        assertNull(DriveSyncChoice.choose<Any>(true, null) { askedServer = true; Any() })
        assertFalse(askedServer)
    }

    @Test
    fun withoutDriveTheServerIsChosenAsBefore() {
        val server = Any()
        assertSame(server, DriveSyncChoice.choose(false, Any()) { server })
        assertNull(DriveSyncChoice.choose<Any>(false, null) { null })
    }

    // ---- may a background run go ----

    private fun decide(engaged: Boolean = true, auto: Boolean = true, lock: RunDecision = RunDecision.Run) =
        DriveWorkRules.decide(engaged, auto) { lock }

    @Test
    fun itRunsWhenConnectedAutoOnAndTheLockIsThere() {
        assertEquals(WorkDecision.Run, decide())
    }

    @Test
    fun notConnectedSkips() {
        assertEquals(WorkDecision.Skip(SkipReason.NOT_CONNECTED), decide(engaged = false))
    }

    @Test
    fun autoBackupOffSkips() {
        assertEquals(WorkDecision.Skip(SkipReason.AUTO_OFF), decide(auto = false))
    }

    @Test
    fun aRemovedLockSkipsWithItsOwnReason() {
        assertEquals(WorkDecision.Skip(SkipReason.LOCK_REMOVED), decide(lock = RunDecision.PausedNoLock))
    }

    @Test
    fun anUnreadableLockSkipsToo() {
        assertEquals(WorkDecision.Skip(SkipReason.LOCK_UNKNOWN), decide(lock = RunDecision.PausedUnknown))
    }

    @Test
    fun theLockIsAskedOnlyWhenTheOtherConditionsHold() {
        // Asking can drop the device key when the lock is gone: a person who never used Drive, or has it off, is never touched.
        var asked = 0
        val ask = { asked++; RunDecision.PausedNoLock }
        DriveWorkRules.decide(engaged = false, autoBackupOn = true, lock = ask)
        DriveWorkRules.decide(engaged = true, autoBackupOn = false, lock = ask)
        assertEquals(0, asked)
        DriveWorkRules.decide(engaged = true, autoBackupOn = true, lock = ask)
        assertEquals(1, asked)
    }

    @Test
    fun onlyTheLockSkipsGetTheDocumentedNotice() {
        assertTrue(DriveWorkRules.isLockPause(SkipReason.LOCK_REMOVED))
        assertTrue(DriveWorkRules.isLockPause(SkipReason.LOCK_UNKNOWN))
        assertFalse(DriveWorkRules.isLockPause(SkipReason.NOT_CONNECTED))
        assertFalse(DriveWorkRules.isLockPause(SkipReason.AUTO_OFF))
    }

    // ---- Settings' lock notice and the schedule ----

    @Test
    fun theNoticeFollowsTheLockAndWhetherDriveIsInUse() {
        assertEquals(LockNotice.NONE, DriveLockRules.notice(engaged = true, lockPresent = true))
        assertEquals(LockNotice.NONE, DriveLockRules.notice(engaged = false, lockPresent = true))
        assertEquals(LockNotice.PAUSED, DriveLockRules.notice(engaged = true, lockPresent = false))
        assertEquals(LockNotice.NEEDS_LOCK, DriveLockRules.notice(engaged = false, lockPresent = false))
    }

    @Test
    fun aKeyTheKeyStoreLostWithTheLockThereHasItsOwnNotice() {
        assertEquals(LockNotice.KEY_LOST, DriveLockRules.notice(engaged = true, lockPresent = true, keyStoreFault = true))
        assertEquals("a removed lock keeps the lock's words", LockNotice.PAUSED, DriveLockRules.notice(engaged = true, lockPresent = false, keyStoreFault = true))
        assertEquals("Drive not in use: nothing to say", LockNotice.NONE, DriveLockRules.notice(engaged = false, lockPresent = true, keyStoreFault = true))
        assertTrue(DriveLockRules.showsCard(LockNotice.KEY_LOST))
        assertEquals(LockNotice.KEY_LOST, DriveLockRules.pausedNotice(true))
        assertEquals(LockNotice.PAUSED, DriveLockRules.pausedNotice(false))
        assertTrue(DriveWorkRules.isLockPause(SkipReason.KEY_LOST))
        assertFalse(DriveWorkRules.isLockPause(SkipReason.AUTO_OFF))
    }

    @Test
    fun aStandingPauseIsLeftAloneWhileTheLockIsStillGoneOrTheKeyStoreStillAtFault() {
        assertEquals(null, DriveLockRules.standingPause(paused = false, lockPresent = false, keyStoreFault = true))
        assertEquals(SkipReason.LOCK_REMOVED, DriveLockRules.standingPause(paused = true, lockPresent = false, keyStoreFault = false))
        assertEquals(SkipReason.KEY_LOST, DriveLockRules.standingPause(paused = true, lockPresent = true, keyStoreFault = true))
        assertEquals("the lock is back: look again", null, DriveLockRules.standingPause(paused = true, lockPresent = true, keyStoreFault = false))
    }

    @Test
    fun theCardIsHiddenOnlyWhenDriveCannotBeSwitchedOn() {
        assertTrue(DriveLockRules.showsCard(LockNotice.NONE))
        assertTrue(DriveLockRules.showsCard(LockNotice.PAUSED))
        assertFalse(DriveLockRules.showsCard(LockNotice.NEEDS_LOCK))
    }

    @Test
    fun theBackgroundWorkExistsOnlyWhileInUseAndAutoBackupIsOn() {
        assertTrue(DriveLockRules.shouldSchedule(engaged = true, autoBackupOn = true))
        assertFalse(DriveLockRules.shouldSchedule(engaged = true, autoBackupOn = false))
        assertFalse(DriveLockRules.shouldSchedule(engaged = false, autoBackupOn = true))
        assertFalse(DriveLockRules.shouldSchedule(engaged = false, autoBackupOn = false))
    }
}
