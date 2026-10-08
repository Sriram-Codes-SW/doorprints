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

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.doorprints.DoorprintsApp
import java.util.concurrent.TimeUnit

/**
 * The regular Drive run in the background (docs/15 §1.3): every 6 hours with a network and the battery not low, the daily
 * backup if one is due (the sync is [app.doorprints.data.SyncWorker]'s, every 30 minutes with a back-off). It does what [DriveBackgroundRunner] decides: nothing unless Drive is connected,
 * automatic backup is on and the phone still has its screen lock (asked again before every step); without the lock it
 * posts the documented notice and touches nothing in Drive. Photos follow the Wi-Fi-only rule (the controller's photo gate
 * reads `ConnectivityManager`). Unique periodic work, set and cancelled by [DriveServices.rescheduleWork].
 */
class DriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val drive = (applicationContext as DoorprintsApp).container.drive
        // Backup only: the regular sync is SyncWorker's (it runs while Drive is in use), so this run adds no second pass.
        return result(drive.runInBackground(sync = false, backup = true), runAttemptCount)
    }

    companion object {
        /** The unique periodic work's name, so a re-schedule replaces the one job. */
        const val WORK_NAME = "drive-backup"
        private const val PERIOD_HOURS = 6L
        private const val MAX_ATTEMPTS = 5

        /** The WorkManager result of an outcome: transient failures retry with backoff, up to [MAX_ATTEMPTS] in one period. */
        fun result(outcome: RunOutcome, attempt: Int): Result = when (outcome) {
            RunOutcome.Success, is RunOutcome.Skipped -> Result.success()
            RunOutcome.Retry -> if (attempt < MAX_ATTEMPTS) Result.retry() else Result.failure()
            RunOutcome.Failure -> Result.failure()
        }

        /** Sets the work on or cancels it; UPDATE keeps the period's clock instead of restarting it. */
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val work = PeriodicWorkRequestBuilder<DriveBackupWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, work)
        }
    }
}
