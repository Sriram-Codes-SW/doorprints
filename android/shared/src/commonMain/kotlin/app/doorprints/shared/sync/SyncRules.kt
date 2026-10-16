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

package app.doorprints.shared.sync

/**
 * A locally stored row that takes part in two-way sync. On Android the Room entities (HouseEntity, VisitEntity in
 * :app) implement it; a future iOS store would do the same.
 */
interface SyncRecord {
    /** Epoch milliseconds of the last edit on whichever device made it. */
    val updatedAt: Long

    /** True while this row has local changes the server has not seen yet. */
    val dirty: Boolean

    /** True for a tombstone; the Drive rule puts a tombstone above a live row of the same time and writer. */
    val deleted: Boolean get() = false

    /**
     * The device that made this version of the row (`by` in a `sync/1` file), the Drive rule's tie-break; null while
     * the store does not keep it (S4b-BL-118 adds it), which compares as `""`.
     */
    val writer: String? get() = null
}

/**
 * How a pulled row meets the local copy of it (S4b-BL-70): true keeps the local row and drops the incoming one. Each
 * [app.doorprints.data.SyncBackend] supplies its own: the server's is [SyncRules.serverMerge]; Drive's per-device
 * files use [DriveMerge.rule], last-write-wins on `updatedAt` with ties broken by the writing device (docs/15 §5.1,
 * S4b-BL-130), because an older device snapshot must not overwrite a clean, newer local row.
 */
fun interface MergeRule {
    fun keepLocal(local: SyncRecord?, incoming: SyncRecord): Boolean
}

/**
 * Pure conflict rules for the sync loop (Repository.sync in :app), free of platform and database types.
 *
 * "Last edit wins": when the server sends a row this device also changed and has not pushed yet (dirty), the local
 * copy is kept only if it was edited strictly later. On a tie the server copy wins, so every device converges on
 * the same row.
 */
object SyncRules {
    /** Keeps the local row only if it has unsent changes and was edited strictly later than the incoming one. */
    fun keepLocal(localDirty: Boolean, localUpdatedAt: Long, incomingUpdatedAt: Long): Boolean =
        localDirty && localUpdatedAt > incomingUpdatedAt

    /** The same rule on records; with no local row there is nothing to keep. */
    fun keepLocal(local: SyncRecord?, incoming: SyncRecord): Boolean =
        local != null && keepLocal(local.dirty, local.updatedAt, incoming.updatedAt)

    /**
     * The server sync's merge rule (S4b-BL-70): [keepLocal]. Right for a server that has already merged every device's
     * writes, so any row it sends is at least as new as a clean local one.
     */
    val serverMerge: MergeRule = MergeRule { local, incoming -> keepLocal(local, incoming) }

    /**
     * Push order (Android review, round 17): a dirty visit that is a tombstone **with no house** is pushed before
     * any house, every other dirty visit after the houses.
     *
     * When a house tombstone reaches the server, its purge unlinks every visit still linked to that house and stamps
     * them with the server's now (`HouseService.purge` → `VisitRepository.unlinkHouse`). A visit tombstone pushed
     * after that carries an older stamp, loses last-write-wins, and the next pull brings the visit back as a live
     * loose visit. The undo of a copy import deletes houses and their visits together, and writes those visit
     * tombstones with `houseId = null` so they go first and the purge finds nothing left to unlink. They need no
     * house row on the server (no foreign key to satisfy), so pushing them first is always safe. Visits that are
     * live, or tombstones that still name a house, keep going after the houses, whose rows they may need.
     */
    fun pushesBeforeHouses(deleted: Boolean, houseId: String?): Boolean = deleted && houseId == null

    /** [visits] split by [pushesBeforeHouses]: first the ones to push before the houses, then the rest, in order. */
    fun <V> visitsByPushOrder(
        visits: List<V>,
        deleted: (V) -> Boolean,
        houseId: (V) -> String?,
    ): Pair<List<V>, List<V>> = visits.partition { pushesBeforeHouses(deleted(it), houseId(it)) }

    /**
     * S4b-BL-20: the server is behind this device (its database was replaced by a new, empty one, or restored from an
     * older dump) when its highest sync version ([maxSyncVersion], `GET /api/stats`) is below one of the stored pull
     * [cursors]. A cursor only ever holds a version the server handed out, and the server's sequence never goes back
     * while it keeps its data (not even after "delete all my data"), so on a healthy server no cursor is above it.
     * Null (an older server without the field) is unknown: false.
     */
    fun serverBehind(maxSyncVersion: Long?, cursors: List<Long>): Boolean =
        maxSyncVersion != null && cursors.any { it > maxSyncVersion }

    /**
     * S4b-BL-20, from a push's answer (the web's `pushShowsReset`): every accepted write takes a new version above
     * every cursor on a healthy server, so an accepted write answered with a version at or below [highestCursor]
     * shows a server that went back. Accepted means the answer's [answerUpdatedAt] is not later than the
     * [sentUpdatedAt]: when last-write-wins keeps the server's newer row, the server answers with that row, its old
     * version and a later time, which says nothing. A missing time or version ([answerVersion] 0) is unknown: false.
     */
    fun pushShowsReset(sentUpdatedAt: Long, answerUpdatedAt: Long?, answerVersion: Long, highestCursor: Long): Boolean =
        highestCursor > 0 && answerVersion > 0 && sentUpdatedAt > 0 && answerUpdatedAt != null &&
            answerUpdatedAt > 0 && answerUpdatedAt <= sentUpdatedAt && answerVersion <= highestCursor
}
