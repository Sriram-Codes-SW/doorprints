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

import app.doorprints.shared.export.ImportActions
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaNote
import app.doorprints.shared.model.AreaNoteType
import app.doorprints.shared.model.AreaType
import app.doorprints.shared.model.Place
import app.doorprints.shared.model.PlaceType
import app.doorprints.shared.records.RecordLimitException
import app.doorprints.shared.records.RecordType
import app.doorprints.shared.records.decode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Hunting areas, my places and area notes on this phone: how each is read from its record row, the local edits of
 * them, and the backup's rows for them (S4b-BL-168, slice 3). It is separate from [CommonRepository] because the Map,
 * the house page, the Assistant's text, the wake-up, the exporters and the import (and its undo) all read the three
 * kinds the same way; a new value on one of them edits this file, the model and the backup mapper, not the repository.
 * The three share one writer, [writeCapped], so they live together. The loops that write many kinds at once stay with
 * the import they belong to.
 *
 * Each kind is a row of the generic record table (types `area`, `place`, `areanote`). [records] is the repository's own
 * writer, which checks the id and the size, stamps the row and asks for a sync.
 */
internal class AreaStore(private val db: AppDatabase, private val records: RecordWriter) {
    fun observeAreas(): Flow<List<Area>> = db.records().byType(AreaType.name).map(::areasOf)

    suspend fun areas(): List<Area> = areasOf(db.records().listByType(AreaType.name))

    fun observePlaces(): Flow<List<Place>> = db.records().byType(PlaceType.name).map(::placesOf)

    suspend fun places(): List<Place> = placesOf(db.records().listByType(PlaceType.name))

    fun observeNotes(): Flow<List<AreaNote>> = db.records().byType(AreaNoteType.name).map(::notesOf)

    suspend fun notes(): List<AreaNote> = notesOf(db.records().listByType(AreaNoteType.name))

    suspend fun saveArea(area: Area) {
        val clean = area.copy(name = area.name.trim())
        require(clean.isValid) { "an area needs a record id, a name of 1..${Area.MAX_NAME}, a point and a radius of 200..2000 m" }
        writeCapped(AreaType, clean.id, clean, Area.MAX_AREAS) { areaOf(it) }
    }

    suspend fun savePlace(place: Place) {
        val clean = place.copy(name = place.name.trim())
        require(clean.isValid) { "a place needs a record id, a name of 1..${Place.MAX_NAME} and a point" }
        writeCapped(PlaceType, clean.id, clean, Place.MAX_PLACES) { placeOf(it) }
    }

    suspend fun saveNote(note: AreaNote) {
        val clean = note.copy(
            areaId = note.areaId?.trim()?.ifEmpty { null }, street = note.street?.trim()?.ifEmpty { null },
            text = note.text.trim(), updatedAt = 0L,
        )
        require(clean.isValid) { "an area note needs a record id, exactly one of an area or a street, and 1..${AreaNote.MAX_TEXT} characters" }
        writeCapped(AreaNoteType, clean.id, clean, AreaNote.MAX_NOTES) { noteOf(it)?.copy(updatedAt = 0L) }
    }

    suspend fun newAreaId(): String = usedIds(AreaType).let { used -> Area.newId({ it in used }) }

    suspend fun newPlaceId(): String = usedIds(PlaceType).let { used -> Place.newId({ it in used }) }

    suspend fun newNoteId(): String = usedIds(AreaNoteType).let { used -> AreaNote.newId({ it in used }) }

    suspend fun deleteArea(id: String) = records.delete(AreaType, id)

    suspend fun deletePlace(id: String) = records.delete(PlaceType, id)

    suspend fun deleteNote(id: String) = records.delete(AreaNoteType, id)

    /**
     * Writes [value] unless the live record already says the same (no write: it keeps its stamp and is not pushed
     * again); `RecordLimitException` when it would be live record number [max] + 1 of [type].
     */
    private suspend fun <T> writeCapped(type: RecordType<T>, id: String, value: T, max: Int, read: (RecordEntity) -> T?) {
        db.withImmediateTransaction {
            val stored = db.records().get(type.name, id)?.takeUnless { it.deleted }
            if (stored != null && read(stored) == value) return@withImmediateTransaction
            if (stored == null && db.records().countLive(type.name) >= max) throw RecordLimitException(type.name, max)
            records.save(type, id, value)
        }
    }

    // A tombstone's id is taken too: reusing it would bring the old record back on another device.
    private suspend fun usedIds(type: RecordType<*>): Set<String> = db.records().versions(type.name).mapTo(HashSet()) { it.id }

    private fun areasOf(rows: List<RecordEntity>) = rows.mapNotNull(::areaOf).sortedWith(Area.BY_NAME)

    private fun placesOf(rows: List<RecordEntity>) = rows.mapNotNull(::placeOf).sortedWith(Place.BY_NAME)

    private fun notesOf(rows: List<RecordEntity>) = rows.mapNotNull(::noteOf).sortedWith(AreaNote.NEWEST_FIRST)

    companion object {
        /** An area row with its id, coerced; null when the payload does not decode or cannot be trusted (skipped). */
        fun areaOf(row: RecordEntity): Area? = row.decode(AreaType)?.copy(id = row.id)?.coerced()

        fun placeOf(row: RecordEntity): Place? = row.decode(PlaceType)?.copy(id = row.id)?.coerced()

        /** A note row with its id and its edit stamp (not part of the payload), coerced; null when it cannot be trusted. */
        fun noteOf(row: RecordEntity): AreaNote? =
            row.decode(AreaNoteType)?.copy(id = row.id, updatedAt = row.updatedAt)?.coerced()

        /**
         * A backup's areas, places and area notes as record rows, stamped by [stamp] from the file's `updatedAt`:
         * coerced (the plan checked them), dirty so they are pushed.
         */
        fun importedRows(actions: ImportActions, stamp: (Long) -> Long): List<RecordEntity> =
            actions.areas.map { a ->
                imported(AreaType, a.id, checkNotNull(a.toArea()?.coerced()) { "area ${a.id} was not checked" }, stamp(a.updatedAt))
            } + actions.places.map { p ->
                imported(PlaceType, p.id, checkNotNull(p.toPlace()?.coerced()) { "place ${p.id} was not checked" }, stamp(p.updatedAt))
            } + actions.areaNotes.map { n ->
                imported(AreaNoteType, n.id, checkNotNull(n.toAreaNote().coerced()) { "note ${n.id} was not checked" }, stamp(n.updatedAt))
            }

        private fun <T> imported(type: RecordType<T>, id: String, value: T, updatedAt: Long) = RecordEntity(
            type = type.name, id = id, payload = type.encode(value), updatedAt = updatedAt, deleted = false, dirty = true,
        )
    }
}

/** The repository's own record writers, which check the id and the size and the row cap, stamp the row and ask for a sync. */
internal interface RecordWriter {
    suspend fun <T> save(type: RecordType<T>, id: String, value: T)

    suspend fun delete(type: RecordType<*>, id: String)
}
