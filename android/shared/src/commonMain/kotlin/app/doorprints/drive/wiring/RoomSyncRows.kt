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

package app.doorprints.drive.wiring

import app.doorprints.data.AppDatabase
import app.doorprints.data.PhotoEntity
import app.doorprints.data.toDto
import app.doorprints.drive.sync.LocalRows
import app.doorprints.drive.sync.SyncRows
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.api.PhotoChangeDto
import app.doorprints.shared.sync.SyncFileException
import app.doorprints.shared.sync.SyncFiles
import app.doorprints.shared.sync.SyncRow

/**
 * The phone's own rows as the Drive sync reads them (`LocalRows`, S4b-BL-118): the houses, visits, records and live
 * photos of the Room database, **tombstones included** (a deleted house must reach the other devices), each as the same
 * DTO the server sync sends plus the writer [deviceId]. Web twin: `LocalRowsAdapter` over IndexedDB.
 *
 * Photos: a photo queued for deletion is left out (the sync backend writes its tombstone itself, `deletePhoto`); the
 * others carry their size (the file's, from [photoSize]) so the pending-photo count can add them up, and a stamp of
 * their last change (the later of when they were added and their last edit of tags or caption).
 *
 * A row the sync file's schema refuses (a bad id) is left out instead of making the whole file unwritable.
 */
class RoomSyncRows(
    private val db: AppDatabase,
    private val deviceId: () -> String,
    private val photoSize: (photoId: String) -> Long?,
) : LocalRows {
    /**
      * Every house, visit and record (tombstones included) and every photo row, in the sync file's row form; a row the
      * file's schema refuses is left out.
     */
    override suspend fun all(): List<SyncRow> {
        val by = deviceId()
        // A writer the file's schema refuses would drop every row below, one by one, and write an empty file: stop instead.
        check(SyncFiles.isDeviceId(by)) { "this device's id is not a sync device id" }
        val rows = ArrayList<SyncRow>()
        for (house in db.houses().all()) rows.addIfValid { SyncRows.house(house.toDto(), by) }
        for (house in db.houses().deleted()) rows.addIfValid { SyncRows.house(house.toDto(), by) }
        for (visit in db.visits().all()) rows.addIfValid { SyncRows.visit(visit.toDto(), by) }
        for (visit in db.visits().deleted()) rows.addIfValid { SyncRows.visit(visit.toDto(), by) }
        for (record in db.records().all()) rows.addIfValid { SyncRows.record(record.toDto(), by) }
        for (photo in db.photos().all()) rows.addIfValid { SyncRows.photo(photoChange(photo), by) }
        return rows
    }

    /** The photo row by id as a DTO, or null when this device has no such photo. */
    override suspend fun photo(photoId: String): PhotoChangeDto? = db.photos().get(photoId)?.let(::photoChange)

    private fun photoChange(p: PhotoEntity): PhotoChangeDto = PhotoChangeDto(
        id = p.id,
        houseId = p.houseId,
        contentType = CONTENT_TYPE,
        sizeBytes = photoSize(p.id)?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt(),
        createdAt = IsoTime.format(p.createdAt),
        updatedAt = IsoTime.format(maxOf(p.createdAt, p.metaUpdatedAt)),
        deleted = p.deleted,
        roomId = p.roomId,
        tags = p.tags,
        caption = p.caption,
        metaUpdatedAt = p.metaUpdatedAt.takeIf { it > 0 },
    )

    private inline fun MutableList<SyncRow>.addIfValid(make: () -> SyncRow) {
        try {
            add(make())
        } catch (_: SyncFileException) {
            // Left out: see the class note.
        }
    }

    private companion object {
        /** Every stored photo is a JPEG: the app re-encodes what it takes in (`AndroidRepository.addPhoto`). */
        const val CONTENT_TYPE = "image/jpeg"
    }
}
