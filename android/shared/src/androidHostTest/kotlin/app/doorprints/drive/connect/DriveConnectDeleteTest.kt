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

import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.deviceauth.LockState
import app.doorprints.drive.DriveFault
import app.doorprints.drive.DriveOp
import app.doorprints.drive.FakeDriveServer
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.StopReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The delete flow on the phones (S4b-BL-119 as the screens see it): levels, the device check, partial run then resume, offline. */
class DriveConnectDeleteTest {
    private val server = FakeDriveServer()
    private val a = Phone(server, "Pixel 8")
    private val day = 24L * 60 * 60 * 1000

    private suspend fun withBackups(n: Int): List<String> {
        a.c.createFolder()
        a.c.confirmRecoveryKeySaved()
        val ids = mutableListOf<String>()
        repeat(n) {
            ids += a.c.backUpNow().ok().backup.id
            server.clock.advance(2 * day)
        }
        return ids
    }

    private fun driveFiles() = server.allFiles().filter { !it.trashed }.size

    @Test
    fun deletingOneOfTwoBackupsIsLevelOneNeedsNoDeviceCheckAndKeepsTheOther() = runTest {
        val ids = withBackups(2)
        val action = DeletionAction.OneBackup(ids[0])
        val info = a.c.deleteConfirmInfo(action).ok()
        assertEquals(DeleteConfirmInfo(DeletionLevel.L1, DeleteFactor.NONE, false, 0), info)
        assertEquals(DeleteFactor.NONE, a.c.deleteFactor(action))
        val plan = a.c.deletePlan(action).ok()
        assertEquals(DeletionLevel.L1, plan.level)
        val grant = a.c.authorizeDelete(plan, "Confirm").ok()
        assertEquals(0, a.deviceAuth.asks)
        val run = a.c.executeDelete(plan, grant).ok()
        assertEquals(DeleteRun(finished = true, left = 0, total = 1, stopped = null), run)
        assertEquals(listOf(ids[1]), a.c.listBackups().ok().backups.map { it.id })
        assertEquals(ConnectState.READY, a.c.state.value)
    }

    @Test
    fun levelOneRunsEvenWithoutAGrant() = runTest {
        val ids = withBackups(2)
        val plan = a.c.deletePlan(DeletionAction.OneBackup(ids[0])).ok()
        assertTrue(a.c.executeDelete(plan, null).ok().finished)
    }

    @Test
    fun theOnlyBackupIsLevelTwoAndNothingIsDeletedWithoutTheDeviceCheck() = runTest {
        val ids = withBackups(1)
        val action = DeletionAction.OneBackup(ids[0])
        assertEquals(DeleteFactor.DEVICE_AUTH, a.c.deleteFactor(action))
        assertEquals(DeletionLevel.L2, a.c.deleteConfirmInfo(action).ok().level)
        val plan = a.c.deletePlan(action).ok()
        val before = driveFiles()
        assertEquals(DriveReason.DELETE_NOT_AUTHORIZED, a.c.executeDelete(plan, null).reason())
        assertEquals(before, driveFiles())
        val grant = a.c.authorizeDelete(plan, "Confirm it's you").ok()
        assertEquals(1, a.deviceAuth.asks)
        assertEquals(DeleteLevel.L2, a.deviceAuth.lastLevel)
        assertTrue(a.c.executeDelete(plan, grant).ok().finished)
        assertEquals(emptyList(), a.c.listBackups().ok().backups)
    }

    @Test
    fun deleteAllBackupsAsksATickBoxAndDeleteEverythingAlsoFiveSeconds() = runTest {
        withBackups(2)
        assertEquals(DeleteConfirmInfo(DeletionLevel.L2, DeleteFactor.DEVICE_AUTH, true, 0), a.c.deleteConfirmInfo(DeletionAction.AllBackups).ok())
        assertEquals(DeleteConfirmInfo(DeletionLevel.L3, DeleteFactor.DEVICE_AUTH, true, 5000), a.c.deleteConfirmInfo(DeletionAction.Everything).ok())
        assertEquals(DeleteFactor.DEVICE_AUTH, a.c.deleteFactor(DeletionAction.Everything))
        assertEquals(DeleteFactor.DEVICE_AUTH, a.c.deleteFactor(DeletionAction.OlderBackups))
    }

    @Test
    fun theFactorIsNeverAPasskeyOrTheRecoveryKey() {
        assertEquals(listOf(DeleteFactor.NONE, DeleteFactor.DEVICE_AUTH), DeleteFactor.entries)
    }

