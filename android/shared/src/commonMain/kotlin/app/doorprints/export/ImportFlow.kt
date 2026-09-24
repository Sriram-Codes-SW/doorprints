package app.doorprints.export

import app.doorprints.shared.export.BackupManifest
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.export.ImportPreview

// The import flow's data (S4-04), common since ADR-23 CMP-6 P6b so the Import screen and ImportViewModel in `:ui` can
// hold it; the platform reads, stages and imports the file (`:app`'s Imports and ImportWorker).

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
         * (`ImportPlan.copyDuplicates`). Not derived from [merge], which counts tombstones as "already here".
         */
        val duplicateHouses: Int,
        /**
         * The picked file's name as its provider shows it (Android: `OpenableColumns.DISPLAY_NAME`), for the file
         * header on the Import screen; null when the provider gives none. Only ever displayed, never used as a path.
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
        /** The merge preview for the two opt-in flags; one of the four the preview works out (`Imports.preview`). */
        fun mergeFor(restoreDeleted: Boolean, skipUpdates: Boolean): ImportPreview = when {
            restoreDeleted && skipUpdates -> keepMineRestored
            restoreDeleted -> mergeRestored
            skipUpdates -> keepMine
            else -> merge
        }
    }

    data class Refused(val problem: BackupProblem) : ImportCheck
}

/** How many house labels the Replace dialog names before "and *n* more". */
const val REPLACED_LABELS = 5

/** The result of copying the picked document into the cache: the staged file, or why it was refused. */
sealed interface ImportStaging {
    data class Staged(val path: String) : ImportStaging
    data class Refused(val problem: BackupProblem) : ImportStaging
}

/**
 * What the import worker is asked to do. The file is already staged in the cache ([ImportStaging]). [restoreDeleted]
 * and [skipUpdates] are the two opt-in MERGE flags of `ImportPlan` (UX review, round 11): bring back houses deleted on
 * this phone, and keep the phone's version of every row the file has a newer one of. Android packs it into
 * WorkManager's `Data` (`toData`, `fromData` in `:app`) under the keys below.
 */
data class ImportRequest(
    val stagedPath: String,
    val mode: ImportMode,
    val restoreDeleted: Boolean = false,
    val skipUpdates: Boolean = false,
) {
    companion object {
        const val KEY_PATH = "path"
        const val KEY_MODE = "mode"
        const val KEY_RESTORE = "restoreDeleted"
        const val KEY_SKIP_UPDATES = "skipUpdates"
    }
}

/**
 * An import handed to the background worker (Android: WorkManager): the request's [id], and [queued], which waits for
 * the enqueue to finish and says whether the request was kept. It is not when an import is already ENQUEUED or RUNNING
 * (Android: `ExistingWorkPolicy.KEEP`); then no run with [id] is ever reported, and a screen waiting for one would wait
 * forever. [queued] throws if the enqueue itself failed.
 */
class ImportStart(val id: String, val queued: suspend () -> Boolean)
