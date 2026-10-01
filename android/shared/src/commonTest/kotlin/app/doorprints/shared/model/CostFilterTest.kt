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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The list's cost filters ([CostFilter], S4b-BL-84): the same houses and vectors, in the same order, as the web's
 * `cost-filter.spec.ts`. Monthly cost, money to move in and per sq ft come from [CostSummary].
 */
class CostFilterTest {
    private data class H(val id: String, val price: Long?, val type: String, val area: Int?, val cost: HouseCost?)

    private val houses = listOf(
        // ₹32,500 a month, ₹1,20,000 to move in, ₹30.0 per sq ft.
        H("rent", 30_000, "RENT", 1000, HouseCost(deposit = 90_000, maintenance = 2_500)),
        // ₹25,000 a month (maintenance included), ₹85,000 to move in, ₹50.0 per sq ft.
        H("included", 25_000, "RENT", 500, HouseCost(depositMonths = 2, maintenance = 2_000, maintenanceIncluded = true, brokerage = 10_000)),
        // A sale: no monthly cost and nothing to move in; ₹5,000.0 per sq ft.
        H("sale", 5_000_000, "SALE", 1000, null),
        // No area: ₹20,000 a month and to move in, no per sq ft.
        H("noArea", 20_000, "RENT", null, null),
        // No price: nothing computes.
        H("noPrice", null, "RENT", 800, null),
    )

    private val vectors: List<Pair<CostFilter, String>> = listOf(
        CostFilter() to "rent,included,sale,noArea,noPrice",
        CostFilter(monthly = CostRange(min = 25_000)) to "rent,included",
        CostFilter(monthly = CostRange(max = 25_000)) to "included,noArea",
        CostFilter(monthly = CostRange(25_000, 25_000)) to "included",
        // The wrong way round reads as 25,000..30,000.
        CostFilter(monthly = CostRange(min = 30_000, max = 25_000)) to "included",
        CostFilter(moveIn = CostRange(max = 1_00_000)) to "included,noArea",
        CostFilter(moveIn = CostRange(min = 0)) to "rent,included,noArea",
        CostFilter(perSqFt = CostRange(min = 40)) to "included,sale",
        CostFilter(perSqFt = CostRange(max = 30)) to "rent",
        CostFilter(monthly = CostRange(max = 30_000), perSqFt = CostRange(max = 60)) to "included",
    )

    @Test fun eachFilterKeepsTheHousesWhoseNumbersAreInItsRanges() {
        for ((filter, expected) in vectors) {
            assertEquals(expected, houses.filter { filter.matches(it.price, it.type, it.area, it.cost) }.joinToString(",") { it.id }, filter.toString())
        }
    }

    @Test fun theActiveCountAndTheSavedEndsRoundTrip() {
        assertEquals(listOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 2), vectors.map { it.first.active })
        for ((filter, _) in vectors) assertEquals(filter, CostFilter.ofEnds(filter.ends()))
        assertEquals(CostFilter(), CostFilter.ofEnds(listOf(1L)))
    }
}
