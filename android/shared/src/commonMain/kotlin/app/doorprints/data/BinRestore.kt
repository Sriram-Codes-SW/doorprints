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

import app.doorprints.drive.photo.GonePhoto
import app.doorprints.drive.photo.PhotoRestoreReason
import app.doorprints.drive.photo.PhotoRestoreReport
import app.doorprints.drive.photo.PhotoRestoreRequest
import app.doorprints.drive.photo.PhotoRestoreRules
import app.doorprints.drive.photo.RestoreConnection
import app.doorprints.shared.api.IsoTime
import kotlinx.coroutines.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * What a *Restore* from *Recently deleted* did about the candidate's photos (S4b-BL-293), for the words after it: how many
 * photos it had ([photosTotal], 0 when none or when nobody recorded any), how many are back ([photosBack]), how many of those
 * came from the person's own Google Drive ([photosFromDrive]), and why the others did not ([reasons], each once). The
 * candidate itself is always restored when this is returned. [retry] is what a second attempt needs while one can help
 * (null otherwise); it is held in memory by the screen only. Web twin: `Restored` and `PhotoRetry` in `delete-undo.service.ts`.
 */
data class BinRestore(
    val photosTotal: Int = 0,
    val photosBack: Int = 0,
    val photosFromDrive: Int = 0,
    val reasons: List<PhotoRestoreReason> = emptyList(),
    val retry: BinRetry? = null,
) {
    companion object {
        /** A restore with nothing to say about photos. */
        val PLAIN = BinRestore()
    }
}

/**
 * What a *Retry photos* needs (S4b-BL-293), everything the restore already had in hand: the candidate ([houseId],
 * [houseDeletedAt], [noted]) and [known], the photos with no bytes yet that failed for a reason a second try can fix (Drive
 * not connected, or no answer). Beyond the web's `PhotoRetry` it carries what earlier attempts settled, because the sync file
 * lists every photo of the candidate on every read: [settled] are the old photo ids already stored here or failed for good
 * (a retry never takes them again, so none is stored twice), [back] how many are back so far and [settledReasons] why the
 * settled ones that did not come back did not, so the words after a retry add up.
 */
data class BinRetry(
    val houseId: String,
    val houseDeletedAt: Long,
    val noted: Int,
    val known: List<GonePhoto>,
    val settled: Set<String> = emptySet(),
    val back: Int = 0,
    val settledReasons: List<PhotoRestoreReason> = emptyList(),
)

/**
 * *Restore* in *Recently deleted* with photos (S4b-BL-293): [restoreHouse] first, then the photos the candidate lost are asked
 * of [fetch] (the person's own Google Drive) and each that comes back is stored under a new id (the old id is a tombstone every
 * device keeps), not uploaded, with the room, tags and caption it had; each that cannot is counted with its reason. Whatever
 * [fetch] does, the candidate's own restore stands. [retry] asks again for what is still missing, through the same
 * [bringPhotosBack].
 */
internal class BinRestorer(
    private val db: AppDatabase,
    private val photoStore: PhotoStore,
    private val now: () -> Long,
    private val restoreHouse: suspend (String) -> Boolean,
) {
    /** Null when the house is missing or not deleted (nothing is written and [fetch] is not asked). */
    suspend fun restore(id: String, fetch: suspend (PhotoRestoreRequest) -> PhotoRestoreReport): BinRestore? {
        val deletedAt = db.houses().getMany(listOf(id)).firstOrNull()?.takeIf { it.deleted }?.updatedAt ?: return null
        if (!restoreHouse(id)) return null
        return result(BinRetry(id, deletedAt, noted = 0, known = emptyList()), fetch)
    }

    /**
     * Asks again for the photos of an already restored candidate, only those [BinRetry.known] and whatever the sync file now
     * names that no earlier attempt settled. The candidate is not touched. Photos come back at most once.
     */
    suspend fun retry(retry: BinRetry, fetch: suspend (PhotoRestoreRequest) -> PhotoRestoreReport): BinRestore = result(retry, fetch)

    private suspend fun result(ctx: BinRetry, fetch: suspend (PhotoRestoreRequest) -> PhotoRestoreReport): BinRestore {
        val brought = bringPhotosBack(ctx, fetch)
        val back = ctx.back + brought.fromDrive
        val summary = PhotoRestoreRules.summarize(ctx.noted, back, ctx.settledReasons + brought.failed, brought.connection)
        // A second try can help only for the reasons Drive being reached again fixes. The next attempt carries just the photos
        // that still have no bytes and failed for one of those, and never the ones settled, so none is stored twice.
        val canHelp = summary.reasons.any { it.retryable() }
        val next = if (canHelp) {
            ctx.copy(
                known = brought.stillGone,
                settled = ctx.settled + brought.settled,
                back = back,
                settledReasons = ctx.settledReasons + brought.failed.filterNot { it.retryable() },
            )
        } else {
            null
        }
        return BinRestore(summary.total, summary.restored, back, summary.reasons, next)
    }

    private class Brought(
        val fromDrive: Int,
        val failed: List<PhotoRestoreReason>,
        val connection: RestoreConnection,
        val stillGone: List<GonePhoto>,
        val settled: Set<String>,
    )

    /**
     * Asks [fetch] (Drive) for the photos of one candidate that have no bytes here and stores each that answers under a new
     * photo id; every failure becomes a reason for a photo, none is thrown. Shared by [restore] and [retry].
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun bringPhotosBack(ctx: BinRetry, fetch: suspend (PhotoRestoreRequest) -> PhotoRestoreReport): Brought {
        val report = try {
            fetch(PhotoRestoreRequest(ctx.houseId, ctx.houseDeletedAt, ctx.known))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PhotoRestoreReport.noConnection(ctx.known)
        }
        val failedNow = report.failed.filter { it.photo.id !in ctx.settled }
        val failed = failedNow.mapTo(ArrayList()) { it.reason }
        val settled = LinkedHashSet<String>()
        failedNow.filterNot { it.reason.retryable() }.mapTo(settled) { it.photo.id }
        var fromDrive = 0
        for (got in report.fetched) {
            if (got.photo.id in ctx.settled) continue
            try {
                val at = got.photo.createdAt?.let { runCatching { IsoTime.parseMillis(it) }.getOrNull() } ?: now()
                photoStore.addRestored(ctx.houseId, Uuid.random().toString(), got.bytes, at, got.photo.meta)
                fromDrive++
                settled += got.photo.id
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed += PhotoRestoreReason.UNREADABLE
                settled += got.photo.id
            }
        }
        val stillGone = failedNow.filter { it.reason.retryable() }.map { it.photo }
        return Brought(fromDrive, failed, report.connection, stillGone, settled)
    }
}

/** The reasons a second try can fix: Drive was not signed in, or gave no answer. */
private fun PhotoRestoreReason.retryable() = this == PhotoRestoreReason.NOT_CONNECTED || this == PhotoRestoreReason.OFFLINE
