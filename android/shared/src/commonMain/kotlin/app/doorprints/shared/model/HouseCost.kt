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

package app.doorprints.shared.model

import kotlinx.serialization.Serializable
import kotlin.math.floor

/**
 * The real cost of a house (docs/11 5.21; 5.30 item 1, slice 1a): every field optional, whole rupees as [Long] and
 * months as [Int], nested under `cost` on the wire, in `data.json` and (as `cost_*` columns) in Room. The property
 * names and their order are the format's (`docs/schemas/backup-sample.json`, `BackupFieldsTest`); absent means
 * unknown and is never written as `null` (docs/schemas README section 4). The TypeScript twin is
 * `web/src/app/shared/house-cost.ts`; the server's is `HouseCost` in `HouseDto`.
 */
@Serializable
data class HouseCost(
    /** The deposit in rupees; [depositMonths] is the same thing as months of rent (rupees win in the arithmetic). */
    val deposit: Long? = null,
    val depositMonths: Int? = null,
    /** Maintenance in rupees per month. */
    val maintenance: Long? = null,
    /** True when the rent already includes the maintenance. */
    val maintenanceIncluded: Boolean? = null,
    val brokerage: Long? = null,
    val brokerageMonths: Int? = null,
    val lockInMonths: Int? = null,
    val noticeMonths: Int? = null,
    /** A calendar date `YYYY-MM-DD`, no time zone. */
    val availableFrom: String? = null,
    /** A negotiation is the person's own: never sent to AI (docs/11 5.30 item 5). */
    val myOffer: Long? = null,
    val agreedPrice: Long? = null,
) {
    /** True when nothing is set: such a cost is written absent, never as `{}`, and read as no cost. */
    val isEmpty: Boolean
        get() = deposit == null && depositMonths == null && maintenance == null && maintenanceIncluded == null &&
            brokerage == null && brokerageMonths == null && lockInMonths == null && noticeMonths == null &&
            availableFrom == null && myOffer == null && agreedPrice == null

    /** This cost, or null when [isEmpty]: what every writer stores. */
    fun orNull(): HouseCost? = takeUnless { it.isEmpty }

    /**
     * What a reader keeps (a file, the server, another device): each field outside its range becomes unknown, and
     * an empty result is null. The server refuses such a row instead; the clients keep the rest of the house.
     */
    fun coerced(): HouseCost? = HouseCost(
        deposit = deposit?.takeIf { it in 0..MAX_RUPEES },
        depositMonths = depositMonths?.takeIf { it in 0..MAX_MONTHS },
        maintenance = maintenance?.takeIf { it in 0..MAX_RUPEES },
        maintenanceIncluded = maintenanceIncluded,
        brokerage = brokerage?.takeIf { it in 0..MAX_RUPEES },
        brokerageMonths = brokerageMonths?.takeIf { it in 0..MAX_MONTHS },
        lockInMonths = lockInMonths?.takeIf { it in 0..MAX_MONTHS },
        noticeMonths = noticeMonths?.takeIf { it in 0..MAX_MONTHS },
        availableFrom = availableFrom?.takeIf { CalendarDate.isValid(it) },
        myOffer = myOffer?.takeIf { it in 0..MAX_RUPEES },
        agreedPrice = agreedPrice?.takeIf { it in 0..MAX_RUPEES },
    ).orNull()

    companion object {
        const val MAX_RUPEES = 1_000_000_000_000L
        const val MAX_MONTHS = 120
    }
}

/** How a house got its point (FR-068). Stored and sent by name; absent for a house saved before slice 1a. */
object LocationSource {
    /** *Use my location*. */
    const val GPS = "GPS"

    /** A tap, a long press or the crosshair on the map. */
    const val MAP = "MAP"

    /** The person says the spot is approximate: a hollow marker, and never a Hunt-mode alert. */
    const val APPROX = "APPROX"

    val ALL: List<String> = listOf(GPS, MAP, APPROX)

    /** The value as read, or null for anything a reader does not know (shown as before slice 1a). */
    fun orNull(value: String?): String? = value?.takeIf { it in ALL }
}