    @Test
    fun deleteEverythingPassedLeavesNothingAndEndsTheSession() = runTest {
        withBackups(2)
        val plan = a.c.deletePlan(DeletionAction.Everything).ok()
        assertEquals(DeletionLevel.L3, plan.level)
        val grant = a.c.authorizeDelete(plan, "Confirm").ok()
        assertEquals(DeleteLevel.L3, a.deviceAuth.lastLevel)
        val run = a.c.executeDelete(plan, grant).ok()
        assertTrue(run.finished)
        assertEquals(0, run.left)
        assertEquals(0, server.allFiles().size)
        assertEquals(ConnectState.DISCONNECTED, a.c.state.value)
        assertFalse(a.c.isReady)
        assertEquals(DeletionLevel.L3, a.c.deletedMarker()!!.level)
        assertNull(a.c.pendingDeletion())
        assertEquals(DriveReason.NOT_CONNECTED, a.c.listBackups().reason())
        assertEquals(DriveReason.NOT_CONNECTED, a.c.deletePlan(DeletionAction.AllBackups).reason())
    }

    @Test
    fun deletingBackupsOnlyKeepsTheSession() = runTest {
        withBackups(2)
        val plan = a.c.deletePlan(DeletionAction.AllBackups).ok()
        assertTrue(a.c.executeDelete(plan, a.c.authorizeDelete(plan, "x").ok()).ok().finished)
        assertEquals(ConnectState.READY, a.c.state.value)
        assertTrue(a.c.isReady)
        assertEquals(DeletionLevel.L2, a.c.deletedMarker()!!.level)
    }

    @Test
    fun deniedAndCancelledChecksDeleteNothing() = runTest {
        withBackups(2)
        val plan = a.c.deletePlan(DeletionAction.AllBackups).ok()
        val before = driveFiles()
        val cases = mapOf(
            AuthResult.CANCELLED to DriveReason.AUTH_CANCELLED, AuthResult.FAILED to DriveReason.AUTH_FAILED,
            AuthResult.LOCKED_OUT to DriveReason.AUTH_LOCKED_OUT, AuthResult.LOCK_NOT_SET to DriveReason.AUTH_LOCK_NOT_SET,
            AuthResult.NOT_AVAILABLE to DriveReason.AUTH_NOT_AVAILABLE,
        )
        for ((result, reason) in cases) {
            a.deviceAuth.next = result
            assertEquals(reason, a.c.authorizeDelete(plan, "x").reason())
        }
        assertEquals(DriveReason.DELETE_NOT_AUTHORIZED, a.c.executeDelete(plan, null).reason())
        assertEquals(before, driveFiles())
    }

    @Test
    fun aThrowingDeviceCheckIsAFailureNotACrash() = runTest {
        withBackups(1)
        val plan = a.c.deletePlan(DeletionAction.AllBackups).ok()
        a.deviceAuth.boom = IllegalStateException("keystore secret")
        assertEquals(DriveReason.AUTH_FAILED, a.c.authorizeDelete(plan, "x").reason())
    }

    @Test
    fun withoutAScreenLockTheLevelTwoActionsAreRefusedEverywhere() = runTest {
        withBackups(2)
        a.deviceAuth.lockEnabled = false
        assertEquals(DriveReason.NO_DEVICE_LOCK, a.c.deleteConfirmInfo(DeletionAction.AllBackups).reason())
        assertNull(a.c.deleteFactor(DeletionAction.AllBackups))
        val plan = a.c.deletePlan(DeletionAction.AllBackups).ok()
        assertEquals(DriveReason.NO_DEVICE_LOCK, a.c.authorizeDelete(plan, "x").reason())
        assertEquals(DriveReason.NO_DEVICE_LOCK, a.c.revokeListedDevice("aa", "x").reason())
        assertEquals(DriveReason.NO_DEVICE_LOCK, a.c.approveJoinedDevice(ByteArray(65), "n", app.doorprints.crypto.DevicePlatform.ANDROID, "x").reason())
        assertTrue(a.enrolment.calls.isEmpty())
    }

    @Test
    fun offlineIsRefusedBeforeAnyPlanOrPrompt() = runTest {
        val ids = withBackups(2)
        a.online = false
        assertEquals(DriveReason.DELETE_OFFLINE, a.c.deletePlan(DeletionAction.AllBackups).reason())
        assertEquals(DriveReason.DELETE_OFFLINE, a.c.deleteConfirmInfo(DeletionAction.AllBackups).reason())
        assertNull(a.c.deleteFactor(DeletionAction.Everything))
        assertEquals(0, a.deviceAuth.asks)
        a.online = true
        val plan = a.c.deletePlan(DeletionAction.OneBackup(ids[0])).ok()
        a.online = false
        assertEquals(DriveReason.DELETE_OFFLINE, a.c.executeDelete(plan, null).reason())
        assertEquals(2, run { a.online = true; a.c.listBackups().ok().backups.size })
    }

