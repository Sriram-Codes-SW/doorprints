package app.doorprints.export

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.work.*
import app.doorprints.DoorprintsApp
import app.doorprints.Notifications
import app.doorprints.R
import app.doorprints.i18n.AppLocale
import app.doorprints.shared.export.BackupCompleteness
import app.doorprints.shared.export.ExportFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.util.UUID

/**
 * Builds one export file off the main thread (S4-02).
 *
 * WorkManager rather than a plain coroutine, for three reasons: a backup with hundreds of photos outlives the
 * screen that started it; the progress has to survive a rotation or a trip to the recents list; and a job Android
 * stops mid-way can be started again. At [FOREGROUND_PHOTO_THRESHOLD] photos or more the job also asks to run in
 * the foreground with a quiet progress notification, so the system does not stop it while the user does something
 * else. A refused promotion is not fatal — the export just continues as ordinary background work.
 *
 * The bytes go straight into the `content://` document the user picked, so nothing large is copied twice and the
 * file lands where the user chose (Downloads, the Drive app, an SD card) rather than in app storage. The other
 * side of that is that a run which does not finish leaves a truncated document with a plausible name in the
 * user's folder. A file the user can see must never be one the app knows is broken, so this class deletes it —
 * and it is the only place that can decide correctly when to.
 *
 * **Stopping is cooperative.** [Exporters.write] is blocking code on [Dispatchers.IO]; cancelling the coroutine
 * does not interrupt it. So the progress callback calls `ensureActive()`, and a stop takes effect at the next
 * row, page or photo instead of after the whole file has been written into a document that is about to go.
 * (Nothing under `export/` wraps a progress call in `runCatching`, which would swallow the
 * [CancellationException]; keep it that way.)
 *
 * **Who deletes a half-written file, and when.** A stop is not one event, and only one kind of stop ends the run:
 *
 *  - *Stop* ([cancel]) marks the run `CANCELLED` and it never runs again.
 *  - Starting a second export ([start], [ExistingWorkPolicy.REPLACE]) cancels the first **and deletes its row**
 *    in the same transaction (`EnqueueRunnable`), so the replaced run is never seen as `CANCELLED` — it is gone.
 *  - The *system* stopping the job (a constraint went away, the ~10-minute job limit, the process being trimmed)
 *    puts it back to `ENQUEUED`, and the retry writes into the same document, which must therefore survive.
 *
 * `ListenableWorker.getStopReason()` would say which case this is, but it is `@RequiresApi(31)` and minSdk is 26.
 * The work database says it too, so the cancellation path reads this run's own row ([discardIfRunEnded]): a missing
 * row (replaced) or `CANCELLED` (Stop) means the document is abandoned and is deleted; anything else is a retry
 * and the document is kept. The row is polled briefly because WorkManager may still show `RUNNING` for a moment
 * after the worker's coroutine was cancelled. A run that has already returned success never reaches that path,
 * which is what makes "Stop" safe to tap in the moment between the file being finished and the screen noticing.
 *
 * **The grant outlives the screen.** A "Save to…" document is reachable only through a URI grant, and the one the
 * picker returns ends with the activity. The screen persists it before starting the run ([ExportGrants.take]), so
 * the delete above, a retry's reopen, the display-name query and the notification's Open and Share all still work
 * after the user has backed out of the app. The run gives the grant back when it fails or its file is deleted; a
 * finished export keeps it, within the bounded list [ExportGrants] maintains.
 */
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val request = ExportRequest.fromData(inputData) ?: return@coroutineScope Result.failure()
        val repository = (applicationContext as DoorprintsApp).container.repository
        val localised = AppLocale.wrap(applicationContext)

        try {
            val bundle = ExportBuilder.bundle(repository, request.options)
            val heavy = bundle.photos.size >= FOREGROUND_PHOTO_THRESHOLD
            if (heavy) runCatching { setForeground(foregroundInfo(localised, 0, 0)) }

            val progress = MutableStateFlow(0 to 0)
            val reporter = reportProgress(
                progress = progress,
                foreground = heavy,
                publish = { done, total ->
                    setProgress(workDataOf(ExportRequest.KEY_DONE to done, ExportRequest.KEY_TOTAL to total))
                },
                promote = { done, total -> setForeground(foregroundInfo(localised, done, total)) },
            )

            try {
                withContext(Dispatchers.IO) {
                    val writing = this
                    BufferedOutputStream(Saf.openOutput(applicationContext, request.target)).use { out ->
                        Exporters.write(
                            bundle = bundle,
                            format = request.format,
                            out = out,
                            photoFile = { id -> repository.photoFile(id) },
                            appVersion = ExportBuilder.appVersion(applicationContext),
                        ) { done, total ->
                            // The cooperative stop point (see the class KDoc): throws CancellationException once
                            // the run has been stopped, which unwinds the writer and closes the stream.
                            writing.ensureActive()
                            progress.value = done to total
                        }
                    }
                }
            } finally {
                reporter.cancel()
            }

            // The name the user will recognise ("Doorprints-2026-09-22.html"), never the document id that ends a
            // content:// URI ("msf%3A1000001234"). Null when the provider will not say; the screen then says
            // just "Saved". Deliberately *not* a suspension point: from here on the file is complete, and a Stop
            // landing now must not reach the cancellation path, which would delete it (see the class KDoc). One
            // small provider query on the worker's own thread is fine.
            val saved = request.target.startsWith("content://")
            val name = if (saved) Saf.displayName(applicationContext, Uri.parse(request.target)) else File(request.target).name
            // "Saved to Download: …" where the provider says where (UX review, round 11); string work only.
            val location = if (saved) Saf.locationName(localised, Uri.parse(request.target)) else null
            // A "Full backup" that leaves something out is never called complete, here or on the screen.
            val partial = request.format == ExportFormat.BACKUP && !BackupCompleteness.isComplete(request.options)
            // Told = seen on the screen, or a notification that was really posted (see ExportRequest.KEY_NOTIFIED).
            val notified = ScreenWatch.exportScreen || notifyDone(localised, request, name, location, partial)
            Result.success(
                workDataOf(
                    ExportRequest.KEY_WRITTEN to request.target,
                    ExportRequest.KEY_FORMAT to request.format.name,
                    ExportRequest.KEY_NAME to name,
                    ExportRequest.KEY_LOCATION to location,
                    ExportRequest.KEY_PARTIAL to partial,
                    ExportRequest.KEY_NOTIFIED to notified,
                    ExportRequest.KEY_FINISHED_AT to System.currentTimeMillis(),
                )
            )
        } catch (e: CancellationException) {
            // Not an error, and a half-written file is never reported as saved. NonCancellable because this
            // coroutine is already cancelled: without it the first suspension point below would throw again.
            withContext(NonCancellable + Dispatchers.IO) { discardIfRunEnded(request.target) }
            throw e
        } catch (e: Exception) {
            if (!isActive) {
                // A stop that surfaced as an I/O error (a provider closing the stream under us, say) is still a
                // stop: treat it exactly like the branch above rather than as a failure that deletes the file a
                // retry would need.
                withContext(NonCancellable + Dispatchers.IO) { discardIfRunEnded(request.target) }
                throw e
            }
            Log.w(TAG, "Export failed", e)
            discardPartial(applicationContext, request.target)
            // Nothing will write to or open this document again; give its persisted grant back (see ExportGrants).
            ExportGrants.release(applicationContext, request.target)
            // A stable code, never e.message: see ExportProblem.
            val problem = ExportProblem.of(e)
            val notified = ScreenWatch.exportScreen || Notifications.result(
                localised, Notifications.EXPORT_DONE_ID,
                localised.getString(R.string.export_notif_failed),
                // A whole sentence, as on the screen: the bare reason ("there is not enough free space")
                // is a lowercase fragment made to be slotted into export_failed.
                localised.getString(R.string.export_failed, localised.getString(problem.messageRes())),
                Notifications.openScreenIntent(localised, Notifications.SCREEN_EXPORT),
            )
            Result.failure(
                workDataOf(
                    ExportRequest.KEY_ERROR to problem.code,
                    ExportRequest.KEY_NOTIFIED to notified,
                    ExportRequest.KEY_FINISHED_AT to System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * After a stop: deletes [target] only if this run has really ended (see the class KDoc).
     *
     * `CancelWorkRunnable` marks the row `CANCELLED` in the transaction that stops the worker, and a system stop
     * moves it back to `ENQUEUED` once the worker has been interrupted, so the first read can still see `RUNNING`.
     * Up to [END_POLLS] reads [END_POLL_MS] apart settle it; a row still `RUNNING` after that, or a read that
     * fails, keeps the file — a leftover the user can delete is recoverable, a deleted retry target is not.
     */
    private suspend fun discardIfRunEnded(target: String) {
        val manager = WorkManager.getInstance(applicationContext)
        repeat(END_POLLS) {
            val info = runCatching { manager.getWorkInfoById(id).get() }.getOrElse { return }
            when (info?.state) {
                // No row: replaced by a newer export. CANCELLED: the Stop button. Either way, nothing will ever
                // finish this document.
                null, WorkInfo.State.CANCELLED -> {
                    if (!claimedByAnotherRun(manager, id, target)) {
                        discardPartial(applicationContext, target)
                        // Only now, after the delete: the persisted grant is what lets that delete work when the
                        // user has already left the app (see ExportGrants). A retry keeps both file and grant.
                        ExportGrants.release(applicationContext, target)
                    }
                    return
                }
                // Not settled yet; see above.
                WorkInfo.State.RUNNING -> delay(END_POLL_MS)
                // ENQUEUED (or BLOCKED): a system stop that will be retried into this same document. Keep it.
                else -> return
            }
        }
    }

    /**
     * The export finished while no Export screen was showing (the user left the app, or went back): say so, with
     * Open and Share, because otherwise a copy made for the share sheet sits unreachable in the cache and a saved
     * one is found only by chance. The text is the result card's own sentence ([resultText]), so a partial backup
     * is called partial here too. Returns whether the notification was posted.
     */
    private fun notifyDone(
        context: Context,
        request: ExportRequest,
        name: String?,
        location: String?,
        partial: Boolean,
    ): Boolean {
        val shareCopy = ResultActions.isShareCopy(request.target)
        return Notifications.result(
            context, Notifications.EXPORT_DONE_ID,
            context.getString(if (shareCopy) R.string.export_notif_ready else R.string.export_notif_saved),
            resultText(context, request.target, name, location, partial),
            Notifications.openScreenIntent(context, Notifications.SCREEN_EXPORT),
            ResultActions.notificationActions(context, request.target, request.format),
        )
    }

    private fun foregroundInfo(context: Context, done: Int, total: Int): ForegroundInfo {
        val notification = Notifications.progress(
            context, context.getString(R.string.export_working), done, total,
            tap = Notifications.openScreenIntent(context, Notifications.SCREEN_EXPORT),
            // Cancels this run by id: the same CANCELLED state as the screen's Stop, so the worker deletes its
            // half-written file exactly as it does then (see the class KDoc).
            stop = WorkManager.getInstance(context).createCancelPendingIntent(id),
        )
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifications.EXPORT_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.EXPORT_ID, notification)
        }
    }

    companion object {
        private const val TAG = "ExportWorker"
        const val WORK_NAME = "export-now"

        private const val END_POLLS = 10
        private const val END_POLL_MS = 100L

        /**
         * The destination of a run, carried on the request's tags.
         *
         * `WorkInfo` does not expose the input data, so another run cannot be asked what it is writing. A tag can
         * be read from any `WorkInfo`, which is what [claimedByAnotherRun] needs.
         */
        private const val TARGET_TAG = "export-target:"

        /**
         * Replaces an export that is still running: one at a time, and the newest choice wins. Returns the run's
         * id, so the screen shows the result of the run it started and not a days-old one.
         */
        fun start(context: Context, request: ExportRequest): UUID {
            val work = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(request.toData())
                .addTag(TARGET_TAG + request.target)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, work)
            return work.id
        }

        /** The file a run was writing into, or null for a run that carries no target tag. */
        fun targetOf(info: WorkInfo?): String? =
            info?.tags?.firstOrNull { it.startsWith(TARGET_TAG) }?.substring(TARGET_TAG.length)

        /**
         * True when a run other than [self] is writing, or has written, the same [target].
         *
         * Two exports with the same format and options produce the same name — a "Share" always writes the same
         * path in `cache/exports/`, and "Save to…" can be pointed at the file an earlier export wrote — so a
         * replaced run and its replacement can share a target. The replaced run must not delete the file its
         * replacement is writing. REPLACE removes every older row of the unique name, so any other row seen here
         * is that replacement; one that failed or was cancelled cleans up after itself.
         */
        private fun claimedByAnotherRun(manager: WorkManager, self: UUID, target: String): Boolean =
            runCatching {
                manager.getWorkInfosForUniqueWork(WORK_NAME).get().any {
                    it.id != self && targetOf(it) == target &&
                        it.state != WorkInfo.State.CANCELLED && it.state != WorkInfo.State.FAILED
                }
            }.getOrDefault(true)

        /**
         * Stops the export. The worker removes its own half-written file once its row shows the run has ended
         * (see the class KDoc), so a tap that lands after the export finished — when cancelling is a no-op for
         * WorkManager — deletes nothing and the "Saved" line stays true.
         *
         * One case the worker cannot cover: a run that is *not running* when Stop is tapped (still `ENQUEUED`,
         * typically waiting to be retried after a system stop) has no coroutine to clean up, yet its document
         * already exists — the picker created it, and an earlier attempt may have half-filled it. For that case
         * only, this reads the row before cancelling and, if it was waiting and is `CANCELLED` once the cancel
         * operation has completed, deletes the document. A run that was already `RUNNING` is left to the worker,
         * so the file is never deleted under a writer that is still going. The reads block, so they run on a
         * plain thread rather than in a scope tied to the screen, which the usual "Stop, then Back" would cancel.
         */
        fun cancel(context: Context, run: WorkInfo?) {
            val app = context.applicationContext
            val manager = WorkManager.getInstance(app)
            val id = run?.id
            val target = targetOf(run)
            if (id == null || target == null) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            Thread {
                runCatching {
                    val before = manager.getWorkInfoById(id).get()?.state
                    manager.cancelUniqueWork(WORK_NAME).result.get()
                    val waiting = before == WorkInfo.State.ENQUEUED || before == WorkInfo.State.BLOCKED
                    val after = manager.getWorkInfoById(id).get()?.state
                    if (waiting && after == WorkInfo.State.CANCELLED && !claimedByAnotherRun(manager, id, target)) {
                        discardPartial(app, target)
                        (app as DoorprintsApp).appScope.launch { ExportGrants.release(app, target) }
                    }
                }.onFailure {
                    // The cancel itself must still happen if a read failed before it.
                    manager.cancelUniqueWork(WORK_NAME)
                }
            }.start()
        }

        /**
         * Removes the half-written file a failed or abandoned export left behind, so "Downloads" never holds a
         * truncated `Doorprints-2026-09-22.zip` that looks saved. Best effort and idempotent: a provider may
         * refuse the delete, the file may already be gone, and neither must turn into a second failure.
         */
        fun discardPartial(context: Context, target: String) {
            runCatching {
                if (target.startsWith("content://")) {
                    Saf.delete(context, Uri.parse(target))
                } else {
                    File(target).delete()
                }
            }
        }

        /** The current or last export, for the screen to show. */
        fun observe(context: Context): Flow<List<WorkInfo>> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)

        fun progressOf(data: Data): Pair<Int, Int> =
            data.getInt(ExportRequest.KEY_DONE, 0) to data.getInt(ExportRequest.KEY_TOTAL, 0)

        /** What the finished file is called, as the user will see it; null when the provider did not say. */
        fun nameOf(data: Data): String? = data.getString(ExportRequest.KEY_NAME)?.takeIf { it.isNotBlank() }

        /** When a run finished (success or failure), or 0 for a row written by an older build. */
        fun finishedAtOf(data: Data): Long = data.getLong(ExportRequest.KEY_FINISHED_AT, 0L)

        /** Whether anyone was told how the run ended; true for a row written by an older build. */
        fun notifiedOf(data: Data): Boolean = data.getBoolean(ExportRequest.KEY_NOTIFIED, true)

        /**
         * The success sentence, for the result card and the notification alike (UX review, round 11): "Saved to
         * Download: Doorprints-2026-09-22.html" where the provider says where, "Saved: …" where it does not, "Ready
         * to share: …" for a Share copy, and "Saved a partial backup…" for a full backup made with options that leave
         * something out, so neither place calls that file complete.
         */
        fun resultText(context: Context, target: String?, name: String?, location: String?, partial: Boolean): String {
            val shareCopy = target != null && ResultActions.isShareCopy(target)
            return when {
                // A copy in the app's private cache is not "saved" anywhere the user can reach.
                shareCopy -> context.getString(
                    if (partial) R.string.export_ready_partial else R.string.export_ready,
                    name ?: File(target!!).name,
                )
                name == null -> context.getString(
                    if (partial) R.string.export_done_partial_plain else R.string.export_done_plain,
                )
                location != null -> context.getString(
                    if (partial) R.string.export_done_partial_in else R.string.export_done_in, location, name,
                )
                else -> context.getString(if (partial) R.string.export_done_partial else R.string.export_done, name)
            }
        }

        /** The format an export was for, so the screen can offer "Share" with the right media type. */
        fun formatOf(data: Data): ExportFormat? =
            ExportFormat.entries.firstOrNull { it.name == data.getString(ExportRequest.KEY_FORMAT) }

        /** Why an export failed, from its output data. */
        fun problemOf(data: Data?): ExportProblem = ExportProblem.fromCode(data?.getString(ExportRequest.KEY_ERROR))
    }
}
