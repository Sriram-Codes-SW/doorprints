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
import app.doorprints.deviceauth.LockState
import app.doorprints.drive.DriveFault
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.DriveOp
import app.doorprints.drive.FakeDriveServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every transition of the Settings > Google Drive state holder, over the fake Drive (S4b-BL-117). */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveSettingsControllerTest {

    private fun TestScope.ctl(rig: ConnectRig) = rig.controller(this)
    private suspend fun TestScope.settle() {
        testScheduler.advanceUntilIdle()
        testScheduler.runCurrent()
    }

    /** Connects a fresh Drive through the whole first-connect flow with the recovery key saved; returns the key text. */
    private suspend fun TestScope.firstConnect(rig: ConnectRig, c: DriveSettingsController): String {
        c.connect(); settle()
        assertEquals(DriveStage.FIRST_CONNECT, c.state.value.stage)
        c.createFirstFolder(); settle()
        assertEquals(DriveStage.RECOVERY_KEY, c.state.value.stage)
        val step = c.state.value.recoveryKey!!
        val groups = step.display.split('-')
        c.confirmRecoveryKey(step.askGroups.map { groups[it - 1] }); settle()
        return step.display
    }

    @Test
    fun withoutAClientIdTheSectionIsHiddenAndNothingHappens() = runTest {
        val rig = ConnectRig().also { it.signIn.configured = false }
        val c = ctl(rig)
        assertEquals(DriveStage.HIDDEN, c.state.value.stage)
        c.connect(); c.start(); settle()
        assertEquals(DriveStage.HIDDEN, c.state.value.stage)
        assertEquals(0, rig.signIn.connects)
        assertTrue(rig.server.allFiles().isEmpty())
    }

    @Test
    fun aPhoneWithoutAScreenLockCannotConnectAndIsToldHowToFixIt() = runTest {
        val rig = ConnectRig().also { it.auth.lockEnabled = false }
        val c = ctl(rig)
        c.connect(); settle()
        assertEquals(DriveStage.NEEDS_SCREEN_LOCK, c.state.value.stage)
        assertEquals(0, rig.signIn.connects)
        rig.auth.lockEnabled = true
        c.lockMayHaveChanged()
        assertEquals(DriveStage.DISCONNECTED, c.state.value.stage)
    }

    @Test
    fun theWebsiteNeedsNoScreenLockToConnect() = runTest {
        val rig = ConnectRig(platform = AuthPlatform.WEBSITE).also { it.auth.lockEnabled = false }
        val c = ctl(rig)
        c.connect(); settle()
        assertEquals(DriveStage.FIRST_CONNECT, c.state.value.stage)
    }

    @Test
    fun signInCancelledDeniedOrOfflineReturnsToDisconnectedWithAWordWhereNeeded() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        rig.signIn.result = SignInResult.Cancelled
        c.connect(); settle()
        assertEquals(DriveStage.DISCONNECTED, c.state.value.stage); assertNull(c.state.value.message)
        for ((failure, message) in listOf(
            SignInFailure.OFFLINE to DriveMessage.SIGNIN_OFFLINE, SignInFailure.DENIED to DriveMessage.SIGNIN_FAILED,
            SignInFailure.WRONG_SCOPE to DriveMessage.SIGNIN_WRONG_SCOPE, SignInFailure.STATE_MISMATCH to DriveMessage.SIGNIN_STATE,
            SignInFailure.STORE_UNAVAILABLE to DriveMessage.SIGNIN_STORE, SignInFailure.NOT_AVAILABLE to DriveMessage.SIGNIN_NOT_AVAILABLE,
        )) {
            rig.signIn.result = SignInResult.Failed(failure)
            c.connect(); settle()
            assertEquals(DriveStage.DISCONNECTED, c.state.value.stage, failure.name)
            assertEquals(message, c.state.value.message, failure.name)
        }
        assertTrue(rig.server.allFiles().isEmpty(), "a failed sign-in writes nothing")
    }

    @Test
    fun firstConnectShowsTheKeyOnceAsksTwoGroupsBackAndWritesTheReadMe() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        c.connect(); settle()
        assertEquals(DriveStage.FIRST_CONNECT, c.state.value.stage)
        c.createFirstFolder(); settle()
        val s = c.state.value
        assertEquals(DriveStage.RECOVERY_KEY, s.stage)
        val step = assertNotNull(s.recoveryKey)
        assertEquals(2, step.askGroups.size); assertTrue(step.askGroups[0] < step.askGroups[1])
        assertEquals(RecoveryStatus.UNCONFIRMED, rig.prefs.recoveryStatus)
        // Wrong groups: still there, nothing else changes.
        c.confirmRecoveryKey(listOf("AAAA", "BBBB")); settle()
        assertEquals(DriveStage.RECOVERY_KEY, c.state.value.stage)
        assertEquals(DriveMessage.RECOVERY_GROUPS_WRONG, c.state.value.message)
        assertEquals(1, c.state.value.recoveryKey!!.wrongTries)
        // Right groups, typed in lower case with spaces.
        val groups = step.display.split('-')
        c.confirmRecoveryKey(step.askGroups.map { " " + groups[it - 1].lowercase() + " " }); settle()
        assertEquals(DriveStage.READY, c.state.value.stage)
        assertNull(c.state.value.recoveryKey, "the key is gone from memory once saved")
        assertEquals(RecoveryStatus.SAVED, rig.prefs.recoveryStatus)
        assertEquals("person@example.com", c.state.value.account)
        // Read me.txt in the root, kind=readme, four languages.
        val root = rig.server.allFiles().first { it.appProperties[DriveLayout.ROLE] == "root" }
        val readme = rig.server.allFiles().single { it.appProperties[DriveLayout.KIND] == DriveReadme.KIND }
        assertEquals(DriveReadme.NAME, readme.name); assertEquals(listOf(root.id), readme.parents)
        assertFalse(c.state.value.toString().contains(step.display), "the key is never in a printed state")
    }

    @Test
    fun skippingTheRecoveryKeyNeedsTheWarningTickAndLeavesNoKey() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        c.connect(); settle()
        c.createFirstFolder(skipRecoveryKey = true, skipAcknowledged = false); settle()
        assertEquals(DriveStage.FIRST_CONNECT, c.state.value.stage, "not acknowledged: nothing happens")
        assertTrue(rig.server.allFiles().isEmpty())
        c.createFirstFolder(skipRecoveryKey = true, skipAcknowledged = true); settle()
        assertEquals(DriveStage.READY, c.state.value.stage)
        assertEquals(RecoveryStatus.SKIPPED, c.state.value.recoveryStatus)
        assertNull(c.state.value.recoveryKey)
    }

    @Test
    fun aSecondDeviceIsToldTheDriveHasBackupsAndWritesNothingUntilItJoinsWithTheRecoveryKey() = runTest {
        val server = FakeDriveServer()
        val first = ConnectRig(server, "Pixel 8")
        val key = firstConnect(first, ctl(first))
        val filesBefore = server.allFiles().map { it.id to it.modifiedTime }

        val second = ConnectRig(server, "iPhone")
        val c = ctl(second)
        c.connect(); settle()
        assertEquals(DriveStage.NEEDS_ENROLMENT, c.state.value.stage)
        assertTrue(c.state.value.recoveryAvailable)
        assertEquals(filesBefore, server.allFiles().map { it.id to it.modifiedTime }, "nothing written before joining")

        c.enterRecoveryKey("not a key"); settle()
        assertEquals(DriveStage.NEEDS_ENROLMENT, c.state.value.stage)
        assertEquals(DriveMessage.WRONG_RECOVERY_KEY, c.state.value.message)
        c.enterRecoveryKey("AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-GGG"); settle()
        assertEquals(DriveStage.NEEDS_ENROLMENT, c.state.value.stage)

        c.enterRecoveryKey(key.lowercase()); settle()
        assertEquals(DriveStage.READY, c.state.value.stage, c.state.value.message?.name)
        assertEquals(RecoveryStatus.SAVED, c.state.value.recoveryStatus)
    }

    @Test
    fun aDeviceWhoseKeyNoLongerOpensTheListAsksForTheRecoveryKeyAndNeverRevokesOrRekeys() = runTest {
        val server = FakeDriveServer()
        val first = ConnectRig(server, "Pixel 8")
        val key = firstConnect(first, ctl(first))
        // Same trust and state on the device, but its key is not in the list (e.g. app data was restored without the key).
        first.identity = app.doorprints.drive.backup.TestIdentity(first.p, "Pixel 8 again")
        val c = DriveSettingsController(first.deps(serviceOverride = first.service()), this)
        val before = server.allFiles().map { it.id to it.modifiedTime to it.appProperties }
        c.connect(); settle()
        assertEquals(DriveStage.NEEDS_RECOVERY_KEY, c.state.value.stage)
        assertEquals(before, server.allFiles().map { it.id to it.modifiedTime to it.appProperties }, "no write of any kind")
        c.enterRecoveryKey(key); settle()
        assertEquals(DriveStage.READY, c.state.value.stage)
    }

    @Test
    fun aGoneFolderIsNeverRecreatedWithoutTheTick() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        firstConnect(rig, c)
        val root = rig.server.allFiles().first { it.appProperties[DriveLayout.ROLE] == "root" }
        rig.server.trashByHand(root.id)
        c.start(); c.refresh(); settle()
        // A fresh controller on the same device reads the folder again.
        val c2 = ctl(rig).also { rig.signIn.connected = true }
        c2.start(); settle()
        assertEquals(DriveStage.FOLDER_GONE, c2.state.value.stage)
        val count = rig.server.allFiles().size
        c2.startAgain(false); settle()
        assertEquals(DriveStage.FOLDER_GONE, c2.state.value.stage); assertEquals(count, rig.server.allFiles().size)
        c2.startAgain(true); settle()
        assertEquals(DriveStage.RECOVERY_KEY, c2.state.value.stage)
    }

    @Test
    fun backUpNowListsTheBackupAndSetsTheLastBackup() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        firstConnect(rig, c)
        assertTrue(c.state.value.ready!!.backups.isEmpty())
        c.backUpNow(); settle()
        val s = c.state.value
        assertEquals(DriveMessage.BACKUP_DONE, s.message)
        assertEquals(1, s.ready!!.backups.size)
        assertTrue(s.ready!!.backups.single().isNewest)
        assertEquals(5, s.ready!!.backups.single().houses)
        assertNotNull(s.ready!!.lastBackupAt)
        assertNull(s.busy)
    }

    @Test
    fun aFailedBackupSaysWhyInPlainWordsAndKeepsTheScreen() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        firstConnect(rig, c)
        rig.server.faults.always(DriveFault.Offline, DriveOp.UPLOAD)
        c.backUpNow(); settle()
        assertEquals(DriveStage.READY, c.state.value.stage)
        assertEquals(DriveMessage.OFFLINE, c.state.value.message)
        rig.server.faults.clear()
        rig.server.faults.always(DriveFault.QuotaExceeded, DriveOp.UPLOAD)
        c.backUpNow(); settle()
        assertEquals(DriveMessage.QUOTA, c.state.value.message)
        rig.server.faults.clear()
    }

    @Test
    fun googleRefusingTheGrantReturnsToDisconnectedWithTheDisconnectedWordsAndKeepsTheHouses() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        firstConnect(rig, c)
        println("DBG before " + c.state.value.stage + " " + c.state.value.message)
        rig.server.faults.always(DriveFault.TokenExpired)
        c.backUpNow(); settle()
        println("DBG after " + c.state.value.stage + " " + c.state.value.message)
        assertEquals(DriveStage.DISCONNECTED, c.state.value.stage)
        assertEquals(DriveMessage.DISCONNECTED_BY_GOOGLE, c.state.value.message)
        assertNull(c.state.value.ready)
    }

    @Test
    fun theSwitchesAreRememberedAndAutomaticBackupIsOffByDefault() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        firstConnect(rig, c)
        assertFalse(c.state.value.autoBackup); assertTrue(c.state.value.photosWifiOnly)
        assertFalse(c.state.value.mobileDataOneOffAvailable, "S4b-BL-128 wires the one-off button")
        c.setAutoBackup(true); c.setPhotosWifiOnly(false)
        assertTrue(rig.prefs.autoBackup); assertFalse(rig.prefs.photosWifiOnly)
        assertTrue(c.state.value.autoBackup); assertFalse(c.state.value.photosWifiOnly)
    }

    @Test
    fun importFromDriveHandsAVerifiedZipToTheExistingImportPreview() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        firstConnect(rig, c)
        c.backUpNow(); settle()
        val id = c.state.value.ready!!.backups.single().id
        val events = mutableListOf<DriveEvent>()
        backgroundScope.launchCollect(c.events, events)
        c.importBackup(id); settle()
        assertEquals(1, rig.handoff.opened.size)
        assertEquals(DriveMessage.IMPORT_READY, c.state.value.message)
        assertTrue(rig.handoff.stagings.single().bytes().isNotEmpty())
        assertEquals(listOf<DriveEvent>(DriveEvent.OpenImportPreview), events)
    }

    @Test
    fun importOfABackupThatIsGoneIsRefusedAndTheStagingIsDiscarded() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        firstConnect(rig, c)
        c.backUpNow(); settle()
        val id = c.state.value.ready!!.backups.single().id
        rig.server.deleteByHand(id)
        c.importBackup(id); settle()
        assertEquals(0, rig.handoff.opened.size)
        assertEquals(DriveMessage.BACKUP_GONE, c.state.value.message)
        assertTrue(rig.handoff.stagings.single().discards >= 1)
    }

    @Test
    fun disconnectThisDeviceForgetsTheGrantTurnsAutoBackupOffAndKeepsDriveUntouched() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        firstConnect(rig, c)
        c.setAutoBackup(true)
        val files = rig.server.allFiles().size
        c.disconnectThisDevice(); settle()
        assertEquals(DriveStage.DISCONNECTED, c.state.value.stage)
        assertEquals(DriveMessage.DISCONNECTED_DONE, c.state.value.message)
        assertEquals(1, rig.signIn.disconnects)
        assertFalse(rig.prefs.autoBackup)
        assertEquals(files, rig.server.allFiles().size, "disconnecting deletes nothing in Drive")
    }

    @Test
    fun aRemovedScreenLockPausesDropsLocalKeysAndDoesNothingInDrive() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        firstConnect(rig, c)
        val requests = rig.server.requests.size
        rig.lock.state = LockState.REMOVED
        c.backUpNow(); settle()
        assertEquals(DriveStage.PAUSED, c.state.value.stage)
        assertEquals(DriveMessage.PAUSED_NO_LOCK, c.state.value.message)
        assertEquals(1, rig.actions.dropped); assertEquals(1, rig.actions.reenrol)
        assertEquals(requests, rig.server.requests.size, "no request after the lock is gone")
        // An unreadable lock pauses too, without dropping anything more.
        rig.lock.state = LockState.PRESENT; rig.lock.boom = true
        c.retry(); settle()
        assertEquals(DriveMessage.PAUSED_UNKNOWN, c.state.value.message)
        assertEquals(1, rig.actions.dropped)
        // The lock is back: the next read goes on.
        rig.lock.boom = false
        c.lockMayHaveChanged(); settle()
        assertTrue(c.state.value.stage != DriveStage.PAUSED)
    }

    // ---- Deleting -------------------------------------------------------------------------------------------------

    private suspend fun TestScope.withBackups(rig: ConnectRig, c: DriveSettingsController, n: Int) {
        firstConnect(rig, c)
        repeat(n) {
            rig.server.clock.advance(86_400_000L * 2)
            c.backUpNow(); settle()
        }
        assertEquals(n, c.state.value.ready!!.backups.size)
    }

    @Test
    fun deletingOneOfSeveralBackupsAsksNoTickNoDelayNoPhoneCheck() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        withBackups(rig, c, 3)
        val oldest = c.state.value.ready!!.backups.last().id
        c.openDelete(DriveDeleteChoice.OneBackup(oldest)); settle()
        val d = assertNotNull(c.state.value.dialog)
        assertFalse(d.needsTick); assertEquals(0, d.delaySeconds); assertEquals(1, d.totalFiles)
        c.confirmDelete(); settle()
        assertNull(c.state.value.dialog)
        assertEquals(2, c.state.value.ready!!.backups.size)
        assertEquals(0, rig.auth.asks, "L1 asks nothing more")
        assertEquals(DriveMessage.DELETE_DONE_BACKUP, c.state.value.message)
    }

    @Test
    fun deletingTheLastBackupIsLevelTwoAndAsksThePhoneButNoTick() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        withBackups(rig, c, 1)
        c.openDelete(DriveDeleteChoice.OneBackup(c.state.value.ready!!.backups.single().id)); settle()
        val d = assertNotNull(c.state.value.dialog)
        assertEquals(app.doorprints.drive.delete.DeletionLevel.L2, d.level)
        assertFalse(d.needsTick)
        c.confirmDelete(); settle()
        assertEquals(1, rig.auth.asks)
        assertTrue(c.state.value.ready!!.backups.isEmpty())
    }

    @Test
    fun deleteAllNeedsTheTickThenThePhoneCheckAndTurnsAutoBackupOff() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        withBackups(rig, c, 2)
        c.setAutoBackup(true)
        c.openDelete(DriveDeleteChoice.AllBackups); settle()
        assertTrue(c.state.value.dialog!!.needsTick)
        c.confirmDelete(); settle()
        assertNotNull(c.state.value.dialog, "not ticked: nothing happens")
        assertEquals(0, rig.auth.asks)
        c.setDeleteTick(true)
        c.confirmDelete(); settle()
        assertEquals(1, rig.auth.asks)
        assertEquals(listOf<DriveDeleteChoice>(DriveDeleteChoice.AllBackups), rig.authReasons)
        assertTrue(c.state.value.ready!!.backups.isEmpty())
        assertEquals(DriveMessage.DELETE_DONE_ALL, c.state.value.message)
        assertFalse(rig.prefs.autoBackup); assertFalse(c.state.value.autoBackup)
        assertEquals(DriveStage.READY, c.state.value.stage, "sync-less v1: connected, ask before backing up again")
    }

    @Test
    fun aDeniedOrCancelledPhoneCheckDeletesNothing() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        withBackups(rig, c, 2)
        val before = rig.server.allFiles().count { !it.trashed }
        for (r in listOf(AuthResult.CANCELLED, AuthResult.FAILED, AuthResult.LOCKED_OUT)) {
            rig.auth.next = r
            c.openDelete(DriveDeleteChoice.AllBackups); settle()
            c.setDeleteTick(true); c.confirmDelete(); settle()
            assertNull(c.state.value.dialog, r.name)
            assertEquals(DriveMessage.DELETE_DENIED, c.state.value.message, r.name)
            assertEquals(before, rig.server.allFiles().count { !it.trashed }, r.name)
        }
    }

    @Test
    fun deleteEverythingWaitsFiveSecondsThenGoesBackToTheFullFirstConnect() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        withBackups(rig, c, 2)
        c.openDelete(DriveDeleteChoice.Everything); settle()
        val d = c.state.value.dialog!!
        assertEquals(5, d.delaySeconds); assertTrue(d.needsTick)
        c.setDeleteTick(true)
        assertEquals(5, c.state.value.dialog!!.secondsLeft(rig.server.clock.now()))
        c.confirmDelete(); settle()
        assertEquals(0, rig.auth.asks, "before 5 seconds nothing happens")
        rig.server.clock.advance(5_000)
        assertEquals(0, c.state.value.dialog!!.secondsLeft(rig.server.clock.now()))
        c.confirmDelete(); settle()
        assertEquals(DriveStage.FIRST_CONNECT, c.state.value.stage)
        assertEquals(DriveMessage.DELETE_DONE_EVERYTHING, c.state.value.message)
        assertEquals(RecoveryStatus.UNKNOWN, c.state.value.recoveryStatus)
        assertTrue(rig.server.allFiles().none { !it.trashed && it.appProperties.isNotEmpty() }, "nothing of ours is left")
        assertNull(rig.stateStore.load().rootId)
        assertFalse(rig.prefs.autoBackup)
    }

    @Test
    fun deletingIsRefusedOfflineWithoutALockAndOnTheWebsiteWithoutAPasskey() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        withBackups(rig, c, 2)
        rig.online = false
        c.openDelete(DriveDeleteChoice.AllBackups); settle()
        assertEquals(DriveMessage.DELETE_OFFLINE, c.state.value.message); assertNull(c.state.value.dialog)
        rig.online = true; rig.auth.lockEnabled = false
        c.openDelete(DriveDeleteChoice.AllBackups); settle()
        assertEquals(DriveMessage.DELETE_NO_LOCK, c.state.value.message); assertNull(c.state.value.dialog)

        val web = ConnectRig(platform = AuthPlatform.WEBSITE)
        val w = ctl(web)
        withBackups(web, w, 2)
        w.openDelete(DriveDeleteChoice.AllBackups); settle()
        assertEquals(DriveMessage.DELETE_USE_PHONE, w.state.value.message)
        assertNull(w.state.value.dialog)
    }

    @Test
    fun theLockRemovedWhileAskingPausesAndDeletesNothing() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        withBackups(rig, c, 2)
        val before = rig.server.allFiles().count { !it.trashed }
        // The phone's check passes, but the lock is gone by the time the deletion starts.
        rig.auth.next = AuthResult.SUCCESS
        c.openDelete(DriveDeleteChoice.AllBackups); settle()
        c.setDeleteTick(true)
        rig.lock.state = LockState.REMOVED
        c.confirmDelete(); settle()
        assertEquals(before, rig.server.allFiles().count { !it.trashed })
        assertEquals(DriveStage.PAUSED, c.state.value.stage)
    }

    @Test
    fun aDeletionThatStopsHalfWayCanBeFinishedWithTryAgainAfterAFreshCheck() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        withBackups(rig, c, 3)
        c.openDelete(DriveDeleteChoice.AllBackups); settle()
        c.setDeleteTick(true)
        rig.server.faults.stopAfter(rig.server.requests.size + 2)
        c.confirmDelete(); settle()
        assertEquals(DriveMessage.DELETE_STOPPED, c.state.value.message)
        assertNull(c.state.value.dialog)
        rig.server.faults.clear()
        c.refresh(); settle()
        val left = c.state.value.ready!!.pendingDeletionLeft
        assertNotNull(left)
        val asksBefore = rig.auth.asks
        c.resumeDeletion(); settle()
        assertEquals(asksBefore + 1, rig.auth.asks, "a resume needs a fresh check")
        assertTrue(c.state.value.ready!!.backups.isEmpty())
        assertNull(c.state.value.ready!!.pendingDeletionLeft)
    }

    @Test
    fun saveACopyFirstOnlyAsksTheHostToOpenSaveACopy() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        withBackups(rig, c, 2)
        val events = mutableListOf<DriveEvent>()
        backgroundScope.launchCollect(c.events, events)
        c.saveCopyFirst()
        assertTrue(events.isEmpty(), "no dialog, no event")
        c.openDelete(DriveDeleteChoice.AllBackups); settle()
        c.saveCopyFirst(); settle()
        assertEquals(listOf<DriveEvent>(DriveEvent.OpenSaveCopy), events)
        assertNotNull(c.state.value.dialog)
        c.cancelDelete()
        assertNull(c.state.value.dialog)
    }

    @Test
    fun oneOperationAtATime() = runTest {
        val rig = ConnectRig()
        val c = ctl(rig)
        firstConnect(rig, c)
        c.backUpNow(); c.backUpNow(); settle()
        assertEquals(1, c.state.value.ready!!.backups.size)
    }
}
