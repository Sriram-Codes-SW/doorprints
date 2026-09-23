package com.househunt.app.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How often a worker publishes its progress to WorkManager (and so to the screen's progress bar). */
internal const val PROGRESS_INTERVAL_MS = 400L

/** How often the foreground notification may be rebuilt; see [reportProgress]. */
internal const val FOREGROUND_INTERVAL_MS = 1_000L

/** At or above this many photos, an export, import or automatic backup asks to run in the foreground. */
internal const val FOREGROUND_PHOTO_THRESHOLD = 25

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
 */
internal fun CoroutineScope.reportProgress(
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
            val now = System.currentTimeMillis()
            if (percent != lastPercent && now - lastNotifiedAt >= FOREGROUND_INTERVAL_MS) {
                lastPercent = percent
                lastNotifiedAt = now
                runCatching { promote(done, total) }
            }
        }
        delay(PROGRESS_INTERVAL_MS)
    }
}
