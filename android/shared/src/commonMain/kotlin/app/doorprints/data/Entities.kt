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

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.doorprints.shared.model.HouseCost
import app.doorprints.shared.model.HouseScore
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.VisitSource
import app.doorprints.shared.sync.SyncRecord

// Room entities, in :shared commonMain since CMP-4 P4a (Room KMP; this file was :app's data/Models.kt). The table and
// column layout is the one shipped as database version 2: HouseStatus and VisitSource are stored by their constant
// names, and RoomSchemaTest pins the identity hash. The package stays app.doorprints.data, so :app's imports and the
// schema folder name do not change.

@Entity(tableName = "houses", indices = [Index("street")])
data class HouseEntity(
    @PrimaryKey val id: String,
    val label: String,
    val address: String? = null,
    val street: String? = null,
    val locality: String? = null,
    val lat: Double,
    val lon: Double,
    val status: HouseStatus = HouseStatus.NEW,
    val price: Long? = null,
    val priceType: String? = "RENT",
    val bedrooms: Int? = null,
    val rating: Int? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val listingUrl: String? = null,
    val notes: String? = null,
    // The house's own values of docs/11 5.30 item 1 (slice 1a, Room version 5): carpet area, how the point was set
    // (`LocationSource`; null for a house saved before) and the cost as `cost_*` columns, all optional. Room reads an
    // embedded object whose columns are all null as null, so an empty cost never comes back as an empty object.
    val areaSqft: Int? = null,
    val locationSource: String? = null,
    @Embedded(prefix = "cost_") val cost: HouseCost? = null,
    val checklist: Map<String, Int> = emptyMap(),
    val createdAt: Long,
    override val updatedAt: Long,
    val deleted: Boolean = false,
    /** True while this row has local changes the server hasn't seen yet. */
    override val dirty: Boolean = true,
) : SyncRecord {
    /** 0–5 overall score, see [HouseScore.of]. Null if nothing has been scored yet. Not stored. */
    val score: Double?
        get() = HouseScore.of(checklist, rating)
}

@Entity(tableName = "visits", indices = [Index("houseId"), Index("street")])
data class VisitEntity(
    @PrimaryKey val id: String,
    val houseId: String? = null,
    val lat: Double,
    val lon: Double,
    val street: String? = null,
    val arrivedAt: Long,
    val leftAt: Long? = null,
    val source: VisitSource = VisitSource.MANUAL,
    override val updatedAt: Long,
    val deleted: Boolean = false,
    override val dirty: Boolean = true,
) : SyncRecord

@Entity(tableName = "photos", indices = [Index("houseId")])
data class PhotoEntity(
    @PrimaryKey val id: String,
    val houseId: String,
    val path: String,
    val uploaded: Boolean = false,
    val createdAt: Long,
    /**
     * Deleted on this device but the server has not been told yet (threat model F-15). The file is already gone;
     * the row is removed once the server confirms the delete.
     */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

data class HouseVisitCount(val houseId: String, val visits: Int, val lastVisit: Long)

/**
 * `id` + `updatedAt` of a row, tombstones included. Read by the import preview (Sprint 4a, S4-04) so the
 * last-write-wins comparison does not have to load whole entities. Not a table: a Room query projection.
 */
data class RowVersion(val id: String, val updatedAt: Long)

/**
 * One point of the path trace (docs/11 5.27, S4b-FR-2): where the phone was while Hunt mode ran with *Trace my path*
 * on. Kept on this phone only: never synced, exported or backed up (a track is location history; a house is not),
 * thinned to about [app.doorprints.location.TrackRecorder] and deleted after 30 days (`Repository.pruneTrack`). Room
 * version 3 (`AppDatabase.MIGRATION_2_3`); the table name and columns are stored names.
 */
@Entity(tableName = "track_points", indices = [Index("at")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** When the fix was taken (epoch milliseconds, the fix's own time). */
    val at: Long,
    val lat: Double,
    val lon: Double,
    /** The fix's reported accuracy in metres, for the map to know how rough the line is. */
    val accuracyM: Float,
)

/**
 * One row of the Sprint 4b record envelope (docs/11 5.30 item 2, ADR-28): every new kind of data that is not a house,
 * a visit or a photo (brokers, criteria, viewings, ...) is a JSON object in [payload] under its [type]'s name, keyed
 * by [id] within the type. The phone never reads a payload here: `RecordType<T>` in `app.doorprints.shared.records`
 * decodes it, so a new kind of data is one serializable class and no table, DAO or migration. A tombstone keeps
 * `{}` as its payload. Room version 4 (`AppDatabase.MIGRATION_3_4`); the table name and columns are stored names.
 */
@Entity(tableName = "records", primaryKeys = ["type", "id"], indices = [Index("type")])
data class RecordEntity(
    /** The record type's name (`RecordRules.isValidType`). */
    val type: String,
    /** Unique within the type (`RecordRules.isValidId`). */
    val id: String,
    /** The record as JSON object text, at most `RecordRules.MAX_PAYLOAD_BYTES` of UTF-8. */
    val payload: String,
    override val updatedAt: Long,
    val deleted: Boolean = false,
    override val dirty: Boolean = true,
) : SyncRecord
