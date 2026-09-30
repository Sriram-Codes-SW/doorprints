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

import app.doorprints.shared.export.BackupFormat
import app.doorprints.shared.export.ExportHouse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cost object's rules (slice 1a): written absent when empty, an empty object reads as none, ranges coerced. */
class HouseCostTest {
    private val house = ExportHouse(id = "h1", label = "x", lat = 1.0, lon = 2.0, createdAt = 1, updatedAt = 1)

    @Test
    fun aCostOfAllNullsIsEmptyAndWrittenAbsent() {
        assertTrue(HouseCost().isEmpty)
        assertNull(HouseCost().orNull())
        assertFalse(HouseCost(deposit = 1).isEmpty)
        val text = BackupFormat.json.encodeToString(ExportHouse.serializer(), house.copy(cost = HouseCost().orNull()))
        assertFalse(text.contains("cost"), text)
        // Absent, `null` and `{}` all read as no cost through the mappers' `orNull` / `coerced`.
        val json = "{\"id\":\"h1\",\"label\":\"x\",\"lat\":1.0,\"lon\":2.0,\"status\":\"NEW\",\"createdAt\":1,\"updatedAt\":1"
        assertNull(BackupFormat.json.decodeFromString(ExportHouse.serializer(), "$json}").cost)
        assertNull(BackupFormat.json.decodeFromString(ExportHouse.serializer(), "$json,\"cost\":null}").cost)
        assertNull(BackupFormat.json.decodeFromString(ExportHouse.serializer(), "$json,\"cost\":{}}").cost?.coerced())
    }

    @Test
    fun theFieldsAreWrittenInTheFormatsOrder() {
        val full = HouseCost(
            deposit = 1, depositMonths = 2, maintenance = 3, maintenanceIncluded = true, brokerage = 4, brokerageMonths = 5,
            lockInMonths = 6, noticeMonths = 7, availableFrom = "2026-10-15", myOffer = 8, agreedPrice = 9,
        )
        assertEquals(
            "{\"deposit\":1,\"depositMonths\":2,\"maintenance\":3,\"maintenanceIncluded\":true,\"brokerage\":4," +
                "\"brokerageMonths\":5,\"lockInMonths\":6,\"noticeMonths\":7,\"availableFrom\":\"2026-10-15\"," +
                "\"myOffer\":8,\"agreedPrice\":9}",
            BackupFormat.json.encodeToString(HouseCost.serializer(), full),
        )
        assertEquals(full, full.coerced())
    }

    @Test
    fun aValueOutOfRangeReadsAsUnknownAndTheRestIsKept() {
        val odd = HouseCost(
            deposit = -1, depositMonths = 121, maintenance = 2_500, maintenanceIncluded = false,
            brokerage = HouseCost.MAX_RUPEES + 1, brokerageMonths = 1, lockInMonths = -3, noticeMonths = 120,
            availableFrom = "2026-02-30", myOffer = 30_000, agreedPrice = 31_000,
        )
        assertEquals(
            HouseCost(maintenance = 2_500, maintenanceIncluded = false, brokerageMonths = 1, noticeMonths = 120, myOffer = 30_000, agreedPrice = 31_000),
            odd.coerced(),
        )
        assertNull(HouseCost(deposit = -1, availableFrom = "15/10/2026").coerced())
        assertEquals(1150, HouseValues.areaSqft(1150))
        assertNull(HouseValues.areaSqft(0))
        assertNull(HouseValues.areaSqft(100_001))
        assertEquals("APPROX", LocationSource.orNull("APPROX"))
        assertNull(LocationSource.orNull("gps"))
        assertNull(LocationSource.orNull(null))
    }

    @Test
    fun calendarDatesAreRealDatesAndRoundTripThroughEpochDays() {
        assertTrue(CalendarDate.isValid("2026-10-15"))
        assertTrue(CalendarDate.isValid("2024-02-29"))
        assertFalse(CalendarDate.isValid("2023-02-29"))
        assertFalse(CalendarDate.isValid("2026-13-01"))
        assertFalse(CalendarDate.isValid("2026-1-5"))
        assertFalse(CalendarDate.isValid("2026-10-15T00:00"))
        assertEquals("1970-01-01", CalendarDate.fromEpochMillis(0))
        assertEquals("2026-10-15", CalendarDate.fromEpochMillis(CalendarDate.toEpochMillis("2026-10-15")!!))
        assertEquals("2024-02-29", CalendarDate.fromEpochMillis(1_709_164_800_000))
        assertEquals(1_709_164_800_000L, CalendarDate.toEpochMillis("2024-02-29"))
        assertNull(CalendarDate.toEpochMillis("nope"))
    }
}
