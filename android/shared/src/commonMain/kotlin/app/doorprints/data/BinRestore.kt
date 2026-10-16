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
package app.doorprints.data

import app.doorprints.drive.photo.PhotoRestoreReason
import app.doorprints.drive.photo.PhotoRestoreReport
import app.doorprints.drive.photo.PhotoRestoreRequest
import app.doorprints.drive.photo.PhotoRestoreRules
import app.doorprints.shared.api.IsoTime
import kotlinx.coroutines.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * What a *Restore* from *Recently deleted* did about the candidate's photos (S4b-BL-293), for the words after it: how many
 * photos it had ([photosTotal], 0 when none or when nobody recorded any), how many are back ([photosBack]), how many of those
 * came from the person's own Google Drive ([photosFromDrive]), and why the others did not ([reasons], each once). The
 * candidate itself is always restored when this is returned. Web twin: `Restored` in `delete-undo.service.ts`.
 */
data class BinRestore(
    val photosTotal: Int = 0,
    val photosBack: Int = 0,
    val photosFromDrive: Int = 0,
    val reasons: List<PhotoRestoreReason> = emptyList(),
) {
    companion object {
        /** A restore with nothing to say about photos. */
        val PLAIN = BinRestore()
    }
}

/**
 * *Restore* in *Recently deleted* with photos (S4b-BL-293): [restoreHouse] first, then the photos the candidate lost are asked
 * of [fetch] (the person's own Google Drive) and each that comes back is stored under a new id (the old id is a tombstone every
 * device keeps), not uploaded, with the room, tags and caption it had; each that cannot is counted with its reason. Whatever
 * [fetch] does, the candidate's own restore stands.
 */
internal class BinRestorer(
    private val db: AppDatabase,
    private val photoStore: PhotoStore,
    private val now: () -> Long,
    private val restoreHouse: suspend (String) -> Boolean,
) {
    /** Null when the house is missing or not deleted (nothing is written and [fetch] is not asked). */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun restore(id: String, fetch: suspend (PhotoRestoreRequest) -> PhotoRestoreReport): BinRestore? {
        val deletedAt = db.houses().getMany(listOf(id)).firstOrNull()?.takeIf { it.deleted }?.updatedAt ?: return null
        if (!restoreHouse(id)) return null
        val report = try {
            fetch(PhotoRestoreRequest(id, deletedAt))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PhotoRestoreReport.noConnection(emptyList())
        }
        val failed = report.failed.mapTo(ArrayList()) { it.reason }
        var fromDrive = 0
        for (got in report.fetched) {
            try {
                val at = got.photo.createdAt?.let { runCatching { IsoTime.parseMillis(it) }.getOrNull() } ?: now()
                photoStore.addRestored(id, Uuid.random().toString(), got.bytes, at, got.photo.meta)
                fromDrive++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed += PhotoRestoreReason.UNREADABLE
            }
        }
        val summary = PhotoRestoreRules.summarize(0, fromDrive, failed, report.connection)
        return BinRestore(summary.total, summary.restored, fromDrive, summary.reasons)
    }
}
