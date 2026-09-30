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

package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.doorprints.data.CommonRepository
import app.doorprints.data.toBundle
import app.doorprints.export.ArchiveImports
import app.doorprints.export.ExportProblem
import app.doorprints.export.ImportCheck
import app.doorprints.export.ImportRequest
import app.doorprints.export.ImportStaging
import app.doorprints.export.ImportStart
import app.doorprints.shared.export.ArchiveOpen
import app.doorprints.shared.export.BackupArchive
import app.doorprints.shared.export.BackupCompleteness
import app.doorprints.shared.export.BackupFormat
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.CopyPhotos
import app.doorprints.shared.export.CopyWriter
import app.doorprints.shared.export.ExportFormat
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.PosixFileException
import app.doorprints.shared.export.PosixFileSink
import app.doorprints.shared.export.PosixFileSource
import app.doorprints.shared.export.iosArchiveTools
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorReadingWithoutChanges
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/*
 * *Save a copy* and *Import a backup* on iPhone (S4b-BL-81): the common Export and Import screens on the common ZIP
 * code (`CopyWriter`, `BackupArchive`, `ArchiveImports` in :shared) with the iPhone's files, sheets and pickers. There is
 * no WorkManager: a run is a coroutine in the app's scope, kept going for a while in the background by a UIKit
 * background task, and its state lives for the life of the process. There are no result notifications: a run that ends
 * while its screen is away is shown there next time (`notified` false). No PDF (`PlatformFeatures.pdfCopies`), and a
 * copy import cannot be undone here yet (no undo record).
 *
 * Files: every copy is first written into `tmp/copies/` (private, not backed up). *Share* hands that file to the share
 * sheet; *Save to…* then opens the Files app's picker for it, and the destination it returns is the run's `written`. A
 * picked backup is read once, under its security scope and through a file coordinator (a file in iCloud is downloaded
 * first), into `tmp/copies/imports/`, which the preview and the import read.
 */

/** The folders under `tmp/`, made when first used. */
@OptIn(ExperimentalForeignApi::class)
internal object IosCopyFolders {
    private val root = NSTemporaryDirectory().trimEnd('/') + "/copies"

    /** *Share*: the share sheet's copies. */
    val share: String get() = made("$root/share")

    /** *Save to…*: the copy the Files picker saves from, kept for *Open* and *Share this file*. */
    val save: String get() = made("$root/save")

    /** Staged backups, for the preview and the import. */
    val imports: String get() = made("$root/imports")

    /** A viewing's calendar file (S4b-BL-92a). */
    val calendar: String get() = made("$root/calendar")

    private fun made(path: String): String {
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        return path
    }

    /** Deletes the files in [folder] last changed more than [ageMs] ago. */
    fun sweep(folder: String, ageMs: Long) {
        val manager = NSFileManager.defaultManager
        val cutoff = nowMillis() - ageMs
        manager.contentsOfDirectoryAtPath(folder, error = null)?.forEach { name ->
            val path = "$folder/$name"
            val changed = manager.attributesOfItemAtPath(path, error = null)?.get(NSFileModificationDate) as? NSDate
            if (changed == null || (changed.timeIntervalSince1970 * 1000).toLong() < cutoff) remove(path)
        }
    }

    fun remove(path: String?) {
        if (path.isNullOrEmpty()) return
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    /** True for a plain file name inside [folder]: the paths a screen's saved state hands back are checked, not trusted. */
    fun isIn(folder: String, path: String): Boolean {
        val name = path.removePrefix("$folder/")
        return path.startsWith("$folder/") && name.isNotEmpty() && '/' !in name && name != ".." && name != "."
    }
}

/** The photo files of [repository] for a copy: as stored for a backup, shrunk to 1024 px JPEG q70 for the rest. */
@OptIn(ExperimentalForeignApi::class)
private class IosCopyPhotos(private val repository: CommonRepository) : CopyPhotos {
    override fun original(photoId: String): ByteArray? =
        NSData.dataWithContentsOfFile(repository.photoFileOf(photoId).toString())?.toByteArray()

    override fun dataUri(photoId: String): String? {
        val image = UIImage.imageWithContentsOfFile(repository.photoFileOf(photoId).toString()) ?: return null
        val (width, height) = image.size.useContents { width to height }
        if (width <= 0 || height <= 0) return null
        val scale = minOf(1.0, MAX_EDGE / maxOf(width, height))
        val size = CGSizeMake(maxOf(1.0, width * scale), maxOf(1.0, height * scale))
        // Scale 1: MAX_EDGE pixels, not points; drawing also applies the photo's orientation.
        val format = UIGraphicsImageRendererFormat.preferredFormat().apply { this.scale = 1.0 }
        val shrunk = UIGraphicsImageRenderer(size, format).imageWithActions { _ ->
            image.drawInRect(CGRectMake(0.0, 0.0, size.useContents { this.width }, size.useContents { this.height }))
        }
        val jpeg = UIImageJPEGRepresentation(shrunk, QUALITY) ?: return null
        return "data:image/jpeg;base64," + jpeg.base64EncodedStringWithOptions(0u)
    }

