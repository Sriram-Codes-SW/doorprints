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

import app.doorprints.shared.model.DeleteScope
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.HouseStatusRules
import app.doorprints.shared.model.StatusHouse
import kotlinx.coroutines.flow.Flow

/**
 * The writes of house rows that carry a stamp (docs/03 §3, deletion safeguards PR 3, S4b-BL-285), apart from the repository:
 * [put] writes a row already stamped (with the TAKEN rule of slice 5), [deleteMany] and [restoreMany] are *Delete all in this
 * space*, *Undo* and *Restore* (a tombstone is a row with `deleted = true`; every write is dirty and later than the row it
 * replaces, so a sync carries it, last write wins). Each reads and writes the rows it is given and no other.
 */
internal class HouseWriter(private val db: AppDatabase, private val now: () -> Long, private val syncSoon: () -> Unit) {
    /** Writes a stamped house row and asks for a sync. A TAKEN house returns the one that was TAKEN to SHORTLISTED (at most one is TAKEN). */
    suspend fun put(row: HouseEntity) {
        if (row.status == HouseStatus.TAKEN && !row.deleted) {
            db.withImmediateTransaction {
                val others = db.houses().all().filter { it.id != row.id }
                val chosen = HouseStatusRules.choose(
                    others.map { StatusHouse(it.id, it.status) } + StatusHouse(row.id, row.status), row.id, HouseStatus.TAKEN,
                ).associate { it.id to it.status }
                val stamp = row.updatedAt
                for (h in others) {
                    if (chosen[h.id] != h.status) {
                        db.houses().upsert(h.copy(status = chosen.getValue(h.id), updatedAt = stamp, dirty = true))
                    }
                }
                db.houses().upsert(row)
            }
        } else {
            db.houses().upsert(row)
        }
        syncSoon()
    }

    /** Tombstones the live houses among [ids] in ONE transaction; a deleted or missing one is skipped. Returns the ids deleted. */
    suspend fun deleteMany(ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val stamp = now()
        val gone = db.withImmediateTransaction {
            val live = read(ids).filter { !it.deleted }
            for (h in live) db.houses().upsert(h.copy(deleted = true, dirty = true, updatedAt = DeleteScope.nextStamp(h.updatedAt, stamp)))
            live.map { it.id }
        }
        if (gone.isNotEmpty()) syncSoon()
        return gone
    }

    /** Brings the tombstones among [ids] back (live, dirty, `updatedAt` past the tombstone's); returns the ids brought back. */
    suspend fun restoreMany(ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val stamp = now()
        val back = read(ids).filter { it.deleted }
            .map { it.copy(deleted = false, dirty = true, updatedAt = DeleteScope.nextStamp(it.updatedAt, stamp)) }
        // A TAKEN house goes through the TAKEN rule ([put]); the rest are one transaction.
        val (taken, plain) = back.partition { it.status == HouseStatus.TAKEN }
        if (plain.isNotEmpty()) {
            db.withImmediateTransaction { for (h in plain) db.houses().upsert(h) }
            syncSoon()
        }
        for (h in taken) put(h)
        return back.map { it.id }
    }

    /** Every tombstone, newest first (`DeleteScope.binRows` picks one space's, younger than 30 days). */
    fun observeDeleted(): Flow<List<HouseEntity>> = db.houses().observeDeleted()

    private suspend fun read(ids: List<String>): List<HouseEntity> =
        ids.distinct().chunked(CommonRepository.PULL_READ_CHUNK).flatMap { db.houses().getMany(it) }
}
