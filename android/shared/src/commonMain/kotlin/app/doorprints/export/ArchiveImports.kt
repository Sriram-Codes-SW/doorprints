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

package app.doorprints.export

import app.doorprints.data.Repository
import app.doorprints.shared.export.BackupArchive
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.export.ImportPlan

/**
 * The preview and the write of an import from a [BackupArchive], in common code (S4b-BL-81, the iPhone): what Android's
 * `Imports.preview` and `ImportWorker` do with their `BackupReader`, with the same `ImportPlan` calls and flags, so a
 * file previews and imports the same on both phones (brokers, criteria, questions, viewings, areas and an update file's
 * rows included). [newId] gives fresh ids for a copy (a random UUID).
 */
object ArchiveImports {

    /** Both modes' previews of [archive] against what is on this phone; nothing is written. */
    suspend fun preview(
        repository: Repository,
        archive: BackupArchive,
        stagedPath: String,
        displayName: String?,
        newId: () -> String,
    ): ImportCheck.Ready {
        val local = repository.localVersions()
        fun preview(mode: ImportMode, restore: Boolean = false, skip: Boolean = false) = ImportPlan.preview(
            archive.data, local.houses, local.visits, local.photoIds, archive.photoEntries, mode,
            local.deletedHouseIds, local.scoredHouseIds, restoreDeleted = restore, skipUpdates = skip,
            localUnlinkedVisitIds = local.unlinkedVisitIds, localBrokers = local.brokers,
            localCriteria = local.criteria, localPreferences = local.preferences, localQuestions = local.questions,
            localViewings = local.viewings, localAreas = local.areas, localPlaces = local.places,
            localAreaNotes = local.areaNotes, localPhotoMeta = local.photoMeta,
            liveQuestions = local.liveQuestions, liveCriteria = local.liveCriteria,
        )
        // Which houses a merge would replace, by name, for the Replace dialog (a merge's plan needs no new ids).
        val replaced = ImportPlan.plan(
            archive.data, local.houses, local.visits, local.photoIds, archive.photoEntries, ImportMode.MERGE,
            newId = newId, locallyDeletedHouseIds = local.deletedHouseIds,
        ).updatedHouseIds
        val labels = archive.data.houses.asSequence()
            .filter { it.id in replaced }
            .take(REPLACED_LABELS)
            .map { h -> h.label.ifBlank { h.street ?: h.address ?: "" } }
            .toList()
        return ImportCheck.Ready(
            stagedPath = stagedPath,
            manifest = archive.manifest,
            merge = preview(ImportMode.MERGE),
            copy = preview(ImportMode.COPY),
            duplicateHouses = ImportPlan.copyDuplicates(archive.data, local.houses, local.deletedHouseIds),
            displayName = displayName,
            mergeRestored = preview(ImportMode.MERGE, restore = true),
            keepMine = preview(ImportMode.MERGE, skip = true),
            keepMineRestored = preview(ImportMode.MERGE, restore = true, skip = true),
            replacedHouseLabels = labels,
        )
    }

    /**
     * Writes the confirmed import [request] from [archive] (a merge row by row, a copy all or nothing:
     * `Repository.applyImport`). [onProgress] is the stop point, as in Android's worker.
     */
    suspend fun apply(
        repository: Repository,
        archive: BackupArchive,
        request: ImportRequest,
        newId: () -> String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Repository.ImportResult {
        val local = repository.localVersions()
        val actions = ImportPlan.plan(
            data = archive.data,
            localHouses = local.houses,
            localVisits = local.visits,
            localPhotoIds = local.photoIds,
            photoEntriesInZip = archive.photoEntries,
            mode = request.mode,
            newId = newId,
            locallyDeletedHouseIds = local.deletedHouseIds,
            restoreDeleted = request.restoreDeleted,
            skipUpdates = request.skipUpdates,
            localUnlinkedVisitIds = local.unlinkedVisitIds,
            syncedDeletedHouseIds = local.syncedDeletedHouseIds,
            localBrokers = local.brokers,
            localCriteria = local.criteria,
            localPreferences = local.preferences,
            localQuestions = local.questions,
            localViewings = local.viewings,
            localAreas = local.areas,
            localPlaces = local.places,
            localAreaNotes = local.areaNotes,
            localPhotoMeta = local.photoMeta,
            liveQuestions = local.liveQuestions, liveCriteria = local.liveCriteria,
        )
        return repository.applyImport(actions, onProgress) { entry -> archive.photoBytes(entry) }
    }
}
