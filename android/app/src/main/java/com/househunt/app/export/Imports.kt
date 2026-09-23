package com.househunt.app.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.househunt.app.data.Repository
import com.househunt.shared.export.BackupFormat
import com.househunt.shared.export.BackupManifest
import com.househunt.shared.export.BackupProblem
import com.househunt.shared.export.ImportMode
import com.househunt.shared.export.ImportPlan
import com.househunt.shared.export.ImportPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** What the import screen shows after a file is picked: either both previews, or why the file was refused. */
sealed interface ImportCheck {
    data class Ready(
        /** The staged copy in the cache; the worker reads it, and it is deleted afterwards. */
        val stagedPath: String,
        val manifest: BackupManifest?,
        val merge: ImportPreview,
        val copy: ImportPreview,
        /**
         * Houses a copy would put on this phone a second time: live houses here with an id in the file
         * ([ImportPlan.copyDuplicates]). Not derived from [merge], which counts tombstones as "already here".
         */
        val duplicateHouses: Int,
        /**
         * The picked file's name as its provider shows it (`OpenableColumns.DISPLAY_NAME`), for the file header
         * on the Import screen; null when the provider gives none. Only ever displayed, never used as a path.
         */
        val displayName: String? = null,
        /**
         * [merge] with `restoreDeleted` (UX review, round 11): what the merge does when the user also brings back
         * the houses deleted on this phone ([ImportPreview.restoredHouses]). The same as [merge] when there are none.
         */
        val mergeRestored: ImportPreview = merge,
        /** [merge] with `skipUpdates`: the Replace dialog's "Keep mine, add only what's new". */
        val keepMine: ImportPreview = merge,
        /** [merge] with both flags. */
        val keepMineRestored: ImportPreview = merge,
        /**
         * The labels (as the file has them; blank when the house has none) of the first [REPLACED_LABELS] houses a
         * merge would replace, for the Replace dialog's "Replace 3 houses and 5 visits?", so the user can judge
         * what they are overwriting. The total is [ImportPreview.updatedHouses].
         */
        val replacedHouseLabels: List<String> = emptyList(),
    ) : ImportCheck {
        /** The merge preview for the two opt-in flags; one of the four worked out in [Imports.preview]. */
        fun mergeFor(restoreDeleted: Boolean, skipUpdates: Boolean): ImportPreview = when {
            restoreDeleted && skipUpdates -> keepMineRestored
            restoreDeleted -> mergeRestored
            skipUpdates -> keepMine
            else -> merge
        }
    }

    data class Refused(val problem: BackupProblem) : ImportCheck
}

/**
 * Reading a backup before anything is written (S4-04).
 *
 * The picked document — a backup ZIP, or the bare `data.json` the server downloads — is copied into the app's cache
 * first. That costs one copy of the file, and it buys a lot:
 * `java.util.zip.ZipFile` needs random access (which a `content://` stream does not give), the reader can then
 * visit `data.json` and one photo at a time instead of streaming the whole archive, and the background worker
 * keeps working even if the temporary SAF grant on the original `Uri` has lapsed by the time it runs.
 */
object Imports {

    private const val MAX_STAGED_BYTES = BackupFormat.MAX_UNCOMPRESSED_BYTES

    private fun stagingDir(context: Context) = File(context.cacheDir, "imports").apply { mkdirs() }

    /** The result of copying the picked document into the cache: the staged file, or why it was refused. */
    sealed interface Staging {
        data class Staged(val path: String) : Staging
        data class Refused(val problem: BackupProblem) : Staging
    }

    /**
     * Copies the picked document into the cache ([stage]) and works out what each mode would do ([preview]).
     * Nothing is written to the database here — this is the "preview what will change" step.
     */
    suspend fun check(context: Context, repository: Repository, source: Uri): ImportCheck =
        when (val staging = stage(context, source)) {
            is Staging.Refused -> ImportCheck.Refused(staging.problem)
            is Staging.Staged -> preview(repository, staging.path, displayName(context, source))
        }

