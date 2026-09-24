package app.doorprints.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.WorkInfo
import app.doorprints.DoorprintsApp
import app.doorprints.Notifications
import app.doorprints.shared.export.ExportFormat
import app.doorprints.shared.export.ExportOptions
import app.doorprints.ui.ExportFileActions
import app.doorprints.ui.ExportRun
import app.doorprints.ui.ExportServices
import app.doorprints.ui.ImportRun
import app.doorprints.ui.ImportServices
import app.doorprints.ui.RunState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/*
 * The Export and Import screens' seams on Android (ADR-23 CMP-6 P6b): WorkManager's ExportWorker and ImportWorker, the
 * Storage Access Framework pickers and grants, the app's FileProvider and the result notifications, with the code the
 * two screens ran before they moved to `:ui`. One of each per process (AndroidAppServices); what needs the activity
 * (the pickers, the share sheet, the options' language) reads it from the composition.
 */

/** A run's state as the screens see it: WorkManager's, one to one. */
internal fun runStateOf(state: WorkInfo.State): RunState = when (state) {
    WorkInfo.State.ENQUEUED -> RunState.ENQUEUED
    WorkInfo.State.RUNNING -> RunState.RUNNING
    WorkInfo.State.SUCCEEDED -> RunState.SUCCEEDED
    WorkInfo.State.FAILED -> RunState.FAILED
    WorkInfo.State.BLOCKED -> RunState.BLOCKED
    WorkInfo.State.CANCELLED -> RunState.CANCELLED
}

/** An export run for the Export screen, read from [info]'s tags, progress and output data as the screen read them. */
internal fun exportRunOf(info: WorkInfo): ExportRun {
    val output = info.outputData
    val (done, total) = ExportWorker.progressOf(info.progress)
    return ExportRun(
        id = info.id.toString(),
        state = runStateOf(info.state),
        done = done,
        total = total,
        target = ExportWorker.targetOf(info),
        written = output.getString(ExportRequest.KEY_WRITTEN),
        format = ExportWorker.formatOf(output),
        name = ExportWorker.nameOf(output),
        location = output.getString(ExportRequest.KEY_LOCATION),
        partial = output.getBoolean(ExportRequest.KEY_PARTIAL, false),
        problem = ExportWorker.problemOf(output),
        finishedAt = ExportWorker.finishedAtOf(output),
        notified = ExportWorker.notifiedOf(output),
    )
}

/** An import run for the Import screen; see [exportRunOf]. */
internal fun importRunOf(info: WorkInfo): ImportRun {
    val output: Data = info.outputData
    val (done, total) = ExportWorker.progressOf(info.progress)
    return ImportRun(
        id = info.id.toString(),
        state = runStateOf(info.state),
        done = done,
        total = total,
        mode = ImportWorker.modeOf(info),
        houses = output.getInt(ImportWorker.KEY_HOUSES, 0),
        visits = output.getInt(ImportWorker.KEY_VISITS, 0),
        photos = output.getInt(ImportWorker.KEY_PHOTOS, 0),
        updatedHouses = output.getInt(ImportWorker.KEY_UPDATED_HOUSES, 0),
        updatedVisits = output.getInt(ImportWorker.KEY_UPDATED_VISITS, 0),
        restoredHouses = output.getInt(ImportWorker.KEY_RESTORED_HOUSES, 0),
        photosSkipped = output.getInt(ImportWorker.KEY_PHOTOS_SKIPPED, 0),
        undoable = output.getBoolean(ImportWorker.KEY_UNDOABLE, false),
        problem = backupProblemOf(output.getString(ExportRequest.KEY_ERROR)),
        finishedAt = output.getLong(ExportRequest.KEY_FINISHED_AT, 0L),
        notified = ExportWorker.notifiedOf(output),
    )
}

/** Where *Share* exports are written: `cache/exports/`, which `file_paths.xml` exposes through the FileProvider. */
internal fun sharedExportDir(context: Context): File =
    File(context.cacheDir, "exports").apply { mkdirs() }

/** [ExportServices] on Android: `ExportWorker`, `CreateExportDocument` and `ExportGrants`, `ResultActions`. */
class AndroidExportServices(private val app: DoorprintsApp) : ExportServices {
    override fun runs(): Flow<List<ExportRun>> = ExportWorker.observe(app).map { list -> list.map(::exportRunOf) }

    override fun start(format: ExportFormat, target: String, options: ExportOptions): String =
        ExportWorker.start(app, ExportRequest(format, target, options)).toString()

    /** See [ExportWorker.cancel] for why the screen does not delete anything itself. */
    override fun stop(run: ExportRun?) =
        ExportWorker.cancel(app, run?.id?.let(UUID::fromString), run?.target)

    override fun shareCopyTarget(fileName: String): String = File(sharedExportDir(app), fileName).absolutePath

