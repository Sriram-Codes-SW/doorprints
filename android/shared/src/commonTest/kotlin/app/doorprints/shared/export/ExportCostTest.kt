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
package app.doorprints.shared.export

import app.doorprints.shared.model.HouseCost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The house's own values in the copies (docs/11 5.30 item 1, slice 1a): the sixteen columns of the houses table
 * after the notes, the **Cost** block and the "Approximate location" line of a house page in HTML and Markdown.
 */
class ExportCostTest {
    private val costly = ExportFixture.house1.copy(
        areaSqft = 1150, locationSource = "GPS",
        cost = HouseCost(
            deposit = 64_000, maintenance = 2_500, maintenanceIncluded = false, brokerageMonths = 1, lockInMonths = 11,
            noticeMonths = 2, availableFrom = "2026-10-15", myOffer = 30_000, agreedPrice = 31_000,
        ),
    )
    private val approx = ExportFixture.house2.copy(locationSource = "APPROX")
    private val bundle = ExportBundle.build(ExportFixture.options(), listOf(costly, approx), emptyList(), emptyList())

    @Test
    fun theHousesTableHasTheSixteenColumnsAfterTheNotes() {
        val table = ExportRows.houses(bundle)
        val notes = table.columns.indexOf("Notes")
        assertEquals(
            listOf(
                "Carpet area (sq ft)", "Location source", "Deposit", "Deposit (months)", "Maintenance per month",
                "Maintenance included", "Brokerage", "Brokerage (months)", "Lock-in (months)", "Notice (months)",
                "Available from", "My offer", "Agreed price", "Monthly cost", "Money to move in", "Cost per sq ft",
            ),
            table.columns.subList(notes + 1, notes + 17),
        )
        // Slice 1c's room count, S4b-BL-87's floor, then the visits.
        assertEquals("Rooms", table.columns[notes + 17])
        assertEquals("Floor", table.columns[notes + 18])
        assertEquals("Visits", table.columns[notes + 19])
        val row = table.rows.first { (it[1] as Cell.Text).value == "Sunrise Apartments" }
        val cells = row.subList(notes + 1, notes + 17).map { ExportRows.plain(it, bundle.options) }
        assertEquals(
            listOf("1150", "GPS", "64000", "", "2500", "No", "", "1", "11", "2", "2026-10-15", "30000", "31000", "34500", "128000", "27.0"),
            cells,
        )
        val other = table.rows.first { (it[1] as Cell.Text).value.startsWith("Green View") }
        assertEquals(
            listOf("", "APPROX", "", "", "", "", "", "", "", "", "", "", "", "", "", ""),
            other.subList(notes + 1, notes + 17).map { ExportRows.plain(it, bundle.options) },
        )
        // The CSV follows the table: the headers and the typed cells land in it unchanged.
        val csv = CsvWriter.write(table, bundle.options)
        assertTrue(csv.startsWith("Rank,House,Status,Score,Price,Price type,Bedrooms,Stars,Address,Street,Locality,Latitude,Longitude,Contact name,Phone,Broker,Listing link,Notes,Carpet area (sq ft),Location source,Deposit,"))
        assertTrue(csv.contains(",2026-10-15,30000,31000,34500,128000,27.0,"), csv)
    }

    @Test
    fun theHtmlPageHasACostBlockAndAnApproximateLine() {
        val html = HtmlWriter.write(bundle)
        val cost = html.substringAfter("<h3>Cost</h3>\n<dl>\n").substringBefore("</dl>")
        assertEquals(
            "<dt>Deposit</dt><dd>₹64,000</dd>\n<dt>Maintenance per month</dt><dd>₹2,500</dd>\n" +
                "<dt>Maintenance included</dt><dd>No</dd>\n<dt>Brokerage (months)</dt><dd>1 months</dd>\n" +
                "<dt>Lock-in (months)</dt><dd>11 months</dd>\n<dt>Notice (months)</dt><dd>2 months</dd>\n" +
                "<dt>Available from</dt><dd>2026-10-15</dd>\n<dt>My offer</dt><dd>₹30,000</dd>\n" +
                "<dt>Agreed price</dt><dd>₹31,000</dd>\n<dt>Monthly cost</dt><dd>₹34,500</dd>\n" +
                "<dt>Money to move in</dt><dd>₹1,28,000</dd>\n<dt>Cost per sq ft</dt><dd>₹27</dd>\n",
            cost,
        )
        assertTrue(html.contains("<dt>Carpet area (sq ft)</dt><dd>1150</dd>"))
        assertTrue(html.contains("<dt>Approximate location</dt><dd>Yes</dd>"))
        // Only the approximate case is worth a line; the second house (no cost) has no Cost block.
        assertEquals(1, html.split("<h3>Cost</h3>").size - 1)
        assertFalse(html.contains("<dd>GPS</dd>"))
        // The comparison table at the top keeps its five columns.
        assertTrue(html.contains("<th scope=\"col\">Rank</th><th scope=\"col\">House</th><th scope=\"col\">Score</th><th scope=\"col\">Price</th><th scope=\"col\">Status</th></tr>"))
    }

    @Test
    fun theMarkdownPageHasTheSameCostBlock() {
        val md = MarkdownWriter.write(bundle)
        assertTrue(md.contains("| Carpet area (sq ft) | 1150 |\n"), md)
        assertTrue(md.contains("| Approximate location | Yes |\n"), md)
        assertTrue(
            md.contains(
                "### Cost\n\n|  |  |\n| --- | --- |\n| Deposit | ₹64,000 |\n| Maintenance per month | ₹2,500 |\n" +
                    "| Maintenance included | No |\n| Brokerage (months) | 1 months |\n| Lock-in (months) | 11 months |\n| Notice (months) | 2 months |\n" +
                    "| Available from | 2026-10-15 |\n| My offer | ₹30,000 |\n| Agreed price | ₹31,000 |\n" +
                    "| Monthly cost | ₹34,500 |\n| Money to move in | ₹1,28,000 |\n| Cost per sq ft | ₹27 |\n",
            ),
            md,
        )
        assertEquals(1, md.split("### Cost").size - 1)
    }

    @Test
    fun aRentWithNoCostHasNoCostBlockAndASaleShowsOnlyWhatItHas() {
        val plainRent = ExportBundle.build(ExportFixture.options(), listOf(ExportFixture.house1), emptyList(), emptyList())
        assertTrue(ExportRows.costLines(ExportFixture.house1, plainRent.strings).isEmpty())
        val sale = ExportFixture.house1.copy(priceType = "SALE", price = 1_250_000, areaSqft = 1450, cost = HouseCost(brokerage = 25_000, agreedPrice = 1_200_000))
        assertEquals(
            listOf("Brokerage" to "₹25,000", "Agreed price" to "₹12,00,000", "Cost per sq ft" to "₹828"),
            ExportRows.costLines(sale, plainRent.strings),
        )
    }
}
