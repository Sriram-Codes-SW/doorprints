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

import androidx.work.ListenableWorker.Result
import org.junit.Assert.assertEquals
import org.junit.Test

/** How a run's outcome becomes WorkManager's result (the periodic worker and the sync worker share it). */
class DriveBackupWorkerTest {
    @Test
    fun successAndSkippedAreSuccess() {
        assertEquals(Result.success(), DriveBackupWorker.result(RunOutcome.Success, 0))
        for (r in SkipReason.entries) assertEquals(Result.success(), DriveBackupWorker.result(RunOutcome.Skipped(r), 0))
    }

    @Test
    fun aTransientFailureRetriesFiveTimesThenFails() {
        for (attempt in 0..4) assertEquals(Result.retry(), DriveBackupWorker.result(RunOutcome.Retry, attempt))
        assertEquals(Result.failure(), DriveBackupWorker.result(RunOutcome.Retry, 5))
    }

    @Test
    fun aFailureThatNeedsThePersonDoesNotRetry() {
        assertEquals(Result.failure(), DriveBackupWorker.result(RunOutcome.Failure, 0))
    }
}