    /** Files shared earlier are removed after a day (docs/11 section 5.2); this is the only place they pile up. */
    override fun cleanShareCopies() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        sharedExportDir(app).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /** The "Your copy is saved" notification, posted if the run ended while the screen was in the background. */
    override fun clearDoneNotification() {
        NotificationManagerCompat.from(app).cancel(Notifications.EXPORT_DONE_ID)
    }

    /** While this is true a finished export is shown on the screen; while it is false the worker posts a notification. */
    override fun screenVisible(visible: Boolean) {
        ScreenWatch.exportScreen = visible
    }

    /** From the composition's context, as the screen read `ExportBuilder.defaults(context)` before. */
    @Composable
    override fun rememberDefaultOptions(): () -> ExportOptions {
        val context = LocalContext.current
        return remember(context) { { ExportBuilder.defaults(context) } }
    }

    @Composable
    override fun rememberSaveToPicker(onPicked: (target: String?) -> Unit): (String, String) -> Boolean {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(CreateExportDocument()) { uri ->
            if (uri != null) {
                // Before the run starts, while this activity still holds the picker's grant: that grant ends with the
                // activity, and the export is built to outlive it (Stop's delete, a retry, the notification's Open and
                // Share). Recorded off the main thread in the app's scope, which leaving the screen does not cancel;
                // older grants beyond ExportGrants.KEPT are released there.
                ExportGrants.take(context, uri)
                app.appScope.launch { ExportGrants.hold(app, uri.toString()) }
            }
            onPicked(uri?.toString())
        }
        return remember(launcher) {
            { mimeType, fileName ->
                try {
                    launcher.launch(CreateExportDocument.Request(mimeType, fileName))
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }
        }
    }

    @Composable
    override fun rememberFileActions(): ExportFileActions {
        val context = LocalContext.current
        return remember(context) {
            object : ExportFileActions {
                override fun open(target: String, format: ExportFormat) = openTarget(context, target, format)
                override fun share(target: String, format: ExportFormat) = shareTarget(context, target, format)
            }
        }
    }
}

/**
 * The share sheet for a finished copy: the `content://` document the user saved, or the cache file behind the
 * app's `FileProvider` (`file_paths.xml`), so the receiving app gets a one-off read grant and no storage
 * permission is involved. False when nothing can take it (the caller shows `export_share_failed`).
 */
private fun shareTarget(context: Context, target: String, format: ExportFormat): Boolean {
    val uri = ResultActions.readableUri(context, target) ?: return false
    return try {
        context.startActivity(ResultActions.share(context, uri, format))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** Opens a saved copy in the app that handles its format; false when there is none (`export_open_failed`). */
private fun openTarget(context: Context, target: String, format: ExportFormat): Boolean {
    val uri = ResultActions.readableUri(context, target) ?: return false
    return try {
        context.startActivity(ResultActions.view(uri, format))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** [ImportServices] on Android: `ImportWorker`, `OpenBackupDocument`, `Imports` (staging and preview). */
class AndroidImportServices(private val app: DoorprintsApp) : ImportServices {
    private val repository get() = app.container.repository

    override fun runs(): Flow<List<ImportRun>> = ImportWorker.observe(app).map { list -> list.map(::importRunOf) }

    override fun stop() = ImportWorker.cancel(app)

    /** The "Import finished" notification, posted if the run ended while the screen was in the background. */
    override fun clearDoneNotification() {
        NotificationManagerCompat.from(app).cancel(Notifications.IMPORT_DONE_ID)
    }

    /** While this is true a finished import is shown on the screen; while it is false the worker posts a notification. */
    override fun screenVisible(visible: Boolean) {
        ScreenWatch.importScreen = visible
    }

    /**
     * The picker opens at the weekly backup [folder] when one is set: a document URI inside the granted tree
     * ([Saf.treeRoot]; see [OpenBackupDocument]).
     */
    @Composable
    override fun rememberBackupPicker(onPicked: (file: String?) -> Unit): (folder: String?) -> Boolean {
        val launcher = rememberLauncherForActivityResult(OpenBackupDocument()) { uri -> onPicked(uri?.toString()) }
        return remember(launcher) {
            { folder ->
                val start = folder?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Saf.treeRoot(Uri.parse(it)) }.getOrNull() }
                try {
                    launcher.launch(start)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }
        }
    }

    override suspend fun displayName(file: String): String? = Imports.displayName(app, Uri.parse(file))

    override suspend fun stage(file: String) = Imports.stage(app, Uri.parse(file))

    override suspend fun preview(stagedPath: String, displayName: String?) =
        Imports.preview(repository, stagedPath, displayName)

    override fun isStaged(path: String): Boolean = File(path).isFile

    override fun discard(path: String?) = Imports.discard(path)

    override fun start(request: ImportRequest): ImportStart = ImportWorker.start(app, request)
}
