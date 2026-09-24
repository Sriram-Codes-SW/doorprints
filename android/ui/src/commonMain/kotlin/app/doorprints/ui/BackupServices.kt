package app.doorprints.ui

import androidx.compose.runtime.Composable
import app.doorprints.export.ExportProblem
import app.doorprints.export.ImportCheck
import app.doorprints.export.ImportRequest
import app.doorprints.export.ImportStaging
import app.doorprints.export.ImportStart
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.ExportFormat
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.ImportMode
import kotlinx.coroutines.flow.Flow

/*
 * The Export and Import screens' seams (ADR-23 CMP-6 P6b): the copies are made and read by background work the
 * platform runs (Android: WorkManager's ExportWorker and ImportWorker, the Storage Access Framework, the app's
 * FileProvider and notifications, all in `:app`), and the screens see each run as an [ExportRun] or [ImportRun].
 */

/** Where a background run is (Android: WorkManager's `WorkInfo.State`, one to one). */
enum class RunState {
    ENQUEUED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED;

    /** Ended, one way or another: nothing more will happen to the run. */
    val isFinished: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

/**
 * An export run as the Export screen shows it: its progress while it runs ([done] of [total]) and what it reported when
 * it ended. Android: read from the run's `WorkInfo` (tags, progress and output data).
 */
data class ExportRun(
    val id: String,
    val state: RunState,
    val done: Int = 0,
    val total: Int = 0,
    /** The file the run writes, from the request (for Stop); null for a run started by an older build. */
    val target: String? = null,
    /** The file the run wrote, once it succeeded: a `content://` document, or a share copy's path in the cache. */
    val written: String? = null,
    /** The format it was made in. */
    val format: ExportFormat? = null,
    /** The finished file's name as the user will see it; null when the storage did not say. */
    val name: String? = null,
    /** The folder or storage the document went to ("Download"), when the storage says. */
    val location: String? = null,
    /** A full backup made with options that leave something out. */
    val partial: Boolean = false,
    /** Why it failed. */
    val problem: ExportProblem = ExportProblem.UNKNOWN,
    /** When it ended (wall clock), or 0. */
    val finishedAt: Long = 0L,
    /** Whether anyone was told how it ended (its screen, or a notification); true when unknown. */
    val notified: Boolean = true,
)

/** An import run as the Import screen shows it; see [ExportRun]. */
data class ImportRun(
    val id: String,
    val state: RunState,
    val done: Int = 0,
    val total: Int = 0,
    /** The mode it was started with; null for a run started before the mode was recorded. */
    val mode: ImportMode? = null,
    /** Rows written, new and updated together, and of them the updates and the houses brought back. */
    val houses: Int = 0,
    val visits: Int = 0,
    val photos: Int = 0,
    val updatedHouses: Int = 0,
    val updatedVisits: Int = 0,
    val restoredHouses: Int = 0,
    /** Photos in the file that could not be restored. */
    val photosSkipped: Int = 0,
    /** A copy import whose undo record was written: the screen may offer *Undo this import*. */
    val undoable: Boolean = false,
    /** Why it failed. */
    val problem: BackupProblem = BackupProblem.NOT_A_BACKUP,
    val finishedAt: Long = 0L,
    val notified: Boolean = true,
)

/** What the Export screen ("Save a copy") needs from the app. Android: `AndroidExportServices` in `:app`. */
interface ExportServices {
    /** The current or last export, for the screen to show (Android: the unique work's rows; normally one). */
    fun runs(): Flow<List<ExportRun>>

    /**
     * Starts an export of [format] into [target] (a document picked with [rememberSaveToPicker], or a share copy's
     * [shareCopyTarget]); an export still running is replaced. Returns the run's id.
     */
    fun start(format: ExportFormat, target: String, options: ExportOptions): String

    /** Stops the export [run] (null: whatever export runs); the worker removes its half-written file. */
    fun stop(run: ExportRun?)

    /** Where a *Share* export writes [fileName]: a private cache file the share sheet can read. */
    fun shareCopyTarget(fileName: String): String

    /** Removes share copies older than a day, so an old export is not left readable in the cache. */
    fun cleanShareCopies()

    /** Takes down the "Your copy is saved" notification: the screen shows the result itself. */
    fun clearDoneNotification()

    /** The screen is on screen (started): a run that ends now is shown here, not only in a notification. */
    fun screenVisible(visible: Boolean)

    /**
     * Returns the function that gives the options the screen starts from, read at each call: this phone's current UTC
     * offset and time, the app's language, everything included.
     */
    @Composable
    fun rememberDefaultOptions(): () -> ExportOptions

    /**
     * Returns the function that opens the system's "Save as" picker with a media type and a suggested name, or
     * returns false when there is no picker. [onPicked] gets the chosen document (a grant on it is kept first, so the
     * run can still write it after the screen is gone), or null when the user backed out.
     */
    @Composable
    fun rememberSaveToPicker(onPicked: (target: String?) -> Unit): (mimeType: String, fileName: String) -> Boolean

    /** Open and *Share this file* for a finished copy. */
    @Composable
    fun rememberFileActions(): ExportFileActions
}

/** Hands a finished copy to another app; each returns false when no app can take it. */
interface ExportFileActions {
    fun open(target: String, format: ExportFormat): Boolean
    fun share(target: String, format: ExportFormat): Boolean
}

/**
 * What the Import screen ("Import a backup") and [ImportViewModel] need from the app: the picker, the file's name,
 * staging and previewing it, and the background import. Android: `AndroidImportServices` in `:app`.
 */
interface ImportServices {
    /** The current or last import, for the screen to show. */
    fun runs(): Flow<List<ImportRun>>

    /** Stops the running import: a merge keeps what it wrote (the same file again finishes it); a copy rolls back. */
    fun stop()

    /** Takes down the "Import finished" notification: the screen shows the result itself. */
    fun clearDoneNotification()

    /** The screen is on screen (started); see [ExportServices.screenVisible]. */
    fun screenVisible(visible: Boolean)

    /**
     * Returns the function that opens the system's file picker for a backup, at the weekly backup [folder] when one is
     * set (the settings' value), or returns false when there is no picker. [onPicked] gets the file, or null.
     */
    @Composable
    fun rememberBackupPicker(onPicked: (file: String?) -> Unit): (folder: String?) -> Boolean

    /** The picked [file]'s name as its storage shows it, or null. */
    suspend fun displayName(file: String): String?

    /** Copies the picked [file] into the cache (cancellable), or says why not. */
    suspend fun stage(file: String): ImportStaging

    /** Checks a staged copy and works out what each mode would do; nothing is written. */
    suspend fun preview(stagedPath: String, displayName: String?): ImportCheck

    /** True while the staged copy at [path] is still there. */
    fun isStaged(path: String): Boolean

    /** Deletes a staged copy nobody will import; nothing for null or a copy already gone. */
    fun discard(path: String?)

    /** Hands a staged copy to the background import (at most one runs at a time). */
    fun start(request: ImportRequest): ImportStart
}
