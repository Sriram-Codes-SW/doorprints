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
import app.doorprints.export.ExportProblem
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.ImportMode
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The texts for a finished run (ADR-23 CMP-6 P6b, S4b-BL-35), as Compose resources: in composition for the Export,
 * Import and Settings screens, and outside it ([exportResultSentence], [importedSentence], `getString` of a
 * [messageResource]) for `:app`'s workers' notifications, which read the same keys since S4b-BL-106 (they had their
 * own copies as Android resources before).
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

/**
 * True for a copy written into the app's cache for the share sheet (a plain path) rather than saved where the user
 * chose: Android's `content://` document, or the `file://` URL the iPhone's Files picker saved it to (S4b-BL-81).
 */
fun isShareCopy(target: String): Boolean = !target.startsWith("content://") && !target.startsWith("file://")

/**
 * The success sentence of a finished export (UX review, round 11): "Saved to Download: Doorprints-2026-09-22.html"
 * where the storage says where, "Saved: …" where it does not, "Ready to share: …" for a share copy, and "Saved a
 * partial backup…" for a full backup made with options that leave something out. [exportResultSentence] is the same
 * sentence outside composition.
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
 * only, in the preview's own words, and "Brought back 3 houses." first (round 11). [importedSentence] is the same
 * sentence outside composition.
 */
@Composable
fun importedText(run: ImportRun): String {
    @Composable
    fun parts(vararg counts: Pair<PluralStringResource, Int>): List<String> =
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

/**
 * [exportResultText] outside composition, for the export notification (S4b-BL-106: was `ExportWorker.resultText`
 * with its own Android resources), so the notification and the result card say the same sentence.
 */
suspend fun exportResultSentence(target: String?, name: String?, location: String?, partial: Boolean): String = when {
    target != null && isShareCopy(target) -> getString(
        if (partial) Res.string.export_ready_partial else Res.string.export_ready,
        name ?: target.substringAfterLast('/'),
    )
    name == null -> getString(if (partial) Res.string.export_done_partial_plain else Res.string.export_done_plain)
    location != null -> getString(
        if (partial) Res.string.export_done_partial_in else Res.string.export_done_in, location, name,
    )
    else -> getString(if (partial) Res.string.export_done_partial else Res.string.export_done, name)
}

/**
 * [importedText] outside composition, for the import notification (S4b-BL-106: was `ImportWorker.importedText` with
 * its own Android resources). [houses] and [visits] are everything written; [updatedHouses] and [updatedVisits] the
 * part of them that were updates; [restoredHouses] the part of [houses] that were deleted on this phone and are back.
 */
suspend fun importedSentence(
    houses: Int,
    visits: Int,
    photos: Int,
    updatedHouses: Int = 0,
    updatedVisits: Int = 0,
    restoredHouses: Int = 0,
): String {
    suspend fun parts(vararg counts: Pair<PluralStringResource, Int>): List<String> =
        counts.filter { it.second > 0 }.map { (plural, n) -> getPluralString(plural, n, n) }
    val added = parts(
        Res.plurals.count_houses to (houses - updatedHouses - restoredHouses).coerceAtLeast(0),
        Res.plurals.count_visits to (visits - updatedVisits).coerceAtLeast(0),
        Res.plurals.count_photos to photos,
    )
    val updated = parts(Res.plurals.count_houses to updatedHouses, Res.plurals.count_visits to updatedVisits)
    val sentences = buildList {
        if (restoredHouses > 0) add(getPluralString(Res.plurals.import_restored_result, restoredHouses, restoredHouses))
        if (added.isNotEmpty()) add(getString(Res.string.import_added, joinedListText(added)))
        if (updated.isNotEmpty()) add(getString(Res.string.import_updated, joinedListText(updated)))
    }
    return if (sentences.isEmpty()) getString(Res.string.import_done_nothing) else sentences.joinToString(" ")
}
