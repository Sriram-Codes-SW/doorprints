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
import app.doorprints.drive.backup.BackupSchedule
import app.doorprints.drive.connect.ConnectResult
import app.doorprints.drive.connect.ConnectState
import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.connect.DueBackupResult
import app.doorprints.drive.connect.SyncInfo
import app.doorprints.drive.connect.SyncState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One background run of Drive (docs/15 §1.3, §10.3) over a fake controller: when it goes, in which order, and what ends it. */
class DriveBackgroundRunnerTest {

    private class Ops(
        override var engaged: Boolean = true,
        override var isReady: Boolean = true,
    ) : BackgroundOps {
        var auto = true
        val locks = ArrayDeque<RunDecision>()
        var lockDefault: RunDecision = RunDecision.Run
        var connectResult = ConnectResult(ConnectState.READY)
        var syncResult = SyncInfo(SyncState.SYNCED, 1L)
        var backupResult: DueBackupResult = DueBackupResult.Ran(BackupSchedule.Reason.DAILY)
        val calls = mutableListOf<String>()

        override fun autoBackupEnabled() = auto
        override fun lockDecision(): RunDecision {
            calls += "lock"
            return locks.removeFirstOrNull() ?: lockDefault
        }

        override suspend fun connect(): ConnectResult {
            calls += "connect"
            if (connectResult.state == ConnectState.READY) isReady = true
            return connectResult
        }

        override suspend fun sync(): SyncInfo {
            calls += "sync"
            return syncResult
        }

        override suspend fun runDueBackup(): DueBackupResult {
            calls += "backup"
            return backupResult
        }

        var notices = 0
        override fun onLockPaused() {
            notices++
            calls += "notice"
        }
    }

    private fun run(ops: Ops, sync: Boolean = true, backup: Boolean = true) =
        runBlocking { DriveBackgroundRunner(ops).run(sync, backup) }

    private fun Ops.driveCalls() = calls.filter { it in setOf("connect", "sync", "backup") }

    @Test
    fun aConnectedPhoneWithTheLockSyncsThenBacksUp() {
        val ops = Ops()
        assertEquals(RunOutcome.Success, run(ops))
        assertEquals(listOf("sync", "backup"), ops.driveCalls())
    }

    @Test
    fun notConnectedDoesNothing() {
        val ops = Ops(engaged = false)
        assertEquals(RunOutcome.Skipped(SkipReason.NOT_CONNECTED), run(ops))
        assertEquals(emptyList<String>(), ops.calls)
    }

    @Test
    fun autoBackupOffDoesNothing() {
        val ops = Ops().apply { auto = false }
        assertEquals(RunOutcome.Skipped(SkipReason.AUTO_OFF), run(ops))
        assertEquals(emptyList<String>(), ops.driveCalls())
    }

    @Test
    fun aRemovedLockStopsBeforeAnythingAndSaysTheDocumentedNoticeOnce() {
        val ops = Ops().apply { lockDefault = RunDecision.PausedNoLock }
        assertEquals(RunOutcome.Skipped(SkipReason.LOCK_REMOVED), run(ops))
        // Nothing reached Drive: no connect, no sync, no backup (so nothing could be uploaded, downloaded or deleted).
        assertEquals(emptyList<String>(), ops.driveCalls())
        assertEquals(1, ops.notices)
    }

    @Test
    fun anUnreadableLockAlsoPausesWithTheNotice() {
        val ops = Ops().apply { lockDefault = RunDecision.PausedUnknown }
        assertEquals(RunOutcome.Skipped(SkipReason.LOCK_UNKNOWN), run(ops))
        assertEquals(1, ops.notices)
    }

    @Test
    fun aLockRemovedWhileTheSyncRanStopsBeforeTheBackup() {
        val ops = Ops().apply { locks += RunDecision.Run; locks += RunDecision.PausedNoLock }
        assertEquals(RunOutcome.Skipped(SkipReason.LOCK_REMOVED), run(ops))
        assertEquals(listOf("sync"), ops.driveCalls())
        assertEquals(1, ops.notices)
    }

    @Test
    fun theLockIsAskedBeforeEveryStep() {
        val ops = Ops()
        run(ops)
        // Before the sync, and again before the backup.
        assertEquals(listOf("lock", "sync", "lock", "backup"), ops.calls)
    }

