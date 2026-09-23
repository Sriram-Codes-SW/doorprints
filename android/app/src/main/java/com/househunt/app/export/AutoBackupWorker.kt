package com.househunt.app.export

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.work.*
import com.househunt.app.HouseHuntApp
import com.househunt.app.Notifications
import com.househunt.app.R
import com.househunt.app.data.AppSettings
import com.househunt.app.i18n.AppLocale
import com.househunt.shared.export.ExportFormat
import com.househunt.shared.export.PhotoScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.util.concurrent.TimeUnit

/**
 * The optional weekly backup (S4-07, docs/11 section 5.2: "weekly JSON backup, only while charging, to a folder
 * picked once, keeping the last 4").
 *
 * It writes the same `doorprints-backup/1` ZIP the Export screen writes, into the folder the user granted once
 * with `OpenDocumentTree`, and then deletes everything but the newest *N*. It is off until the user picks that
 * folder — the app never writes anywhere the user has not chosen.
 *
 * Constraints: **charging** and **battery not low** — exactly what docs/11 section 5.2 asks for, and nothing
 * more. An earlier draft also required an unmetered network, reasoning ahead to S5-10 (the same file to Google
 * Drive). That was wrong for this story: the backup is written to a local SAF folder and needs no network at
 * all, so a user who is never on Wi-Fi would simply never get a backup, and nothing on the Settings screen would
 * say why — a silent non-failure, which is the worst kind. When S5-10 adds an upload, the network constraint
 * belongs on *that* work, not on writing a file to the user's own storage.
 *
 * **Only finished backups may ever count as backups.** This job is stopped part-way more often than any other in
 * the app: unplugging the phone ends the charging constraint, and a large photo backup can reach JobScheduler's
 * execution limit. Every run creates a new document and a retry never reuses it, so a truncated ZIP left behind
 * would sit in the folder under a plausible name, and retention — "keep the newest N" — would count it and delete
 * a good backup to make room. After a few unplugged nights a user who asked for four backups could have four
 * unopenable files while Settings said the last backup worked. Three things prevent that:
 *
 *  1. The document is created as `partial-Doorprints-backup-….zip` ([partialName]) and renamed to its real name
 *     only after the ZIP has been completely written and closed. [isFinishedBackup] — the only names retention
 *     looks at — never matches a partial name, so even a file left by a process that was killed outright cannot
 *     take a retention slot. A provider that does not advertise `FLAG_SUPPORTS_RENAME` gets the final name up
 *     front instead, and relies on point 2 alone.
 *  2. On every path except success — stopped, failed, or retried — the run deletes the document it created,
 *     under `NonCancellable` so the delete happens even though the coroutine has been cancelled.
 *  3. Writing is cooperative (`ensureActive()` in the progress callback), so a stop ends the write at the next
 *     photo instead of after the whole archive, and with [FOREGROUND_PHOTO_THRESHOLD] photos or more the job asks
 *     to run in the foreground, which lifts the execution limit. The promotion is best effort (Android 12+ may
 *     refuse it for a job that started in the background); points 1 and 2 are what make a refusal harmless.
 *
 * Failures are recorded as an [ExportProblem] code, never `e.message`: Settings shows the reason translated, and
 * the exception goes to logcat. Two outcomes are also posted as a notification that opens Settings, because a user
 * who never opens Settings would otherwise believe for months that backups are still being made: the folder being
 * gone (the backup then turns itself off) and the final failed attempt of a run.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = (applicationContext as HouseHuntApp).container.settings
        val settings = store.current()
        return try {
            backUp(settings)
        } finally {
            // The folder this run wrote to may have been dropped while it ran (the switch turned off, or another
            // folder chosen). Settings leaves that grant to the run, because the run still needed it to delete
            // its unfinished document; give it back now that nothing else will (see [releaseFolder]).
            withContext(NonCancellable) {
                val folder = settings.autoBackupFolder
                val now = runCatching { store.current().autoBackupFolder }.getOrNull()
                if (folder.isNotBlank() && now != null && now != folder) Saf.releaseGrant(applicationContext, folder)
            }
        }
    }

    private suspend fun backUp(settings: AppSettings): Result = coroutineScope {
        val repository = (applicationContext as HouseHuntApp).container.repository
        if (!settings.autoBackup || settings.autoBackupFolder.isBlank()) return@coroutineScope Result.success()
        // "Back up now" from Settings: same work, but no retries (the user is watching and can tap again) and a
        // Stop action, which is safe here because cancelling a one-off run does not cancel the weekly schedule.
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val localised = AppLocale.wrap(applicationContext)

        // The document this run created and has not yet finished. Each run owns its own document, so deleting it
        // on any path but success is always right; it is set back to null the moment the file is complete.
        var unfinished: Uri? = null

        try {
            val tree = Uri.parse(settings.autoBackupFolder)
            val options = ExportBuilder.defaults(applicationContext).copy(photos = PhotoScope.ALL)
            val bundle = ExportBuilder.bundle(repository, options)
            val name = ExportFormat.BACKUP.fileName(bundle)
            val mime = ExportFormat.BACKUP.mimeType

            val placeholder = withContext(Dispatchers.IO) {
                Saf.createInTree(applicationContext, tree, mime, partialName(name))
            }
            if (placeholder == null) {
                // The folder was removed, or the grant was revoked in Settings > Apps. Turn the backup off and
                // say so, instead of failing quietly every week for ever.
                repository.settings.saveAutoBackup(false, "", settings.autoBackupKeep)
                repository.settings.saveAutoBackupResult(System.currentTimeMillis(), ERROR_NO_FOLDER)
                // Settings shows this too, but a user who never opens Settings would otherwise believe for months
                // that the weekly backup is still running.
                if (!manual || !ScreenWatch.settingsScreen) {
                    notifyProblem(
                        localised,
                        localised.getString(R.string.auto_backup_stopped_title),
                        localised.getString(R.string.auto_backup_stopped_text),
                    )
                }
                return@coroutineScope Result.failure()
            }
            unfinished = placeholder
            val renameAtEnd = withContext(Dispatchers.IO) { Saf.supportsRename(applicationContext, placeholder) }
            val written: Uri = if (renameAtEnd) {
                placeholder
            } else {
                // Rename unsupported: swap the placeholder for a document with the final name, so a completed
                // backup is never left stranded under a name retention ignores.
                withContext(Dispatchers.IO) { Saf.delete(applicationContext, placeholder) }
                unfinished = null
                val direct = withContext(Dispatchers.IO) {
                    Saf.createInTree(applicationContext, tree, mime, name)
                } ?: error("Cannot create the backup document")
                unfinished = direct
                direct
            }

            val heavy = bundle.photos.size >= FOREGROUND_PHOTO_THRESHOLD
            if (heavy) runCatching { setForeground(foregroundInfo(localised, 0, 0, manual)) }
            val progress = MutableStateFlow(0 to 0)
            val reporter = reportProgress(
                progress = progress,
                foreground = heavy,
                // Nothing observes this job's progress; only the notification moves.
                publish = { _, _ -> },
                promote = { done, total -> setForeground(foregroundInfo(localised, done, total, manual)) },
            )
            try {
                withContext(Dispatchers.IO) {
                    val writing = this
                    BufferedOutputStream(Saf.openOutput(applicationContext, written.toString())).use { out ->
                        Exporters.write(
                            bundle = bundle,
                            format = ExportFormat.BACKUP,
                            out = out,
                            photoFile = { id -> repository.photoFile(id) },
                            appVersion = ExportBuilder.appVersion(applicationContext),
                        ) { done, total ->
                            // The stop point: unplugging the charger ends the run here, at the next photo.
                            writing.ensureActive()
                            progress.value = done to total
                        }
                    }
                }
            } finally {
                reporter.cancel()
            }

            if (renameAtEnd) {
                // Complete and closed: only now may it carry a name retention counts. If a provider that said
                // it could rename refuses after all, keep the complete file under its partial name — honest,
                // and never counted — rather than delete a good backup.
                val renamed = withContext(Dispatchers.IO) { Saf.rename(applicationContext, written, name) }
                if (renamed == null) Log.w(TAG, "Backup written but could not be renamed; kept as partial")
            }
            unfinished = null

            withContext(Dispatchers.IO) {
                Saf.trim(applicationContext, tree, settings.autoBackupKeep) { isFinishedBackup(it) }
            }
            repository.settings.saveAutoBackupResult(System.currentTimeMillis(), "")
            Result.success()
        } catch (e: CancellationException) {
            // Unplugged, the job limit, or the user turned the backup off. A retry makes a new document.
            discard(unfinished)
            throw e
        } catch (e: Exception) {
            discard(unfinished)
            Log.w(TAG, "Automatic backup failed", e)
            val problem = ExportProblem.of(e)
            repository.settings.saveAutoBackupResult(System.currentTimeMillis(), problem.code)
            if (!manual && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                // The last attempt: nothing will try again until next week, so the user has to hear about it.
                if (!manual || !ScreenWatch.settingsScreen) {
                    notifyProblem(
                        localised,
                        localised.getString(R.string.auto_backup_failed_title),
                        // A whole sentence, as ExportWorker's notification: the bare reason is a lowercase fragment.
                        localised.getString(R.string.export_failed, localised.getString(problem.messageRes())),
                    )
                }
                Result.failure()
            }
        }
    }

    /** Posts a backup problem that opens Settings, where the folder can be chosen again. */
    private fun notifyProblem(context: Context, title: String, text: String) {
        Notifications.result(
            context, Notifications.AUTO_BACKUP_PROBLEM_ID, title, text,
            Notifications.openScreenIntent(context, Notifications.SCREEN_SETTINGS),
        )
    }

    /** Deletes a document this run did not finish. NonCancellable: the run may already have been stopped. */
    private suspend fun discard(document: Uri?) {
        if (document == null) return
        withContext(NonCancellable + Dispatchers.IO) { Saf.delete(applicationContext, document) }
    }

    private fun foregroundInfo(context: Context, done: Int, total: Int, manual: Boolean): ForegroundInfo {
        val notification = Notifications.progress(
            context, context.getString(R.string.auto_backup_working), done, total,
            tap = Notifications.openScreenIntent(context, Notifications.SCREEN_SETTINGS),
            // Only for "Back up now": cancelling the weekly run by id would cancel the whole schedule.
            stop = if (manual) WorkManager.getInstance(context).createCancelPendingIntent(id) else null,
        )
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifications.AUTO_BACKUP_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.AUTO_BACKUP_ID, notification)
        }
    }

    companion object {
        private const val TAG = "AutoBackupWorker"
        const val WORK_NAME = "auto-backup"

        /** The one-off "Back up now" run; a separate name, so it never replaces or cancels the weekly schedule. */
        const val NOW_WORK_NAME = "auto-backup-now"
        private const val KEY_MANUAL = "manual"
        const val ERROR_NO_FOLDER = "no-folder"
        const val BACKUP_PREFIX = "Doorprints-backup-"

        /** What an unfinished backup is called until it has been completely written; see the class KDoc. */
        const val PARTIAL_PREFIX = "partial-"
        private const val MAX_ATTEMPTS = 3

        fun partialName(finalName: String): String = PARTIAL_PREFIX + finalName

        /**
         * The files retention counts and trims: finished automatic backups only. A partial name starts with
         * [PARTIAL_PREFIX], not [BACKUP_PREFIX], so it is never one of the "last N" and never pushes a good backup
         * out. Pure, so `SafTrimTest` pins it.
         */
        fun isFinishedBackup(name: String): Boolean = name.startsWith(BACKUP_PREFIX) && name.endsWith(".zip")

        /**
         * "Back up now": one run straight away, with no charging constraint (the user asked for it and is
         * watching). KEEP, so a second tap while one is running does nothing.
         */
        fun runNow(context: Context) {
            val work = OneTimeWorkRequestBuilder<AutoBackupWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.KEEP, work)
        }

        /** The "Back up now" run, for Settings to show while it works. */
        fun observeNow(context: Context): kotlinx.coroutines.flow.Flow<List<WorkInfo>> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW_WORK_NAME)

        /**
         * Gives back the persisted grant on a backup [folder] the settings no longer name (the switch was turned
         * off, or another folder was chosen): the app keeps no access to a folder it will not write to, and
         * persisted grants are capped per app. Call it after saving the new settings and, when turning the backup
         * off, **before** [schedule] cancels the runs — a cancelled run is marked `CANCELLED` at once, while its
         * coroutine is still deleting its unfinished document through this very grant.
         *
         * So a run that is `RUNNING` keeps the grant, and releases it itself when it ends ([doWork]'s `finally`,
         * which sees the folder has changed). Anything else is released now. The reads block; call off the main
         * thread.
         */
        suspend fun releaseFolder(context: Context, folder: String) {
            if (folder.isBlank()) return
            val app = context.applicationContext
            withContext(Dispatchers.IO) {
                val manager = WorkManager.getInstance(app)
                val inUse = runCatching {
                    (manager.getWorkInfosForUniqueWork(WORK_NAME).get() +
                        manager.getWorkInfosForUniqueWork(NOW_WORK_NAME).get())
                        .any { it.state == WorkInfo.State.RUNNING }
                }.getOrDefault(true)
                if (!inUse) Saf.releaseGrant(app, folder)
            }
        }

        /** Turns the weekly job on or off. UPDATE keeps the existing period instead of restarting the clock. */
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) {
                manager.cancelUniqueWork(WORK_NAME)
                manager.cancelUniqueWork(NOW_WORK_NAME)
                return
            }
            // Built here rather than held in a companion property: a property would be initialised whenever the
            // class is loaded, including by the plain-JVM `SafTrimTest`, which only wants [isFinishedBackup].
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            val work = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, work)
        }
    }
}
