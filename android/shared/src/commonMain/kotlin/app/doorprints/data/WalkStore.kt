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

import app.doorprints.shared.trace.TraceConstants
import app.doorprints.shared.trace.TraceGeo
import app.doorprints.shared.trace.TracePoint
import app.doorprints.shared.trace.TraceWalk
import app.doorprints.shared.trace.WalkCodec
import app.doorprints.shared.trace.WalkSource
import app.doorprints.shared.trace.splitWalks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** What *Save with a house* did (docs/11 5.27.6). Everything but [Saved] changed nothing. */
sealed interface SaveWalkResult {
    data class Saved(val id: String) : SaveWalkResult

    /** The walk has no points (it was pruned, deleted or already saved). */
    data object NoSuchWalk : SaveWalkResult

    /** The house is not there or is deleted. */
    data object NoSuchHouse : SaveWalkResult

    /** More than [TraceConstants.MAX_WALK_POINTS] points: *This walk is too long to save.* It stays in the 30-day trace. */
    data object TooLong : SaveWalkResult

    /** The house already has [TraceConstants.MAX_WALKS_PER_HOUSE] saved walks. */
    data object HouseFull : SaveWalkResult

    /** The device already has [TraceConstants.MAX_SAVED_WALKS] saved walks. */
    data object DeviceFull : SaveWalkResult
}

/**
 * The saved walks and the walks read from the two stores (docs/11 5.27.6, docs/03 §6.2 *Room 11*). **Local only**:
 * nothing here, and no table it reads, is part of an export, a backup or a sync (`WalkPrivacySourceTest`); it logs
 * nothing (no walk id, point or count in any log line or breadcrumb).
 */
@OptIn(ExperimentalUuidApi::class)
class WalkStore(
    private val db: AppDatabase,
    private val now: () -> Long,
    private val newId: () -> String = { Uuid.random().toString() },
) {
    /**
     * Emits at once and after each committed change to the trace or the saved walks, **not** the houses (a sync or a house
     * edit changes no walk; a deleted house's walks go with the sweep, which deletes `saved_walks` rows and so wakes this).
     * Not `localTablesChanged`.
     */
    fun changes(): Flow<Unit> = db.invalidationTracker.createFlow("track_points", "saved_walks").map { }

    fun savedWalkCount(): Flow<Int> = db.savedWalks().observeCount()

    fun savedWalksOf(houseId: String): Flow<List<SavedWalkSummary>> = db.savedWalks().observeForHouse(houseId)

    /** The walk [walkId] moved into a saved walk of [houseId], in one transaction; a refusal changes nothing. */
    suspend fun saveWalk(houseId: String, walkId: Long): SaveWalkResult = db.withImmediateTransaction {
        val house = db.houses().get(houseId)
        if (house == null || house.deleted) return@withImmediateTransaction SaveWalkResult.NoSuchHouse
        if (walkId == 0L) return@withImmediateTransaction SaveWalkResult.NoSuchWalk // 0 is no id: never a whole old trace
        val points = db.track().ofWalk(walkId)
        if (points.size < 2) return@withImmediateTransaction SaveWalkResult.NoSuchWalk // one point is no walk (as the website)
        if (points.size > TraceConstants.MAX_WALK_POINTS) return@withImmediateTransaction SaveWalkResult.TooLong
        if (db.savedWalks().countForHouse(houseId) >= TraceConstants.MAX_WALKS_PER_HOUSE) {
            return@withImmediateTransaction SaveWalkResult.HouseFull
        }
        if (db.savedWalks().count() >= TraceConstants.MAX_SAVED_WALKS) return@withImmediateTransaction SaveWalkResult.DeviceFull
        val tp = points.map { TracePoint(it.lat, it.lon, it.at, it.walkId) }
        val id = newId()
        db.savedWalks().insert(
            SavedWalkEntity(
                id = id, houseId = houseId, startedAt = points.first().at, endedAt = points.last().at, savedAt = now(),
                pointCount = points.size, lengthM = lengthM(tp), points = WalkCodec.encode(tp),
            ),
        )
        db.track().deleteWalk(walkId)
        SaveWalkResult.Saved(id)
    }

    suspend fun deleteTraceWalk(walkId: Long) {
        if (walkId != 0L) db.track().deleteWalk(walkId)
    }

    suspend fun deleteSavedWalk(id: String) = db.savedWalks().delete(id)

    suspend fun deleteAllSavedWalks() = db.savedWalks().deleteAll()

    suspend fun savedWalkPoints(id: String): List<TracePoint>? =
        db.savedWalks().get(id)?.let { WalkCodec.decode(it.points, it.startedAt, it.pointCount) }

    /** The newest walk id above [askedUpTo], not [liveWalkId], with at least 5 points and 100 m (docs/11 5.27.6). */
    suspend fun lastEndedWalk(askedUpTo: Long, liveWalkId: Long): Long? {
        for (id in db.track().walkIdsAbove(askedUpTo, liveWalkId)) {
            val points = db.track().ofWalk(id).map { TracePoint(it.lat, it.lon, it.at, it.walkId) }
            if (points.size >= TraceConstants.ASK_MIN_POINTS && lengthM(points) >= TraceConstants.ASK_MIN_LENGTH_M) return id
        }
        return null
    }

    /**
     * Every walk stored: the trace since [sinceMs] split into walks (a trace walk whose id is a saved walk's id is left
     * out, a save cut between its two writes), then every saved walk of a live house, whatever its age, one decoded at a
     * time.
     */
    suspend fun walks(sinceMs: Long): List<TraceWalk> =
        walksOtherThanWithSource(0, sinceMs).map { TraceWalk(it.first, it.second) }

    /** [walks] as plain point lists, without the live walk [liveWalkId] (the alert's others). */
    suspend fun walksOtherThan(liveWalkId: Long, sinceMs: Long): List<List<TracePoint>> =
        walksOtherThanWithSource(liveWalkId, sinceMs).map { it.first }

    private suspend fun walksOtherThanWithSource(liveWalkId: Long, sinceMs: Long): List<Pair<List<TracePoint>, WalkSource>> {
        val saved = db.savedWalks().live()
        val savedIds = saved.mapTo(HashSet()) { it.startedAt }
        val trace = splitWalks(db.track().since(sinceMs).map { TracePoint(it.lat, it.lon, it.at, it.walkId) })
            .filter { w ->
                val id = w.first().walkId
                // Legacy rows (id 0) less than 30 minutes before the live walk merge into it, so the live id may be on
                // a later point than the first: any walk holding a point of the live walk is the live walk.
                (liveWalkId == 0L || w.none { it.walkId == liveWalkId }) && (id == 0L || id !in savedIds)
            }
        val out = ArrayList<Pair<List<TracePoint>, WalkSource>>(trace.size + saved.size)
        trace.mapTo(out) { it to WalkSource.TRACE }
        for (w in saved) WalkCodec.decode(w.points, w.startedAt, w.pointCount)?.let { out.add(it to WalkSource.SAVED) }
        return out
    }

    /** Every saved walk whose house is a tombstone or missing goes; not cancelled by a screen closing. */
    suspend fun sweep() {
        withContext(NonCancellable + Dispatchers.IO) { runCatching { db.savedWalks().sweepOfDeletedHouses() } }
    }

    private fun lengthM(points: List<TracePoint>): Int {
        var total = 0.0
        for (i in 1 until points.size) {
            if (points[i].resumed) continue
            total += TraceGeo.segmentLengthM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
        return total.roundToInt()
    }
}
