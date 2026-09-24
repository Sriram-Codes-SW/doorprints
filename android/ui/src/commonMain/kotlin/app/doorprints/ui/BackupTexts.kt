package app.doorprints.ui

import androidx.compose.runtime.Composable
import app.doorprints.export.ExportProblem
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.ImportMode
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The Export, Import and Settings screens' texts for a finished run (ADR-23 CMP-6 P6b, S4b-BL-35), as Compose
 * resources. `:app`'s workers and notifications keep the same sentences as Android resources (ProblemMessages.kt,
 * ExportWorker.resultText, ImportWorker.importedText), because a notification has no composition; `StringParityTest`
 * keeps the two copies equal.
 */

/** The translated reason for an export or backup failure code (`export_problem_*`). */
val ExportProblem.messageResource: StringResource
    get() = when (this) {
        ExportProblem.NO_SPACE -> Res.string.export_problem_no_space
        ExportProblem.CANNOT_WRITE -> Res.string.export_problem_cannot_write
        ExportProblem.UNKNOWN -> Res.string.export_problem_unknown
    }

/** The translated reason a backup file was refused or could not be imported (`problem_*`). */
val BackupProblem.messageResource: StringResource
    get() = when (this) {
        BackupProblem.NOT_A_BACKUP -> Res.string.problem_not_a_backup
        BackupProblem.UNSUPPORTED_VERSION -> Res.string.problem_unsupported
        BackupProblem.TOO_MANY_ENTRIES -> Res.string.problem_too_many
        BackupProblem.TOO_LARGE -> Res.string.problem_too_large
        BackupProblem.SUSPICIOUS_PATH -> Res.string.problem_suspicious
        BackupProblem.CHECKSUM_MISMATCH -> Res.string.problem_checksum
        BackupProblem.BROKEN_DATA -> Res.string.problem_broken
        BackupProblem.READ_FAILED -> Res.string.problem_read_failed
        BackupProblem.WRITE_FAILED -> Res.string.problem_write_failed
    }

/**
 * "The import stopped part-way": a merge says what finishes it (importing the same file again, which is idempotent);
 * a copy says nothing was added, because it is rolled back, and never tells the user to import again "to finish",
 * which would add every house a second time. A run of unknown mode gets the merge text, as before.
 */
fun importWriteFailedResource(mode: ImportMode?): StringResource =
    if (mode == ImportMode.COPY) Res.string.import_write_failed_copy else Res.string.import_write_failed

/** The same choice for a stopped run. */
fun importStoppedResource(mode: ImportMode?): StringResource =
    if (mode == ImportMode.COPY) Res.string.import_stopped_copy else Res.string.import_stopped

/** True for a copy written into the app's cache for the share sheet rather than saved where the user chose. */
fun isShareCopy(target: String): Boolean = !target.startsWith("content://")

/**
 * The success sentence of a finished export (UX review, round 11): "Saved to Download: Doorprints-2026-09-22.html"
 * where the storage says where, "Saved: …" where it does not, "Ready to share: …" for a share copy, and "Saved a
 * partial backup…" for a full backup made with options that leave something out. `ExportWorker.resultText` writes the
 * notification's copy of it.
 */
@Composable
fun exportResultText(target: String?, name: String?, location: String?, partial: Boolean): String = when {
    // A copy in the app's private cache is not "saved" anywhere the user can reach.
    target != null && isShareCopy(target) -> stringResource(
        if (partial) Res.string.export_ready_partial else Res.string.export_ready,
        name ?: target.substringAfterLast('/'),
    )
    name == null -> stringResource(if (partial) Res.string.export_done_partial_plain else Res.string.export_done_plain)
    location != null -> stringResource(
        if (partial) Res.string.export_done_partial_in else Res.string.export_done_in, location, name,
    )
    else -> stringResource(if (partial) Res.string.export_done_partial else Res.string.export_done, name)
}

/**
 * "Added 2 houses and 20 photos. Updated 3 houses." for a finished import (UX review, 2026-09-22): the non-zero parts
 * only, in the preview's own words, and "Brought back 3 houses." first (round 11). `ImportWorker.importedText`
 * writes the notification's copy of it.
 */
@Composable
fun importedText(run: ImportRun): String {
    @Composable
    fun parts(vararg counts: Pair<org.jetbrains.compose.resources.PluralStringResource, Int>): List<String> =
        counts.filter { it.second > 0 }.map { (plural, n) -> pluralStringResource(plural, n, n) }
    val added = parts(
        Res.plurals.count_houses to (run.houses - run.updatedHouses - run.restoredHouses).coerceAtLeast(0),
        Res.plurals.count_visits to (run.visits - run.updatedVisits).coerceAtLeast(0),
        Res.plurals.count_photos to run.photos,
    )
    val updated = parts(Res.plurals.count_houses to run.updatedHouses, Res.plurals.count_visits to run.updatedVisits)
    val sentences = buildList {
        if (run.restoredHouses > 0) {
            add(pluralStringResource(Res.plurals.import_restored_result, run.restoredHouses, run.restoredHouses))
        }
        if (added.isNotEmpty()) add(stringResource(Res.string.import_added, joinedList(added)))
        if (updated.isNotEmpty()) add(stringResource(Res.string.import_updated, joinedList(updated)))
    }
    return if (sentences.isEmpty()) stringResource(Res.string.import_done_nothing) else sentences.joinToString(" ")
}