    private companion object {
        const val MAX_EDGE = 1024.0
        const val QUALITY = 0.7
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}

/** `CFBundleShortVersionString`, for the backup's manifest. */
private fun appVersion(): String =
    platform.Foundation.NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""

/**
 * Runs [block] inside a UIKit background task, so a copy or an import that is still going when the person leaves the
 * app gets the few minutes iOS allows to finish; when iOS ends that time, [onExpired] stops the run.
 */
private suspend fun <T> withBackgroundTime(name: String, onExpired: () -> Unit, block: suspend () -> T): T {
    var task: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    withContext(Dispatchers.Main) {
        task = UIApplication.sharedApplication.beginBackgroundTaskWithName(name) {
            onExpired()
            UIApplication.sharedApplication.endBackgroundTask(task)
            task = UIBackgroundTaskInvalid
        }
    }
    try {
        return block()
    } finally {
        withContext(NonCancellable + Dispatchers.Main) {
            if (task != UIBackgroundTaskInvalid) UIApplication.sharedApplication.endBackgroundTask(task)
            task = UIBackgroundTaskInvalid
        }
    }
}

/** Reports progress at most every [PROGRESS_MS], and always the last step, so the bar moves without flooding. */
private class Throttle(private val publish: (Int, Int) -> Unit) {
    private var last = TimeSource.Monotonic.markNow()

    fun report(done: Int, total: Int) {
        if (done < total && last.elapsedNow().inWholeMilliseconds < PROGRESS_MS) return
        last = TimeSource.Monotonic.markNow()
        publish(done, total)
    }

