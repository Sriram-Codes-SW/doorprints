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
import kotlin.test.assertNull

/**
 * The cost arithmetic of docs/11 5.21 (slice 1a): the seven vectors, in this order, are the same list the web keeps
 * in `house-cost.spec.ts`, so both apps compute the same monthly cost, move-in money and cost per sq ft.
 */
class CostSummaryTest {
    @Test
    fun vector1RentWithDepositMaintenanceBrokerageMonthsAndAgreedPrice() {
        val s = CostSummary.of(
            32_000, "RENT", 1150,
            HouseCost(deposit = 64_000, maintenance = 2_500, maintenanceIncluded = false, brokerageMonths = 1, agreedPrice = 31_000),
        )
        assertEquals(34_500L, s.monthlyCost)
        assertEquals(128_000L, s.moveIn)
        assertEquals(27.0, s.perSqFt)
        assertEquals(31_000L, s.effectivePrice)
    }

    @Test
    fun vector2RentAloneIsItsOwnMonthlyCostAndMoveIn() {
        val s = CostSummary.of(32_000, "RENT", null, null)
        assertEquals(32_000L, s.monthlyCost)
        assertEquals(32_000L, s.moveIn)
        assertNull(s.perSqFt)
        assertEquals(32_000L, s.effectivePrice)
    }

    @Test
    fun vector3IncludedMaintenanceAddsNothingAndDepositMonthsBecomeRupees() {
        val s = CostSummary.of(32_000, "RENT", null, HouseCost(maintenance = 2_500, maintenanceIncluded = true, depositMonths = 2))
        assertEquals(32_000L, s.monthlyCost)
        assertEquals(96_000L, s.moveIn)
    }

    @Test
    fun vector4ASaleHasNoMonthlyCostOrMoveInButAPricePerSqFt() {
        val s = CostSummary.of(1_250_000, "SALE", 1450, HouseCost(brokerage = 25_000, agreedPrice = 1_200_000))
        assertNull(s.monthlyCost)
        assertNull(s.moveIn)
        assertEquals(827.6, s.perSqFt)
        assertEquals(1_200_000L, s.effectivePrice)
    }

    @Test
    fun vector5NoPriceComputesNothing() {
        val s = CostSummary.of(null, "RENT", 1000, HouseCost(deposit = 64_000))
        assertNull(s.monthlyCost)
        assertNull(s.moveIn)
        assertNull(s.perSqFt)
        assertNull(s.effectivePrice)
    }

    @Test
    fun vector6AreaZeroNeverDivides() {
        assertNull(CostSummary.of(32_000, "RENT", 0, null).perSqFt)
    }

    @Test
    fun vector7RupeesWinOverMonths() {
        assertEquals(96_000L, CostSummary.of(32_000, "RENT", null, HouseCost(deposit = 64_000, depositMonths = 3)).moveIn)
    }
}
