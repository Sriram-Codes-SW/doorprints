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
import app.doorprints.drive.connect.ConnectResult
import app.doorprints.drive.connect.ConnectState
import app.doorprints.drive.connect.DriveConnectController
import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.connect.DueBackupResult
import app.doorprints.drive.connect.SyncInfo
import app.doorprints.drive.connect.SyncState

/** What a background run needs from Drive: the controller's calls and the lock check, as one seam the JVM tests fake. */
interface BackgroundOps {
    /** Drive is in use on this phone (persisted; asked without building the Drive graph). */
    val engaged: Boolean
    /** The folder is open in this process. */
    val isReady: Boolean
    /** Whether *Automatic backup* is on. */
    fun autoBackupEnabled(): Boolean

    /** The lock check before a run ([app.doorprints.deviceauth.DriveGate.beforeRun]): a removed lock drops local keys only. */
    fun lockDecision(): RunDecision
    /** Signs in and opens the folder. */
    suspend fun connect(): ConnectResult
    /** One sync pass. */
    suspend fun sync(): SyncInfo
    /** A backup if the schedule says one is due. */
    suspend fun runDueBackup(): DueBackupResult

    /**
     * Drive is already paused for want of the lock (or a lost key) and nothing has changed: the reason to stay out, asked
     * **before** anything is built or touched, so a paused phone costs one small file read per run. Null: look again.
     */
    fun standingPause(): SkipReason? = null

    /** The documented notice, once: "Google Drive backup is paused because this phone no longer has a screen lock...". */
    fun onLockPaused()
}

/** [BackgroundOps] over the real controller. */
class ControllerBackgroundOps(
    private val controller: () -> DriveConnectController,
    override val engaged: Boolean,
    private val lock: () -> RunDecision,
    private val notifyLock: () -> Unit,
    private val standing: () -> SkipReason? = { null },
) : BackgroundOps {
    override val isReady: Boolean get() = controller().isReady
    override fun autoBackupEnabled() = controller().autoBackupEnabled()
    override fun lockDecision() = lock()
    override suspend fun connect() = controller().connect()
    override suspend fun sync() = controller().syncNow()
    override suspend fun runDueBackup() = controller().runDueBackup()
    override fun standingPause() = standing()
    override fun onLockPaused() = notifyLock()
}

/** How a background run ended, in WorkManager's terms. */
sealed interface RunOutcome {
    /** Nothing to do, or done. */
    data object Success : RunOutcome

    /** A transient failure (offline, rate limited, a Drive hiccup): try again with backoff. */
    data object Retry : RunOutcome

    /** Needs the person (not enrolled, revoked, signed out, a full Drive): the next period tries again, nothing is retried now. */
    data object Failure : RunOutcome

    /** Not run at all, with the reason (not connected, auto-backup off, no screen lock). */
    data class Skipped(val reason: SkipReason) : RunOutcome
}

/**
 * One background run of Drive (the periodic [DriveBackupWorker], and the sync after a change): the rules of docs/15 §1.3
 * and §10.3 in one place. It runs only when Drive is connected, automatic backup is on **and** the phone still has its
 * screen lock, and asks the lock again before **each** step. A missing lock stops the run with the documented notice and
 * touches nothing in Drive: no upload, download or delete (the lock check drops local keys only).
 */
class DriveBackgroundRunner(private val ops: BackgroundOps, private val onSync: (SyncInfo) -> Unit = {}) {
    /**
      * Runs the steps asked for, in order (connect if needed, sync, backup), checking the lock before each. The result
      * is the
     * worst of the steps (failure over retry over success); a skipped run touches nothing in Drive.
     */
    suspend fun run(sync: Boolean, backup: Boolean): RunOutcome {
        // A pause that still stands says nothing again (the notice was shown when it began) and builds nothing.
        if (ops.engaged) ops.standingPause()?.let { return RunOutcome.Skipped(it) }
        when (val d = guard()) {
            is WorkDecision.Skip -> return skipped(d)
            WorkDecision.Run -> Unit
        }
        if (!ops.isReady) {
            val connected = ops.connect()
            if (connected.state != ConnectState.READY) return if (connected.error?.let(::retryable) == true) RunOutcome.Retry else RunOutcome.Failure
        }
        var result: RunOutcome = RunOutcome.Success
        if (sync) {
            val info = ops.sync()
            onSync(info)
            result = worse(result, syncOutcome(info))
            // The lock is asked again before the next step: it may have gone while the sync ran.
            (guard() as? WorkDecision.Skip)?.let { return skipped(it) }
        }
        if (backup) result = worse(result, backupOutcome(ops.runDueBackup()))
        return result
    }

    private fun guard(): WorkDecision = DriveWorkRules.decide(ops.engaged, ops.autoBackupEnabled(), ops::lockDecision)

    private fun skipped(d: WorkDecision.Skip): RunOutcome {
        if (DriveWorkRules.isLockPause(d.reason)) ops.onLockPaused()
        return RunOutcome.Skipped(d.reason)
    }

    companion object {
        /** Reasons that may pass by themselves: the network, Google's rate limit, a server hiccup. */
        fun retryable(reason: DriveReason): Boolean = when (reason) {
            DriveReason.OFFLINE, DriveReason.RATE_LIMITED, DriveReason.SERVER, DriveReason.DRIVE, DriveReason.CONNECT_FAILED -> true
            else -> false
        }

        fun syncOutcome(info: SyncInfo): RunOutcome = when (info.state) {
            SyncState.SYNCED, SyncState.SKIPPED_FILES, SyncState.NOT_RUN -> RunOutcome.Success
            // The shrink guard waits for the person; a pause waits for the lock or the deletion: neither is a failure to retry.
            SyncState.NEEDS_CONFIRMATION, SyncState.PAUSED -> RunOutcome.Success
            SyncState.WAITING, SyncState.OFFLINE -> RunOutcome.Retry
            SyncState.ERROR -> if (info.error?.let(::retryable) == true) RunOutcome.Retry else RunOutcome.Failure
        }

        fun backupOutcome(result: DueBackupResult): RunOutcome = when (result) {
            is DueBackupResult.Ran -> RunOutcome.Success
            // Not due, waiting out a backoff, or blocked: the schedule says when to ask again, which is the next period.
            is DueBackupResult.NotRan -> RunOutcome.Success
            is DueBackupResult.Failed -> if (retryable(result.reason)) RunOutcome.Retry else RunOutcome.Failure
        }

        private fun worse(a: RunOutcome, b: RunOutcome): RunOutcome = when {
            a is RunOutcome.Failure || b is RunOutcome.Failure -> RunOutcome.Failure
            a is RunOutcome.Retry || b is RunOutcome.Retry -> RunOutcome.Retry
            else -> a
        }
    }
}