/** The house's own value ranges (docs/11 5.30 item 1), as every reader coerces them. */
object HouseValues {
    const val MAX_AREA_SQFT = 100_000

    /** The carpet area as read, or null when outside 1..[MAX_AREA_SQFT]. */
    fun areaSqft(value: Int?): Int? = value?.takeIf { it in 1..MAX_AREA_SQFT }

    /** The floor a flat is on (S4b-BL-87): 0 the ground floor, negative a basement level. */
    const val MIN_FLOOR = -5
    const val MAX_FLOOR = 200

    /** The floor as read, or null when outside [MIN_FLOOR]..[MAX_FLOOR] (the server refuses such a row instead). */
    fun floor(value: Int?): Int? = value?.takeIf { it in MIN_FLOOR..MAX_FLOOR }
}

/**
 * What a house costs, from its price, area and [HouseCost] (docs/11 5.21): the same arithmetic on both apps
 * (`costSummary(house)` in `web/src/app/shared/house-cost.ts`; `CostSummaryTest` and `house-cost.spec.ts` keep the
 * same vectors in the same order). Each value is null when it cannot be computed.
 */
data class CostSummary(
    /** RENT only: the rent plus the maintenance unless the rent includes it. */
    val monthlyCost: Long?,
    /** RENT only: the deposit, the brokerage and the first month's rent; months of rent become rupees by the rent. */
    val moveIn: Long?,
    /** The agreed price, else the asked one, per square foot of carpet area, rounded to one decimal. */
    val perSqFt: Double?,
    /** The agreed price, else the asked one: what is shown next to the price when they differ. */
    val effectivePrice: Long?,
) {
    companion object {
        fun of(price: Long?, priceType: String?, areaSqft: Int?, cost: HouseCost?): CostSummary {
            val rent = priceType != "SALE"
            val effective = cost?.agreedPrice ?: price
            val monthly = if (rent && price != null) {
                price + (if (cost?.maintenanceIncluded == true) 0L else cost?.maintenance ?: 0L)
            } else {
                null
            }
            val moveIn = if (rent && price != null) {
                val deposit = cost?.deposit ?: cost?.depositMonths?.let { it * price } ?: 0L
                val brokerage = cost?.brokerage ?: cost?.brokerageMonths?.let { it * price } ?: 0L
                deposit + brokerage + price
            } else {
                null
            }
            val perSqFt = if (effective != null && areaSqft != null && areaSqft > 0) {
                floor(effective.toDouble() / areaSqft * 10 + 0.5) / 10
            } else {
                null
            }
            return CostSummary(monthly, moveIn, perSqFt, effective)
        }
    }
}

/**
 * `YYYY-MM-DD` calendar dates without a time zone (the cost's *available from*), as the server's `LocalDate` reads
 * them, and their epoch-day conversion for a date picker (days-to-civil and civil-to-days, H. Hinnant, as
 * `HouseDocuments.utcDate`).
 */
object CalendarDate {
    private val PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

    /** True for a real date in 0001..9999 (a 30 February is not one). */
    fun isValid(text: String): Boolean {
        if (!PATTERN.matches(text)) return false
        val y = text.substring(0, 4).toInt()
        val m = text.substring(5, 7).toInt()
        val d = text.substring(8, 10).toInt()
        if (y < 1 || m !in 1..12 || d < 1) return false
        val leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
        val days = when (m) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return d <= days
    }

    /** The date at UTC midnight of [epochMillis] (what Material's date picker hands over). */
    fun fromEpochMillis(epochMillis: Long): String {
        val z = epochMillis.floorDiv(86_400_000L) + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    /** UTC midnight of a valid [date] in epoch milliseconds (the picker's initial selection), or null. */
    fun toEpochMillis(date: String): Long? {
        if (!isValid(date)) return null
        val y0 = date.substring(0, 4).toLong()
        val m = date.substring(5, 7).toLong()
        val d = date.substring(8, 10).toLong()
        val y = if (m <= 2) y0 - 1 else y0
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146_097 + doe - 719_468) * 86_400_000L
    }
}
