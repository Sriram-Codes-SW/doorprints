package com.househunt.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.househunt.shared.model.HouseScore
import com.househunt.shared.model.HouseStatus
import com.househunt.shared.model.VisitSource
import com.househunt.shared.sync.SyncRecord

// Room entities stay in :app until the Room KMP migration (Phase 2, see android/shared/README.md). Their table and
// column layout is unchanged by Sprint 3.5: HouseStatus and VisitSource moved to :shared with the same constant
// names, and Room still stores them by name. The pure rules (score, sync conflicts) now live in :shared.

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
