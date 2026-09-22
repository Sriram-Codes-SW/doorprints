package com.househunt.app.data

import androidx.annotation.StringRes
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.househunt.app.R

/** Stored by name (shared with the API and web); only the label is translated. */
enum class HouseStatus(@StringRes val labelRes: Int) {
    NEW(R.string.status_NEW),
    SHORTLISTED(R.string.status_SHORTLISTED),
    REJECTED(R.string.status_REJECTED),
}

/**
 * Things worth checking at every house. Each is scored 0 (bad) to 5 (great). Keys are language-neutral and shared
 * with the API and the web app; labels come from strings.xml (check_water, ...), like the web's check.water keys.
 */
object Checklist {
    val items: Map<String, Int> = linkedMapOf(
        "water" to R.string.check_water,
        "power" to R.string.check_power,
        "parking" to R.string.check_parking,
        "sunlight" to R.string.check_sunlight,
        "ventilation" to R.string.check_ventilation,
        "noise" to R.string.check_noise,
        "security" to R.string.check_security,
        "maintenance" to R.string.check_maintenance,
        "neighbourhood" to R.string.check_neighbourhood,
        "commute" to R.string.check_commute,
    )
}

/** Same cap as the server's app.limits.max-photos-per-house default (threat model F-06). */
const val MAX_PHOTOS_PER_HOUSE = 20

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
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** True while this row has local changes the server hasn't seen yet. */
    val dirty: Boolean = true,
) {
    /**
     * 0–5 overall score: average of the checklist, blended 50/50 with the star rating when both exist.
     * Null if nothing has been scored yet.
     */
    val score: Double?
        get() {
            val check = if (checklist.isEmpty()) null else checklist.values.average()
            val stars = rating?.toDouble()
            return when {
                check != null && stars != null -> (check + stars) / 2
                else -> check ?: stars
            }
        }
}

enum class VisitSource { AUTO, MANUAL }

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
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

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
