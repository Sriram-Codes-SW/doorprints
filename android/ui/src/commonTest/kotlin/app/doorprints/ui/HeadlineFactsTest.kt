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

package app.doorprints.ui

import app.doorprints.shared.trace.PlaceBand
import app.doorprints.shared.trace.PlaceRow
import app.doorprints.shared.trace.WalkSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The headline's facts (docs/11 5.27.13, TC-U-154 text): distinct days, the largest distance rounded up, caps. */
class HeadlineFactsTest {
    private val day = 86_400_000L

    private fun row(i: Int, distance: Double, atMs: Long, band: PlaceBand = PlaceBand.WALKED, source: WalkSource = WalkSource.TRACE) =
        PlaceRow(i, distance, atMs, band, source)

    @Test fun theLargestDistanceAmongTheListedDaysRoundedUpIsQuoted() {
        // Newest first: a day with a 3 m walk and a day with a 22.2 m walk: "within 23 m", never the smallest.
        val f = headlineFacts(listOf(row(1, 3.0, 5 * day + 5_000), row(0, 22.2, 4 * day + 5_000)), PlaceBand.WALKED)!!
        assertEquals(23, f.distanceM)
        assertEquals(2, f.days.size)
    }

    @Test fun twoWalksOfOneDayQuoteTheLargerOfTheTwoNotTheSmaller() {
        // The text mutation of TC-U-154: the smallest would say "within 3 m" for a day whose other walk was 22 m away.
        val f = headlineFacts(listOf(row(1, 3.0, 5 * day + 9_000), row(0, 22.0, 5 * day + 2_000)), PlaceBand.WALKED)!!
        assertEquals(22, f.distanceM)
    }

    @Test fun aSubMetreDistanceIsAtLeastOneMetre() {
        assertEquals(1, headlineFacts(listOf(row(0, 0.0, day)), PlaceBand.WALKED)!!.distanceM)
        assertEquals(1, headlineFacts(listOf(row(0, 0.2, day)), PlaceBand.WALKED)!!.distanceM)
        assertEquals(22, headlineFacts(listOf(row(0, 22.0, day)), PlaceBand.WALKED)!!.distanceM)
    }

    @Test fun twoWalksOnOneDayAreOneDate() {
        val f = headlineFacts(listOf(row(1, 5.0, 5 * day + 9_000), row(0, 6.0, 5 * day + 2_000)), PlaceBand.WALKED)!!
        assertEquals(1, f.days.size)
        assertEquals(5 * day + 9_000, f.days.single(), "the day's newest walk")
    }

    @Test fun atMostThreeDaysAreListedThenTheRestAreCounted() {
        val rows = (5 downTo 0).map { row(it, 10.0 + it, it * day + 40_000_000L) }
        val f = headlineFacts(rows, PlaceBand.WALKED)!!
        assertEquals(3, f.days.size)
        assertEquals(3, f.moreDays)
        // The distance is the largest of the LISTED days (the three newest: i = 5, 4, 3): 15.
        assertEquals(15, f.distanceM)
    }

    @Test fun onlyTheRowsOfTheHeadlinesBandCount() {
        val rows = listOf(row(1, 40.0, 3 * day, PlaceBand.CLOSE), row(0, 10.0, 2 * day, PlaceBand.WALKED))
        assertEquals(10, headlineFacts(rows, PlaceBand.WALKED)!!.distanceM)
        assertEquals(40, headlineFacts(rows, PlaceBand.CLOSE)!!.distanceM)
        assertNull(headlineFacts(listOf(row(0, 10.0, day)), PlaceBand.CLOSE))
    }

    @Test fun atMostFiveRowsAreListedThenTheRestAreCounted() {
        val rows = (0 until 8).map { row(it, 5.0, (10 - it) * day) }
        val (shown, more) = listedRows(rows, PlaceBand.WALKED)
        assertEquals(5, shown.size)
        assertEquals(3, more)
        assertEquals(0, listedRows(rows.take(5), PlaceBand.WALKED).second)
    }

    @Test fun theYearIsReadOnTheDeviceClock() {
        assertEquals(1970, localYear(43_200_000L))
        assertEquals(2026, localYear(1_790_000_000_000L))
        assertEquals(2024, localYear(1_709_208_000_000L)) // 2024-02-29 12:00 UTC
        assertEquals(2024, localYear(1_735_646_400_000L)) // 2024-12-31 12:00 UTC: the same day in every zone from UTC-12 to UTC+11
    }
}
