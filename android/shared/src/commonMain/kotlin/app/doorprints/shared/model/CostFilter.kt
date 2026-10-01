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

/**
 * One range of the list's cost filters (docs/11 5.21, S4b-BL-84): whole rupees, each end optional. A range typed the
 * wrong way round reads the right way round.
 */
data class CostRange(val min: Long? = null, val max: Long? = null) {
    val isSet: Boolean get() = min != null || max != null

    /** True when the range is not set, or [value] is known and within it (both ends included). */
    fun accepts(value: Double?): Boolean {
        if (!isSet) return true
        if (value == null) return false
        val low = if (min != null && max != null) minOf(min, max) else min
        val high = if (min != null && max != null) maxOf(min, max) else max
        return (low == null || value >= low) && (high == null || value <= high)
    }
}

/**
 * The house list's filters over the cost numbers (docs/11 5.21: "the numbers join the filters"; S4b-BL-84): monthly
 * cost, money to move in and cost per sq ft, each a [CostRange] over what [CostSummary] computes. A house whose number
 * cannot be computed (a sale has no monthly cost, a house without an area no per sq ft) is left out by a range on that
 * number and kept by the others. On top of the status filter and the search, never instead of them. The web's
 * `costFilterMatches` (`shared/cost-filter.ts`) keeps the same vectors (`CostFilterTest`, `cost-filter.spec.ts`).
 */
data class CostFilter(
    val monthly: CostRange = CostRange(),
    val moveIn: CostRange = CostRange(),
    val perSqFt: CostRange = CostRange(),
) {
    /** How many of the three ranges are set: the count on the *Filters* button. */
    val active: Int get() = listOf(monthly, moveIn, perSqFt).count { it.isSet }

    fun matches(summary: CostSummary): Boolean =
        monthly.accepts(summary.monthlyCost?.toDouble()) && moveIn.accepts(summary.moveIn?.toDouble()) &&
            perSqFt.accepts(summary.perSqFt)

    /** [matches] for a house's own values. */
    fun matches(price: Long?, priceType: String?, areaSqft: Int?, cost: HouseCost?): Boolean =
        active == 0 || matches(CostSummary.of(price, priceType, areaSqft, cost))

    /** The six ends in order (monthly, move-in, per sq ft; min then max), for saving the filter across a rotation. */
    fun ends(): List<Long?> = listOf(monthly.min, monthly.max, moveIn.min, moveIn.max, perSqFt.min, perSqFt.max)

    companion object {
        /** [ends] back; anything that is not six ends is no filter. */
        fun ofEnds(ends: List<Long?>): CostFilter =
            if (ends.size != 6) CostFilter() else CostFilter(CostRange(ends[0], ends[1]), CostRange(ends[2], ends[3]), CostRange(ends[4], ends[5]))
    }
}
