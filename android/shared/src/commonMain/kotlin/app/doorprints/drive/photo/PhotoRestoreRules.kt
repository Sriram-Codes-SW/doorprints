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
package app.doorprints.drive.photo

import app.doorprints.shared.model.PhotoMeta
import app.doorprints.shared.sync.SyncKind
import app.doorprints.shared.sync.SyncRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * Getting a restored candidate's photos back from the person's own Google Drive (S4b-BL-293; docs/15 §1.4, docs/03 §20).
 * The rules only, pure: which photos belong to the candidate, which are still inside the 30 days, what the person is told.
 * Web twin: `photo-restore-rules.ts`, the same names; both read `docs/schemas/photo-restore-vectors.json`.
 */

/** Why a photo did not come back; the order is the order the words list them. */
enum class PhotoRestoreReason { NOT_CONNECTED, OFFLINE, PAST_WINDOW, MISSING, UNREADABLE }

/** How far a run got with Drive itself, before any photo. */
enum class RestoreConnection { OK, NOT_CONNECTED, OFFLINE }

/** What to do with one photo. */
enum class RestoreAction { FETCH, PAST_WINDOW, MISSING }

/** A deleted photo whose bytes are gone from this device: its id, when it was deleted and what is known of it. */
data class GonePhoto(
    val id: String,
    /** Epoch milliseconds of the photo's tombstone. */
    val deletedAt: Long,
    val contentType: String? = null,
    val createdAt: String? = null,
    val meta: PhotoMeta = PhotoMeta(),
    /** The Drive file of its bytes, when a record names it. */
    val ref: PhotoRef? = null,
)

/** One photo and what to do with it. */
data class PlanItem(val photo: GonePhoto, val action: RestoreAction)

/** What the person is told about the photos of one restored candidate. */
data class RestoreSummary(
    /** How many photos the candidate had (at least the ones accounted for). */
    val total: Int,
    /** How many came back (from this device's own copies or from Google Drive). */
    val restored: Int,
    /** Why the others did not, each reason once, in [PhotoRestoreReason] order; empty when none is missing. */
    val reasons: List<PhotoRestoreReason>,
)

object PhotoRestoreRules {
    /** How long Drive keeps the encrypted copy of a deleted photo, in days (docs/15 §1.4): the same 30 days as *Recently deleted*. */
    const val RESTORE_WINDOW_DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** A photo tombstoned at most this long before its candidate counts as deleted with it (a sync can stamp it a little later or earlier). */
    const val WITH_CANDIDATE_MS = 60_000L

    /** The photo of a tombstone row of the sync file, or null when the row is not a photo tombstone. */
    fun goneOfRow(row: SyncRow): GonePhoto? {
        if (row.kind != SyncKind.PHOTOS || !row.stamp.deleted) return null
        fun text(k: String): String? = (row.json[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val tags = (row.json["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return GonePhoto(
            id = row.key,
            deletedAt = row.stamp.updatedAt,
            contentType = text("contentType"),
            createdAt = text("createdAt"),
            meta = PhotoMeta.coerced(text("roomId"), tags, text("caption"), (row.json["metaUpdatedAt"] as? JsonPrimitive)?.longOrNull),
            ref = app.doorprints.drive.sync.SyncRows.refOf(row),
        )
    }

    /** The candidate a photo row of the sync file belongs to. */
    fun houseOfRow(row: SyncRow): String? =
        ((row.json[SyncRow.HOUSE_REF] ?: row.json[SyncRow.FORMER_HOUSE_REF]) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** True when a photo was deleted along with its candidate (not long before it). */
    fun deletedWithCandidate(photoDeletedAt: Long, candidateDeletedAt: Long): Boolean = photoDeletedAt >= candidateDeletedAt - WITH_CANDIDATE_MS

    /**
     * The plan for one restored candidate: the photos known on this device ([local]: tombstones that still have a row here)
     * and those the sync file names ([file]: this device's own file, tombstones of this candidate), by photo id, only those
     * deleted with the candidate. A photo is fetched when a Drive file is named for it (the file's row, else the device's own
     * [refs]) and its tombstone is inside the 30 days; it is past the window when it is not; it is missing when no Drive file
     * is known for it. The local photos come first, then the file's.
     */
    fun planRestore(
        houseId: String,
        houseDeletedAt: Long,
        now: Long,
        local: List<GonePhoto>,
        file: List<SyncRow>,
        refs: Map<String, PhotoRef>,
    ): List<PlanItem> {
        val byId = LinkedHashMap<String, GonePhoto>()
        fun consider(p: GonePhoto) {
            if (!deletedWithCandidate(p.deletedAt, houseDeletedAt)) return
            val had = byId[p.id]
            byId[p.id] = if (had == null) p else had.copy(ref = had.ref ?: p.ref)
        }
        for (p in local) consider(p)
        for (row in file) {
            val p = goneOfRow(row) ?: continue
            if (houseOfRow(row) == houseId) consider(p)
        }
        return byId.values.map { p ->
            val photo = p.copy(ref = p.ref ?: refs[p.id])
            when {
                photo.ref == null -> PlanItem(photo, RestoreAction.MISSING)
                now - photo.deletedAt > RESTORE_WINDOW_DAYS * DAY_MS -> PlanItem(photo, RestoreAction.PAST_WINDOW)
                else -> PlanItem(photo, RestoreAction.FETCH)
            }
        }
    }

    /**
     * The summary: [noted] is how many photos the candidate had when it was deleted here (0 when unknown), [back] how many
     * are back, [failed] the reason of each photo that is not. A photo nobody recorded ([noted] above what is accounted
     * for) is missing, or not fetched for the reason Drive itself could not be reached.
     */
    fun summarize(noted: Int, back: Int, failed: List<PhotoRestoreReason>, connection: RestoreConnection): RestoreSummary {
        val accounted = back + failed.size
        val total = maxOf(noted, accounted)
        val reasons = failed.toMutableSet()
        if (total > accounted) {
            reasons += when (connection) {
                RestoreConnection.OK -> PhotoRestoreReason.MISSING
                RestoreConnection.NOT_CONNECTED -> PhotoRestoreReason.NOT_CONNECTED
                RestoreConnection.OFFLINE -> PhotoRestoreReason.OFFLINE
            }
        }
        return RestoreSummary(total, back, PhotoRestoreReason.entries.filter { it in reasons })
    }
}
