package app.doorprints.export

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.work.*
import app.doorprints.DoorprintsApp
import app.doorprints.Notifications
import app.doorprints.R
import app.doorprints.i18n.AppLocale
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.export.ImportPlan
import app.doorprints.ui.joinList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID

/**
 * Writes an already previewed and confirmed import (S4-04).
 *
 * The archive is validated a second time here, on purpose: the preview happened in another process lifetime, and
 * a background job must never trust a path handed to it.
 *
 * What an interruption leaves depends on the mode (see `Repository.applyImport`): a **merge** writes rows one at a
 * time, so it leaves what it managed and importing the same file again finishes the job; a **copy** is all or
 * nothing, because its rows get new ids on every run and "import it again" would add them twice. The mode travels
 * as a tag on the work request ([modeOf]), because a cancelled run has no output data, and the screen needs it to
 * say which of the two happened.
 */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val request = ImportRequest.fromData(inputData) ?: return@coroutineScope Result.failure()
        val repository = (applicationContext as DoorprintsApp).container.repository
        val localised = AppLocale.wrap(applicationContext)
        val staged = File(request.stagedPath)

        try {
            when (val opened = BackupReader.open(staged)) {
                is BackupOpen.Failed -> {
                    staged.delete()
                    val notified = notifyIfUnseen(localised, opened.problem)
                    Result.failure(
                        workDataOf(
                            ExportRequest.KEY_ERROR to opened.problem.name,
                            ExportRequest.KEY_NOTIFIED to notified,
                            ExportRequest.KEY_FINISHED_AT to System.currentTimeMillis(),
                        )
                    )
                }

                is BackupOpen.Ok -> opened.reader.use { reader ->
                    val local = repository.localVersions()
                    val actions = ImportPlan.plan(
                        data = reader.data,
                        localHouses = local.houses,
                        localVisits = local.visits,
                        localPhotoIds = local.photoIds,
                        photoEntriesInZip = reader.photoEntries,
                        mode = request.mode,
                        newId = { UUID.randomUUID().toString() },
                        locallyDeletedHouseIds = local.deletedHouseIds,
                        // The same flags the preview the user confirmed was made with (Imports.preview).
                        restoreDeleted = request.restoreDeleted,
                        skipUpdates = request.skipUpdates,
                        localUnlinkedVisitIds = local.unlinkedVisitIds,
                        syncedDeletedHouseIds = local.syncedDeletedHouseIds,
                    )
                    val heavy = actions.photos.size >= FOREGROUND_PHOTO_THRESHOLD
                    if (heavy) runCatching { setForeground(foregroundInfo(localised, 0, 0)) }

                    val progress = MutableStateFlow(0 to 0)
                    // Shared with ExportWorker: the progress bar every 400 ms, the foreground notification at most
                    // once a second and only when the percentage moves (NotificationManager drops rapid updates).
                    val reporter = reportProgress(
                        progress = progress,
                        foreground = heavy,
                        publish = { done, total ->
                            setProgress(workDataOf(ExportRequest.KEY_DONE to done, ExportRequest.KEY_TOTAL to total))
                        },
                        promote = { done, total -> setForeground(foregroundInfo(localised, done, total)) },
                    )
                    val result = try {
                        repository.applyImport(
                            actions = actions,
                            onProgress = { done, total ->
                                // The stop point for the notification's and the screen's Stop. Merge: rows
                                // already written stay (each is idempotent), and importing the same file again
                                // finishes. Copy: the transaction is rolled back and nothing stays.
                                ensureActive()
                                progress.value = done to total
                            },
                        ) { entry -> reader.photoBytes(entry) }
                    } finally {
                        reporter.cancel()
                    }
                    staged.delete()
                    // A copy can be undone for a day (UX review, round 16): its new ids go to a small private file,
                    // not to the output Data, which is capped at 10 KB. If the file cannot be written the import
                    // still stands; the screen then simply offers no undo.
                    val undoable = request.mode == ImportMode.COPY &&
                        (result.copiedHouses.isNotEmpty() || result.copiedVisits.isNotEmpty()) &&
                        ImportUndo.save(
                            applicationContext,
                            CopyRecord(
                                runId = id.toString(),
                                finishedAt = System.currentTimeMillis(),
                                houses = result.copiedHouses,
                                visits = result.copiedVisits,
                                photos = result.copiedPhotos,
                            ),
                        )
                    // Told = seen on the Import screen, or a notification that was really posted.
                    val notified = ScreenWatch.importScreen || Notifications.result(
                        localised, Notifications.IMPORT_DONE_ID,
                        localised.getString(R.string.import_notif_done),
                        importedText(
                            localised, result.houses, result.visits, result.photos,
                            result.updatedHouses, result.updatedVisits, result.restoredHouses,
                        ),
                        Notifications.openScreenIntent(localised, Notifications.SCREEN_IMPORT),
                    )
                    Result.success(
                        workDataOf(
                            KEY_ROWS to result.rows,
                            KEY_HOUSES to result.houses,
                            KEY_VISITS to result.visits,
                            KEY_PHOTOS to result.photos,
                            KEY_PHOTOS_SKIPPED to result.photosSkipped,
                            KEY_UPDATED_HOUSES to result.updatedHouses,
                            KEY_UPDATED_VISITS to result.updatedVisits,
                            KEY_RESTORED_HOUSES to result.restoredHouses,
                            KEY_UNDOABLE to undoable,
                            ExportRequest.KEY_NOTIFIED to notified,
                            ExportRequest.KEY_FINISHED_AT to System.currentTimeMillis(),
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            // Deliberately *not* deleting the staged copy: WorkManager runs a stopped job again, and it would
            // have nothing to read. `Imports.cleanOldStaging` sweeps anything left behind after a few hours.
            throw e
        } catch (e: Exception) {
            staged.delete()
            // A [BackupProblem] name, never `e.message`: `ImportScreen.problemOf` maps anything it does not
            // recognise to NOT_A_BACKUP, so putting an exception message here told a user whose disk was full
            // that their perfectly good backup "is not a Doorprints backup" — the wrong fix entirely. Everything
            // that reaches here is a failure to *write* (photo bytes, or a row into SQLite); the reason the user
            // can act on goes in the Data, and the detail goes to logcat.
            Log.w(TAG, "Import failed while writing", e)
            val notified = notifyIfUnseen(localised, BackupProblem.WRITE_FAILED, request.mode)
            Result.failure(
                workDataOf(
                    ExportRequest.KEY_ERROR to BackupProblem.WRITE_FAILED.name,
                    ExportRequest.KEY_NOTIFIED to notified,
                    ExportRequest.KEY_FINISHED_AT to System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * A failure nobody saw on screen is posted, with the same translated reason the screen would show. Returns
     * whether anyone was told: the screen was showing, or the notification was really posted.
     */
    private fun notifyIfUnseen(context: Context, problem: BackupProblem, mode: ImportMode? = null): Boolean {
        if (ScreenWatch.importScreen) return true
        return Notifications.result(
            context, Notifications.IMPORT_DONE_ID,
            context.getString(R.string.import_notif_failed),
            if (problem == BackupProblem.WRITE_FAILED) {
                context.getString(writeFailedRes(mode))
            } else {
                context.getString(R.string.import_failed, context.getString(problem.messageRes()))
            },
            Notifications.openScreenIntent(context, Notifications.SCREEN_IMPORT),
        )
    }

    private fun foregroundInfo(context: Context, done: Int, total: Int): ForegroundInfo {
        val notification = Notifications.progress(
            context, context.getString(R.string.import_working), done, total,
            tap = Notifications.openScreenIntent(context, Notifications.SCREEN_IMPORT),
            stop = WorkManager.getInstance(context).createCancelPendingIntent(id),
        )
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifications.IMPORT_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.IMPORT_ID, notification)
        }
    }

    companion object {
        private const val TAG = "ImportWorker"
        const val WORK_NAME = "import-now"

        /** How many rows the import wrote, in the worker's output data; and the same per type. */
        const val KEY_ROWS = "rows"
        const val KEY_HOUSES = "houses"
        const val KEY_VISITS = "visits"
        const val KEY_PHOTOS = "photos"

        /** Of [KEY_HOUSES] and [KEY_VISITS], how many replaced a row already on the phone (MERGE only). */
        const val KEY_UPDATED_HOUSES = "updatedHouses"
        const val KEY_UPDATED_VISITS = "updatedVisits"

        /** Of [KEY_HOUSES], how many were houses deleted on this phone and brought back (MERGE with restore only). */
        const val KEY_RESTORED_HOUSES = "restoredHouses"

        /**
         * Photos the import could not restore: absent from the ZIP, unreadable, failing the manifest's SHA-256, or
         * belonging to a house deleted on this phone after the preview. A damaged backup must not report a clean
         * import, so the screen shows this as its own line.
         */
        const val KEY_PHOTOS_SKIPPED = "photosSkipped"

        /** COPY only: true when the run's [CopyRecord] was written, so the screen may offer *Undo this import*. */
        const val KEY_UNDOABLE = "undoable"

        /**
         * KEEP, not REPLACE: an import that is already writing rows must never be cut in half by a second tap.
         * Returns the request's id, which is the run's id unless an import was already ENQUEUED or RUNNING: then
         * KEEP drops the request, no run with that id is ever reported, and [ImportStart.queued] says `false`.
         */
        fun start(context: Context, request: ImportRequest): ImportStart {
            val work = OneTimeWorkRequestBuilder<ImportWorker>()
                .setInputData(request.toData())
                .addTag(modeTag(request.mode))
                .build()
            val manager = WorkManager.getInstance(context)
            val operation = manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, work)
            return ImportStart(work.id.toString()) {
                // The enqueue is asynchronous. Waiting for it first means the lookup below cannot run before the
                // row is written and mistake a request that is still being queued for one KEEP dropped.
                operation.await()
                manager.getWorkInfoByIdFlow(work.id).first() != null
            }
        }

        /**
         * Stops a running import. A merge keeps the rows it already wrote, and importing the same file again
         * finishes it; a copy is rolled back and leaves nothing.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * "Added 2 houses and 20 photos. Updated 3 houses.", in the app's language, for the notification (the Import
         * screen's copy is `importedText` in `:ui`, CMP-6; UX review, 2026-09-22). Built from the non-zero parts only, so it never says "0 houses", and
         * in the preview's own words: a merge's rows that replaced one on the phone are *updated*, not "imported".
         * [houses] and [visits] are everything written; [updatedHouses] and [updatedVisits] the part of them that
         * were updates; [restoredHouses] the part of [houses] that were deleted on this phone and are back ("Brought
         * back 3 houses.", UX review round 11).
         */
        fun importedText(
            context: Context,
            houses: Int,
            visits: Int,
            photos: Int,
            updatedHouses: Int = 0,
            updatedVisits: Int = 0,
            restoredHouses: Int = 0,
        ): String {
            val r = context.resources
            fun parts(vararg counts: Pair<Int, Int>): List<String> =
                counts.filter { it.second > 0 }.map { (plural, n) -> r.getQuantityString(plural, n, n) }
            val added = parts(
                R.plurals.count_houses to (houses - updatedHouses - restoredHouses).coerceAtLeast(0),
                R.plurals.count_visits to (visits - updatedVisits).coerceAtLeast(0),
                R.plurals.count_photos to photos,
            )
            val updated = parts(R.plurals.count_houses to updatedHouses, R.plurals.count_visits to updatedVisits)
            val sentences = buildList {
                if (restoredHouses > 0) {
                    add(r.getQuantityString(R.plurals.import_restored_result, restoredHouses, restoredHouses))
                }
                if (added.isNotEmpty()) add(context.getString(R.string.import_added, joined(context, added)))
                if (updated.isNotEmpty()) add(context.getString(R.string.import_updated, joined(context, updated)))
            }
            return if (sentences.isEmpty()) context.getString(R.string.import_done_nothing) else sentences.joinToString(" ")
        }

        /**
         * "a", "a and b", "a, b and c", "a, b, c and d", … with each language's own list pattern, for the
         * notification's sentence. Any number of items ([joinList], common since CMP-6; the screens use `joinedList`
         * with the same patterns as Compose resources).
         */
        fun joined(context: Context, items: List<String>): String = joinList(
            items,
            two = { a, b -> context.getString(R.string.import_list_two, a, b) },
            three = { a, b, c -> context.getString(R.string.import_list_three, a, b, c) },
            middle = { a, b -> context.getString(R.string.import_list_middle, a, b) },
        )

        /** Prefix of the tag that records a run's [ImportMode]; tags survive a cancelled run, output data does not. */
        private const val MODE_TAG = "import-mode:"

        /** The tag [start] puts on a run of [mode]. */
        internal fun modeTag(mode: ImportMode): String = MODE_TAG + mode.name

        /** The mode a run was started with, or null for a run started before the tag existed. */
        fun modeOf(info: WorkInfo): ImportMode? = info.tags.firstNotNullOfOrNull { tag ->
            if (!tag.startsWith(MODE_TAG)) return@firstNotNullOfOrNull null
            ImportMode.entries.firstOrNull { it.name == tag.removePrefix(MODE_TAG) }
        }

        /**
         * "The import stopped part-way": a merge says what finishes it (importing the same file again, which is
         * idempotent); a copy says nothing was added, because it is rolled back — and never tells the user to import
         * again "to finish", which would add every house a second time. A run of unknown mode (started before this
         * tag existed) gets the merge text, as before. The notification's; the screen's is `importWriteFailedResource`.
         */
        @StringRes
        fun writeFailedRes(mode: ImportMode?): Int =
            if (mode == ImportMode.COPY) R.string.import_write_failed_copy else R.string.import_write_failed

        fun observe(context: Context): Flow<List<WorkInfo>> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)
    }
}