    @Test
    fun aBackupOnlyRunAsksTheLockOnce() {
        val ops = Ops()
        run(ops, sync = false)
        assertEquals(listOf("lock", "backup"), ops.calls)
    }

    @Test
    fun aRestartedProcessReconnectsFirst() {
        val ops = Ops(isReady = false)
        assertEquals(RunOutcome.Success, run(ops))
        assertEquals(listOf("connect", "sync", "backup"), ops.driveCalls())
    }

    @Test
    fun aReconnectThatNeedsThePersonFailsWithoutRetry() {
        val ops = Ops(isReady = false).apply { connectResult = ConnectResult(ConnectState.NEEDS_ENROLMENT) }
        assertEquals(RunOutcome.Failure, run(ops))
        assertEquals(listOf("connect"), ops.driveCalls())
    }

    @Test
    fun aReconnectThatWentOfflineRetries() {
        val ops = Ops(isReady = false).apply { connectResult = ConnectResult(ConnectState.ERROR, error = DriveReason.OFFLINE) }
        assertEquals(RunOutcome.Retry, run(ops))
    }

    @Test
    fun anOfflineSyncRetriesButTheBackupStillGetsItsTurn() {
        val ops = Ops().apply { syncResult = SyncInfo(SyncState.OFFLINE, null, error = DriveReason.OFFLINE) }
        assertEquals(RunOutcome.Retry, run(ops))
        assertEquals(listOf("sync", "backup"), ops.driveCalls())
    }

    @Test
    fun aFailureOutranksARetry() {
        val ops = Ops().apply {
            syncResult = SyncInfo(SyncState.OFFLINE, null, error = DriveReason.OFFLINE)
            backupResult = DueBackupResult.Failed(DriveReason.UNAUTHORIZED)
        }
        assertEquals(RunOutcome.Failure, run(ops))
    }

    // ---- the mapping of results ----

    @Test
    fun syncStatesMapToWorkOutcomes() {
        fun o(s: SyncState, e: DriveReason? = null) = DriveBackgroundRunner.syncOutcome(SyncInfo(s, null, error = e))
        assertEquals(RunOutcome.Success, o(SyncState.SYNCED))
        assertEquals(RunOutcome.Success, o(SyncState.SKIPPED_FILES))
        assertEquals(RunOutcome.Success, o(SyncState.NOT_RUN))
        assertEquals(RunOutcome.Success, o(SyncState.PAUSED))
        assertEquals(RunOutcome.Success, o(SyncState.NEEDS_CONFIRMATION))
        assertEquals(RunOutcome.Retry, o(SyncState.WAITING))
        assertEquals(RunOutcome.Retry, o(SyncState.OFFLINE))
        assertEquals(RunOutcome.Retry, o(SyncState.ERROR, DriveReason.RATE_LIMITED))
        assertEquals(RunOutcome.Failure, o(SyncState.ERROR, DriveReason.UNAUTHORIZED))
        assertEquals(RunOutcome.Failure, o(SyncState.ERROR, null))
    }

    @Test
    fun backupResultsMapToWorkOutcomes() {
        assertEquals(RunOutcome.Success, DriveBackgroundRunner.backupOutcome(DueBackupResult.Ran(BackupSchedule.Reason.DAILY)))
        assertEquals(RunOutcome.Success, DriveBackgroundRunner.backupOutcome(DueBackupResult.NotRan(BackupSchedule.Reason.NOT_DUE)))
        assertEquals(RunOutcome.Retry, DriveBackgroundRunner.backupOutcome(DueBackupResult.Failed(DriveReason.SERVER)))
        assertEquals(RunOutcome.Failure, DriveBackgroundRunner.backupOutcome(DueBackupResult.Failed(DriveReason.QUOTA_EXCEEDED)))
    }

    @Test
    fun onlyTransientReasonsRetry() {
        val retryable = DriveReason.entries.filter { DriveBackgroundRunner.retryable(it) }.toSet()
        assertEquals(
            setOf(DriveReason.OFFLINE, DriveReason.RATE_LIMITED, DriveReason.SERVER, DriveReason.DRIVE, DriveReason.CONNECT_FAILED),
            retryable,
        )
        assertTrue(DriveReason.DEVICE_REVOKED !in retryable)
    }
}
