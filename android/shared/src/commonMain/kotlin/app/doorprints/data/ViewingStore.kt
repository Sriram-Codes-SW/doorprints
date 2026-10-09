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

import app.doorprints.shared.export.ExportViewing
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.ViewingStatus
import app.doorprints.shared.model.ViewingType
import app.doorprints.shared.model.Viewings
import app.doorprints.shared.records.decode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The viewings on this phone: how a viewing is read from its record row, and the local edits of it (S4b-BL-168,
 * slice 2). It is separate from [CommonRepository] because the reminders, the Assistant's text, the exporters, the
 * import (and its undo) and the screens all decode and cap a viewing the same way; a new viewing value edits this file
 * and the backup mapper, not the repository. The loops that write many kinds at once stay with the sync and the import
 * they belong to.
 *
 * A viewing is a row of the generic record table (type `viewing`). [saveRecord] and [deleteRecord] are the
 * repository's own writers, which check the id and the cap, stamp the row and ask for a sync.
 */
internal class ViewingStore(
    private val db: AppDatabase,
    private val saveRecord: suspend (id: String, viewing: Viewing) -> Unit,
    private val deleteRecord: suspend (id: String) -> Unit,
) {
    fun observeAll(): Flow<List<Viewing>> = db.records().byType(ViewingType.name).map(::sorted)

    suspend fun all(): List<Viewing> = sorted(db.records().listByType(ViewingType.name))

    // The versions query lists the tombstones too, without decoding a payload.
    suspend fun idsForReminders(): List<String> = db.records().versions(ViewingType.name).map { it.id }

    suspend fun ofHouse(houseId: String): List<Viewing> = all().filter { it.houseId == houseId }

    suspend fun next(houseId: String, nowMs: Long): Viewing? = Viewings.nextOf(all(), houseId, nowMs)

    suspend fun get(id: String): Viewing? = db.records().get(ViewingType.name, id)?.takeUnless { it.deleted }?.let(::of)

    suspend fun save(viewing: Viewing) {
        val clean = requireNotNull(viewing.coerced()) { "a viewing needs a record id, a house and a start time" }
        db.withImmediateTransaction {
            // Nothing new: no write, so the record keeps its stamp and is not pushed again.
            if (get(clean.id) == clean) return@withImmediateTransaction
            saveRecord(clean.id, clean)
        }
    }

    suspend fun newId(): String {
        // A tombstone's id is taken too: reusing it would bring the old viewing back on another device.
        val used = db.records().versions(ViewingType.name).mapTo(HashSet()) { it.id }
        return Viewing.newId({ it in used })
    }

    suspend fun delete(id: String) = deleteRecord(id)

    suspend fun markDone(id: String, visitId: String?) {
        val viewing = get(id) ?: return
        save(viewing.copy(status = ViewingStatus.DONE.name, visitId = visitId ?: viewing.visitId))
    }

    private fun sorted(rows: List<RecordEntity>): List<Viewing> = rows.mapNotNull(::of).sortedWith(Viewing.ORDER)

    companion object {
        /** A viewing row with its id, coerced; null when the payload does not decode or cannot be trusted (skipped). */
        fun of(row: RecordEntity): Viewing? = row.decode(ViewingType)?.copy(id = row.id)?.coerced()

        /** The row of a viewing in a backup, coerced (the plan checked it), dirty so it is pushed. */
        fun importedRow(v: ExportViewing, updatedAt: Long): RecordEntity = RecordEntity(
            type = ViewingType.name, id = v.id,
            payload = ViewingType.encode(checkNotNull(v.toViewing().coerced()) { "viewing ${v.id} was not checked" }),
            updatedAt = updatedAt, deleted = false, dirty = true,
        )
    }
}