    @Test
    fun aRunThatStopsHalfWayIsReportedAndResumedWithAFreshCheck() = runTest {
        withBackups(3)
        val plan = a.c.deletePlan(DeletionAction.AllBackups).ok()
        val grant = a.c.authorizeDelete(plan, "x").ok()
        server.faults.on(DriveOp.DELETE, 2, DriveFault.RateLimited(retryAfterMs = 120_000))
        val first = a.c.executeDelete(plan, grant).ok()
        assertFalse(first.finished)
        assertEquals(2, first.left)
        assertEquals(3, first.total)
        assertEquals(StopReason.DRIVE_ERROR, first.stopped)
        val pending = a.c.pendingDeletion()
        assertNotNull(pending)
        assertEquals(plan.operationId, pending.operationId)
        // the first grant was used and forgotten: a resume with it is refused, a fresh check passes
        assertEquals(DriveReason.DELETE_NOT_AUTHORIZED, a.c.resumeDelete(grant).reason())
        val fresh = a.c.authorizeResume("Confirm").ok()
        val second = a.c.resumeDelete(fresh).ok()
        assertEquals(DeleteRun(true, 0, 3, null), second)
        assertNull(a.c.pendingDeletion())
        assertEquals(2, a.deviceAuth.asks)
    }

    @Test
    fun resumeWithNothingPendingIsSaidAndAsksNothing() = runTest {
        withBackups(1)
        assertEquals(DriveReason.DELETE_NOTHING_PENDING, a.c.authorizeResume("x").reason())
        assertEquals(DriveReason.DELETE_NOTHING_PENDING, a.c.resumeDelete(null).reason())
        assertEquals(0, a.deviceAuth.asks)
    }

    @Test
    fun aStaleOrForeignGrantIsRefusedByTheCore() = runTest {
        withBackups(3)
        val plan = a.c.deletePlan(DeletionAction.AllBackups).ok()
        val grant = a.c.authorizeDelete(plan, "x").ok()
        server.clock.advance(61_000)
        assertEquals(DriveReason.DELETE_AUTHORIZATION_STALE, a.c.executeDelete(plan, grant).reason(), "past 60 seconds the grant is stale")
        val other = a.c.deletePlan(DeletionAction.OlderBackups).ok()
        val g2 = a.c.authorizeDelete(plan, "x").ok()
        assertEquals(DriveReason.DELETE_AUTHORIZATION_OTHER_OPERATION, a.c.executeDelete(other, g2).reason(), "a grant bound to another operation never runs this one")
    }

    @Test
    fun theLockBeingRemovedStopsARunMidWay() = runTest {
        withBackups(3)
        val plan = a.c.deletePlan(DeletionAction.AllBackups).ok()
        val grant = a.c.authorizeDelete(plan, "x").ok()
        a.lock.state = LockState.REMOVED
        val out = a.c.executeDelete(plan, grant)
        assertTrue(out is Outcome.Failed || (out as Outcome.Ok).value.stopped == StopReason.AUTHORIZATION_LOST)
        assertEquals(3, a.c.listBackups().ok().backups.size, "nothing was deleted after the lock went")
    }

    @Test
    fun aGrantThatWasForgottenIsNotGenuine() = runTest {
        withBackups(2)
        val plan = a.c.deletePlan(DeletionAction.AllBackups).ok()
        val grant = a.c.authorizeDelete(plan, "x").ok()
        a.authorizer.forget()
        val before = driveFiles()
        assertEquals(DriveReason.DELETE_NOT_AUTHORIZED, a.c.executeDelete(plan, grant).reason())
        assertEquals(before, driveFiles())
    }

    @Test
    fun aGrantCannotBeUsedTwice() = runTest {
        withBackups(3)
        val plan = a.c.deletePlan(DeletionAction.AllBackups).ok()
        val grant = a.c.authorizeDelete(plan, "x").ok()
        server.faults.on(DriveOp.DELETE, 2, DriveFault.RateLimited(retryAfterMs = 120_000))
        assertFalse(a.c.executeDelete(plan, grant).ok().finished)
        assertEquals(DriveReason.DELETE_NOT_AUTHORIZED, a.c.executeDelete(plan, grant).reason())
    }
}
