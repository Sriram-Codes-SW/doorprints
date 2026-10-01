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

package app.doorprints.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** How often a worker publishes its progress to WorkManager (and so to the screen's progress bar). */
const val PROGRESS_INTERVAL_MS = 400L

/** How often the foreground notification may be rebuilt; see [reportProgress]. */
const val FOREGROUND_INTERVAL_MS = 1_000L

/** At or above this many photos, an export, import or automatic backup asks to run in the foreground. */
const val FOREGROUND_PHOTO_THRESHOLD = 25

/**
 * The progress loop the export, import and automatic-backup workers share.
 *
 * The progress bar updates every [PROGRESS_INTERVAL_MS]; the notification does not. NotificationManager
 * rate-limits rapid updates to the same id and starts dropping them, and every `setForeground` call goes through
 * WorkManager's foreground-service path, so [promote] runs at most once every [FOREGROUND_INTERVAL_MS] and only
 * when the percentage has actually moved. Both calls are wrapped in `runCatching`: a failed progress update must
 * not cancel the scope and with it the work it is reporting on.
 *
 * Cancel the returned job when the work is done; it never finishes on its own.
 *
 * Common since S4b-BL-106 (was `:app`'s `WorkProgress.kt`), so a platform without WorkManager can drive its progress
 * bar and notification with the same pacing; Android's export, import and automatic-backup workers call it.
 */
fun CoroutineScope.reportProgress(
    progress: StateFlow<Pair<Int, Int>>,
    foreground: Boolean,
    publish: suspend (done: Int, total: Int) -> Unit,
    promote: suspend (done: Int, total: Int) -> Unit,
): Job = launch {
    var lastNotifiedAt = 0L
    var lastPercent = -1
    while (isActive) {
        val (done, total) = progress.value
        runCatching { publish(done, total) }
        if (foreground) {
            val percent = if (total > 0) done * 100 / total else -1
            val now = Clock.System.now().toEpochMilliseconds()
            if (percent != lastPercent && now - lastNotifiedAt >= FOREGROUND_INTERVAL_MS) {
                lastPercent = percent
                lastNotifiedAt = now
                runCatching { promote(done, total) }
            }
        }
        delay(PROGRESS_INTERVAL_MS)
    }
}