    /**
     * The picked document's display name (`OpenableColumns.DISPLAY_NAME`), or null when the provider has none or
     * the query fails. Shown on the Import screen so the user can see *which* file was checked (Design review,
     * 2026-09-22); it is only text, and the staged copy is never named after it.
     */
    suspend fun displayName(context: Context, source: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val column = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && c.moveToFirst()) c.getString(column)?.takeIf { it.isNotBlank() } else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A provider that throws on query (a lapsed grant, an odd provider): the header simply has no name.
            null
        }
    }

    /**
     * Copies [source] into the staging folder. Cancellable: the copy loop checks for cancellation between
     * 64 KB chunks, so leaving the screen (or tapping Cancel) during a 1 GB copy stops it straight away, and the
     * partial copy is deleted rather than left to fill the cache for six hours.
     */
    suspend fun stage(context: Context, source: Uri): Staging = withContext(Dispatchers.IO) {
        cleanOldStaging(context)
        // No extension on purpose: the copy may be a ZIP or a bare data.json, and BackupReader tells them apart by
        // their first bytes, never by a name the user or the provider chose.
        val staged = File(stagingDir(context), "${UUID.randomUUID()}.staged")
        val outcome = try {
            copy(context, source, staged)
        } catch (e: CancellationException) {
            staged.delete()
            throw e
        }
        if (outcome == CopyOutcome.OK) {
            Staging.Staged(staged.absolutePath)
        } else {
            staged.delete()
            Staging.Refused(if (outcome == CopyOutcome.TOO_LARGE) BackupProblem.TOO_LARGE else BackupProblem.READ_FAILED)
        }
    }

    /**
     * Validates an already staged copy and previews both modes. Also what a screen re-runs after a rotation or a
     * process restart: the staged path survives in saved state, the preview (which depends on what is on the phone
     * now) is simply worked out again. A staged file that has gone — swept after six hours, or deleted — is
     * [BackupProblem.READ_FAILED], and is deleted if it turns out not to be a backup.
     */
    suspend fun preview(
        repository: Repository,
        stagedPath: String,
        displayName: String? = null,
    ): ImportCheck = withContext(Dispatchers.IO) {
        val staged = File(stagedPath)
        if (!staged.isFile) return@withContext ImportCheck.Refused(BackupProblem.READ_FAILED)
        when (val opened = BackupReader.open(staged)) {
            is BackupOpen.Failed -> {
                staged.delete()
                ImportCheck.Refused(opened.problem)
            }

            is BackupOpen.Ok -> opened.reader.use { reader ->
                val local = repository.localVersions()
                fun preview(mode: ImportMode, restore: Boolean = false, skip: Boolean = false) = ImportPlan.preview(
                    reader.data, local.houses, local.visits, local.photoIds, reader.photoEntries, mode,
                    local.deletedHouseIds, local.scoredHouseIds, restoreDeleted = restore, skipUpdates = skip,
                    localUnlinkedVisitIds = local.unlinkedVisitIds,
                )
                // Which houses a merge would replace, by name, for the Replace dialog. The plan is pure and a
                // merge's needs no new ids; its updatedHouseIds are exactly the preview's updatedHouses.
                val replaced = ImportPlan.plan(
                    reader.data, local.houses, local.visits, local.photoIds, reader.photoEntries, ImportMode.MERGE,
                    newId = { UUID.randomUUID().toString() }, locallyDeletedHouseIds = local.deletedHouseIds,
                ).updatedHouseIds
                val labels = reader.data.houses.asSequence()
                    .filter { it.id in replaced }
                    .take(REPLACED_LABELS)
                    .map { h -> h.label.ifBlank { h.street ?: h.address ?: "" } }
                    .toList()
                ImportCheck.Ready(
                    stagedPath = staged.absolutePath,
                    manifest = reader.manifest,
                    merge = preview(ImportMode.MERGE),
                    copy = preview(ImportMode.COPY),
                    duplicateHouses = ImportPlan.copyDuplicates(reader.data, local.houses, local.deletedHouseIds),
                    displayName = displayName,
                    mergeRestored = preview(ImportMode.MERGE, restore = true),
                    keepMine = preview(ImportMode.MERGE, skip = true),
                    keepMineRestored = preview(ImportMode.MERGE, restore = true, skip = true),
                    replacedHouseLabels = labels,
                )
            }
        }
    }

    /** How staging the picked document ended. The three cases are three different things to tell the user. */
    private enum class CopyOutcome { OK, TOO_LARGE, READ_FAILED }

    /**
     * Copies the picked document into [staged], stopping at [MAX_STAGED_BYTES].
     *
     * Only the byte counter produces [CopyOutcome.TOO_LARGE]. A provider that hands back no stream, a read that
     * fails, and a cache partition with no room left are all [CopyOutcome.READ_FAILED] — a user who is out of disk
     * space must not be told their backup file is too big, because that sends them to the wrong fix.
     */
    private suspend fun copy(context: Context, source: Uri, staged: File): CopyOutcome {
        val context0 = currentCoroutineContext()
        return try {
            val input = context.contentResolver.openInputStream(source) ?: return CopyOutcome.READ_FAILED
            var result = CopyOutcome.OK
            input.use { bytes ->
                staged.outputStream().use { output ->
                    var total = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        // The cancellation point; see [stage].
                        context0.ensureActive()
                        val read = bytes.read(buffer)
                        if (read < 0) break
                        total += read
                        // A file larger than the uncompressed limit cannot be one of our backups.
                        if (total > MAX_STAGED_BYTES) {
                            result = CopyOutcome.TOO_LARGE
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            result
        } catch (_: IOException) {
            CopyOutcome.READ_FAILED
        } catch (_: SecurityException) {
            // The SAF grant lapsed between the pick and this read.
            CopyOutcome.READ_FAILED
        }
    }

    /** Deletes a staged file the user decided not to import. Safe to call with a path that is already gone. */
    fun discard(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    /** Anything left behind by a crash or a screen the user simply left. */
    fun cleanOldStaging(context: Context) {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        stagingDir(context).listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    private const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L
}

/** How many house labels the Replace dialog names before "and *n* more". */
const val REPLACED_LABELS = 5