    private companion object {
        const val PROGRESS_MS = 250L
    }
}

/** [ExportServices] on iOS (S4b-BL-81): one run at a time, the newest replacing a running one, as on Android. */
@OptIn(ExperimentalUuidApi::class)
internal class IosExportServices(
    private val repository: CommonRepository,
    private val scope: CoroutineScope,
) : ExportServices {
    private val state = MutableStateFlow<List<ExportRun>>(emptyList())

    /** The running export; main thread. */
    private var job: Job? = null

    @Volatile
    private var visible = false

    /** The copy in `tmp/` behind each saved file's destination, for *Open* and *Share this file*. Main thread. */
    private val savedCopies = mutableMapOf<String, String>()

    override fun runs(): Flow<List<ExportRun>> = state

    override fun start(format: ExportFormat, target: String, options: ExportOptions): String {
        val id = Uuid.random().toString()
        val previous = job
        previous?.cancel()
        state.value = listOf(ExportRun(id, RunState.ENQUEUED, target = target, format = format))
        // After the replaced run has cleaned up: both may write the same file name.
        job = scope.launch(Dispatchers.Default) {
            previous?.join()
            export(id, format, target, options)
        }
        return id
    }

    /** The run's own coroutine deletes its half-written file; see [export]. */
    override fun stop(run: ExportRun?) {
        job?.cancel()
    }

    override fun shareCopyTarget(fileName: String): String = IosCopyFolders.share + "/" + fileName

    override fun cleanShareCopies() {
        IosCopyFolders.sweep(IosCopyFolders.share, DAY_MS)
        IosCopyFolders.sweep(IosCopyFolders.save, DAY_MS)
    }

    override fun clearDoneNotification() = Unit

    override fun screenVisible(visible: Boolean) {
        this.visible = visible
    }

    /** This phone's offset and time now, the app's language, everything included (Android's `ExportBuilder.defaults`). */
    @Composable
    override fun rememberDefaultOptions(): () -> ExportOptions = remember {
        {
            val now = nowMillis()
            ExportOptions(language = appLanguage(), utcOffsetMinutes = utcOffsetMillis(now) / 60_000, exportedAtMillis = now)
        }
    }

    /**
     * iOS saves a file that already exists (*Save to Files*), so there is nothing to pick before the run: the target is
     * a file in `tmp/copies/save/`, and the run opens the Files picker for it once it is written.
     */
    @Composable
    override fun rememberSaveToPicker(onPicked: (target: String?) -> Unit): (String, String) -> Boolean {
        val latest by rememberUpdatedState(onPicked)
        return remember {
            { _, fileName ->
                latest(IosCopyFolders.save + "/" + fileName)
                true
            }
        }
    }

    @Composable
    override fun rememberFileActions(): ExportFileActions = remember {
        object : ExportFileActions {
            override fun open(target: String, format: ExportFormat): Boolean =
                localCopy(target)?.let { IosSheets.open(NSURL.fileURLWithPath(it)) } ?: false

            override fun share(target: String, format: ExportFormat): Boolean =
                localCopy(target)?.let { IosSheets.share(NSURL.fileURLWithPath(it)) } ?: false
        }
    }

    /** The file in `tmp/` a run's target or destination stands for, while it is still there. */
    private fun localCopy(target: String): String? {
        val path = savedCopies[target] ?: target.takeIf { !it.startsWith("file://") } ?: return null
        return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
    }

    private fun update(id: String, change: (ExportRun) -> ExportRun) {
        val current = state.value.firstOrNull() ?: return
        // A replaced run (a newer export started) reports nothing more.
        if (current.id == id) state.value = listOf(change(current))
    }

    private suspend fun export(id: String, format: ExportFormat, target: String, options: ExportOptions) {
        val run = currentCoroutineContext()[Job]
        val saving = IosCopyFolders.isIn(IosCopyFolders.save, target)
        try {
            require(saving || IosCopyFolders.isIn(IosCopyFolders.share, target)) { "not a copy folder" }
            withBackgroundTime("Save a copy", onExpired = { run?.cancel() }) {
                update(id) { it.copy(state = RunState.RUNNING) }
                val bundle = repository.localRows().toBundle(options)
                val progress = Throttle { done, total -> update(id) { it.copy(done = done, total = total) } }
                PosixFileSink(target).use { sink ->
                    CopyWriter.write(bundle, format, sink, IosCopyPhotos(repository), iosArchiveTools, appVersion()) { done, total ->
                        // The stop point: a cancelled run unwinds the writer here.
                        run?.ensureActive()
                        progress.report(done, total)
                    }
                }
                val written = if (saving) {
                    val destination = withContext(Dispatchers.Main) { IosSheets.saveToFiles(NSURL.fileURLWithPath(target)) }
                        ?: throw CancellationException("not saved")
                    withContext(Dispatchers.Main) { savedCopies[destination] = target }
                    destination
                } else {
                    target
                }
                val finished = nowMillis()
                update(id) {
                    it.copy(
                        state = RunState.SUCCEEDED,
                        written = written,
                        name = if (saving) NSURL.URLWithString(written)?.lastPathComponent else written.substringAfterLast('/'),
                        partial = format == ExportFormat.BACKUP && !BackupCompleteness.isComplete(options),
                        finishedAt = finished,
                        notified = visible,
                    )
                }
            }
        } catch (e: CancellationException) {
            // Stopped, replaced, out of background time, or *Save to Files* backed out of: nothing half-written stays.
            IosCopyFolders.remove(target)
            update(id) { it.copy(state = RunState.CANCELLED, finishedAt = nowMillis(), notified = visible) }
            throw e
        } catch (e: Exception) {
            IosCopyFolders.remove(target)
            val problem = when {
                (e as? PosixFileException)?.noSpace == true -> ExportProblem.NO_SPACE
                e is PosixFileException || e is IllegalArgumentException -> ExportProblem.CANNOT_WRITE
                else -> ExportProblem.UNKNOWN
            }
            update(id) { it.copy(state = RunState.FAILED, problem = problem, finishedAt = nowMillis(), notified = visible) }
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

/** [ImportServices] on iOS (S4b-BL-81): the Files picker, staging, the common preview and write, one run at a time. */
@OptIn(ExperimentalUuidApi::class)
internal class IosImportServices(
    private val repository: CommonRepository,
    private val scope: CoroutineScope,
) : ImportServices {
    private val state = MutableStateFlow<List<ImportRun>>(emptyList())

    /** The running import; main thread. */
    private var job: Job? = null

    @Volatile
    private var visible = false

    /** The picked files, with their security scope, by their URL string (a URL made again from it has no scope). */
    private val picked = mutableMapOf<String, NSURL>()

    override fun runs(): Flow<List<ImportRun>> = state

    override fun stop() {
        job?.cancel()
    }

    override fun clearDoneNotification() = Unit

    override fun screenVisible(visible: Boolean) {
        this.visible = visible
    }

    @Composable
    override fun rememberBackupPicker(onPicked: (file: String?) -> Unit): (folder: String?) -> Boolean {
        val latest by rememberUpdatedState(onPicked)
        return remember {
            { _ ->
                IosSheets.pickBackup { url ->
                    val key = url?.absoluteString
                    if (url != null && key != null) picked[key] = url
                    latest(key)
                }
            }
        }
    }

    override suspend fun displayName(file: String): String? = urlOf(file)?.lastPathComponent?.takeIf { it.isNotBlank() }

    private fun urlOf(file: String): NSURL? = picked[file] ?: NSURL.URLWithString(file)

    override suspend fun stage(file: String): ImportStaging {
        // On the caller's (main) thread, where the picker put it.
        val url = urlOf(file)?.takeIf { it.fileURL } ?: return ImportStaging.Refused(BackupProblem.READ_FAILED)
        picked.remove(file)
        return withContext(Dispatchers.Default) { stage(url) }
    }

    private suspend fun stage(url: NSURL): ImportStaging {
        IosCopyFolders.sweep(IosCopyFolders.imports, STALE_MS)
        // No extension: what it is, a ZIP or a bare data.json, is told by its first bytes.
        val staged = IosCopyFolders.imports + "/" + Uuid.random() + ".staged"
        val job = currentCoroutineContext()[Job]
        val scoped = url.startAccessingSecurityScopedResource()
        val outcome = try {
            coordinatedCopy(url, staged) { job?.isActive != false }
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
        return when (outcome) {
            CopyOutcome.OK -> {
                // A file another app opened here arrives in Documents/Inbox; the staged copy is all that is needed.
                if (isInbox(url)) IosCopyFolders.remove(url.path)
                ImportStaging.Staged(staged)
            }
            CopyOutcome.CANCELLED -> {
                IosCopyFolders.remove(staged)
                currentCoroutineContext().ensureActive()
                ImportStaging.Refused(BackupProblem.READ_FAILED)
            }
            CopyOutcome.TOO_LARGE -> {
                IosCopyFolders.remove(staged)
                ImportStaging.Refused(BackupProblem.TOO_LARGE)
            }
            CopyOutcome.READ_FAILED -> {
                IosCopyFolders.remove(staged)
                ImportStaging.Refused(BackupProblem.READ_FAILED)
            }
        }
    }

    override suspend fun preview(stagedPath: String, displayName: String?): ImportCheck = withContext(Dispatchers.Default) {
        if (!isStaged(stagedPath)) return@withContext ImportCheck.Refused(BackupProblem.READ_FAILED)
        when (val read = readArchive(stagedPath) { ArchiveImports.preview(repository, it, stagedPath, displayName, ::newId) }) {
            is Read.Refused -> {
                IosCopyFolders.remove(stagedPath)
                ImportCheck.Refused(read.problem)
            }
            is Read.Done -> read.value
        }
    }

    override fun isStaged(path: String): Boolean =
        IosCopyFolders.isIn(IosCopyFolders.imports, path) && NSFileManager.defaultManager.fileExistsAtPath(path)

    override fun discard(path: String?) {
        if (path != null && IosCopyFolders.isIn(IosCopyFolders.imports, path)) IosCopyFolders.remove(path)
    }

    /** One import at a time: while one runs, another is not queued ([ImportStart.queued] false), as Android's KEEP. */
    override fun start(request: ImportRequest): ImportStart {
        val id = Uuid.random().toString()
        if (job?.isActive == true) return ImportStart(id) { false }
        state.value = listOf(ImportRun(id, RunState.ENQUEUED, mode = request.mode))
        job = scope.launch(Dispatchers.Default) { import(id, request) }
        return ImportStart(id) { true }
    }

    private fun update(id: String, change: (ImportRun) -> ImportRun) {
        val current = state.value.firstOrNull() ?: return
        if (current.id == id) state.value = listOf(change(current))
    }

    private suspend fun import(id: String, request: ImportRequest) {
        val run = currentCoroutineContext()[Job]
        val path = request.stagedPath
        try {
            withBackgroundTime("Import a backup", onExpired = { run?.cancel() }) {
                update(id) { it.copy(state = RunState.RUNNING) }
                val progress = Throttle { done, total -> update(id) { it.copy(done = done, total = total) } }
                // Checked again, as Android's worker does: the preview was made earlier and the path came back from state.
                val read = if (isStaged(path)) {
                    readArchive(path) { archive ->
                        ArchiveImports.apply(repository, archive, request, ::newId) { done, total ->
                            // The stop point: a merge keeps what it wrote, a copy is rolled back.
                            run?.ensureActive()
                            progress.report(done, total)
                        }
                    }
                } else {
                    Read.Refused(BackupProblem.READ_FAILED)
                }
                IosCopyFolders.remove(path)
                when (read) {
                    is Read.Refused -> update(id) {
                        it.copy(state = RunState.FAILED, problem = read.problem, finishedAt = nowMillis(), notified = visible)
                    }
                    is Read.Done -> {
                        val r = read.value
                        update(id) {
                            it.copy(
                                state = RunState.SUCCEEDED, houses = r.houses, visits = r.visits, photos = r.photos,
                                updatedHouses = r.updatedHouses, updatedVisits = r.updatedVisits,
                                restoredHouses = r.restoredHouses, photosSkipped = r.photosSkipped,
                                finishedAt = nowMillis(), notified = visible,
                            )
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // The staged copy stays: after a stopped merge the screen offers the same file again to finish it.
            update(id) { it.copy(state = RunState.CANCELLED, finishedAt = nowMillis(), notified = visible) }
            throw e
        } catch (_: Exception) {
            // Everything that reaches here failed to write (a photo file, a row).
            IosCopyFolders.remove(path)
            update(id) {
                it.copy(state = RunState.FAILED, problem = BackupProblem.WRITE_FAILED, finishedAt = nowMillis(), notified = visible)
            }
        }
    }

    private fun newId(): String = Uuid.random().toString()

    private sealed interface Read<out T> {
        class Done<T>(val value: T) : Read<T>
        class Refused(val problem: BackupProblem) : Read<Nothing>
    }

    /** Opens the staged backup at [path] and runs [block] on it, or says why it cannot be read. */
    private suspend fun <T> readArchive(path: String, block: suspend (BackupArchive) -> T): Read<T> {
        val source = try {
            PosixFileSource(path)
        } catch (_: PosixFileException) {
            return Read.Refused(BackupProblem.READ_FAILED)
        }
        try {
            return when (val opened = BackupArchive.open(source, iosArchiveTools)) {
                is ArchiveOpen.Failed -> Read.Refused(opened.problem)
                is ArchiveOpen.Ok -> Read.Done(block(opened.archive))
            }
        } finally {
            source.close()
        }
    }

    private enum class CopyOutcome { OK, TOO_LARGE, READ_FAILED, CANCELLED }

    /**
     * Copies [url] into [staged] through a file coordinator, which first downloads a file that is only in iCloud; at
     * most [BackupFormat.MAX_UNCOMPRESSED_BYTES], checking [active] between 64 KiB chunks.
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun coordinatedCopy(url: NSURL, staged: String, active: () -> Boolean): CopyOutcome = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        var outcome = CopyOutcome.READ_FAILED
        NSFileCoordinator(filePresenter = null).coordinateReadingItemAtURL(
            url, options = NSFileCoordinatorReadingWithoutChanges, error = error.ptr,
        ) { readable ->
            val path = readable?.path
            if (path != null) outcome = copy(path, staged, active)
        }
        outcome
    }

    private fun copy(from: String, to: String, active: () -> Boolean): CopyOutcome {
        val source = try {
            PosixFileSource(from)
        } catch (_: PosixFileException) {
            return CopyOutcome.READ_FAILED
        }
        try {
            if (source.size > BackupFormat.MAX_UNCOMPRESSED_BYTES) return CopyOutcome.TOO_LARGE
            PosixFileSink(to).use { sink ->
                val buffer = ByteArray(64 * 1024)
                var at = 0L
                while (true) {
                    if (!active()) return CopyOutcome.CANCELLED
                    val n = source.readAt(at, buffer, 0, buffer.size)
                    if (n <= 0) break
                    at += n
                    if (at > BackupFormat.MAX_UNCOMPRESSED_BYTES) return CopyOutcome.TOO_LARGE
                    sink.write(buffer, 0, n)
                }
            }
            return CopyOutcome.OK
        } catch (_: PosixFileException) {
            // A read that failed or a full disk: a storage problem, never "too large".
            return CopyOutcome.READ_FAILED
        } finally {
            source.close()
        }
    }

    /** `Documents/Inbox/`, where iOS puts a file another app opened in Doorprints. */
    private fun isInbox(url: NSURL): Boolean {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
            ?: return false
        return url.path?.startsWith("$documents/Inbox/") == true
    }

    private companion object {
        const val STALE_MS = 6 * 60 * 60 * 1000L
    }
}

/** Writes [text] as the file [name] into [folder] (replacing one there); the path, or null when it could not be written. */
internal fun writeTextFile(folder: String, name: String, text: String): String? {
    val path = "$folder/$name"
    return try {
        PosixFileSink(path).use { it.write(text.encodeToByteArray()) }
        path
    } catch (_: PosixFileException) {
        null
    }
}
