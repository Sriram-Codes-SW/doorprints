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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Brokers in the copies (docs/11 5.25, slice 1b): the `broker` column after the phone, `brokers.csv` and the Brokers
 * sheet, a house page's Broker row and the Brokers section of HTML and Markdown, and all of it left out with no contact
 * details.
 */
class ExportBrokerTest {
    private val ravi = ExportBroker(
        id = "b-ravi", name = "Ravi Kumar", phone = "+91 98450 00000", agency = "Adyar Homes",
        feeTerms = "15 days' rent, once", notes = "Replies fast", rating = 4, updatedAt = 1_790_072_130_000,
    )
    private val meena = ExportBroker(id = "b-meena", name = "Meena Iyer", updatedAt = 1_790_000_000_000)
    private val house1 = ExportFixture.house1.copy(brokerId = "b-ravi")
    private val house2 = ExportFixture.house2.copy(brokerId = "b-meena")

    private fun bundle(options: ExportOptions = ExportFixture.options()) =
        ExportBundle.build(options, listOf(house1, house2), ExportFixture.visits, ExportFixture.photos, listOf(ravi, meena))

    @Test
    fun theBrokerColumnFollowsThePhoneAndReadsNameAndAgency() {
        val b = bundle()
        val table = ExportRows.houses(b)
        val phone = table.columns.indexOf("Phone")
        assertEquals("Broker", table.columns[phone + 1])
        assertEquals("Listing link", table.columns[phone + 2])
        assertEquals(
            listOf("Ravi Kumar (Adyar Homes)", "Meena Iyer"),
            table.rows.map { ExportRows.plain(it[phone + 1], b.options) },
        )
        // A house with no broker, or a broker that is not in the copy, is blank.
        val loose = ExportBundle.build(ExportFixture.options(), listOf(house1, ExportFixture.house2), emptyList(), emptyList(), listOf(meena))
        assertEquals(listOf("", ""), ExportRows.houses(loose).rows.map { ExportRows.plain(it[phone + 1], loose.options) })
    }

    @Test
    fun theBrokersTableHasItsColumnsAndTheCountOfHousesInTheCopy() {
        val b = bundle()
        val table = ExportRows.brokers(b)
        assertEquals("brokers", table.name)
        assertEquals(listOf("Name", "Phone", "Agency", "Fee terms", "Notes", "Stars", "Houses", "Id"), table.columns)
        // Ordered by updatedAt then id, as in a backup.
        assertEquals(
            listOf(
                "Meena Iyer,,,,,,1,b-meena",
                "Ravi Kumar,+91 98450 00000,Adyar Homes,15 days' rent, once,Replies fast,4,1,b-ravi",
            ),
            table.rows.map { row -> row.joinToString(",") { ExportRows.plain(it, b.options) } },
        )
        assertEquals(listOf("houses", "scores", "visits", "photos", "brokers"), ExportRows.tables(b).map { it.name })
        assertEquals(4, ExportRows.tables(ExportFixture.bundle()).size)
        // The XLSX has a sheet for them.
        val parts = XlsxWriter.parts(b).map { it.path }
        assertTrue("xl/worksheets/sheet5.xml" in parts, parts.toString())
        assertTrue(XlsxWriter.parts(b).first { it.path == "xl/workbook.xml" }.xml.contains("name=\"Brokers\""))
    }

    @Test
    fun aHousePageHasTheBrokerRowAndTheBrokersSectionListsTheirHouses() {
        val html = HtmlWriter.write(bundle())
        assertTrue(html.contains("<dt>Broker</dt><dd>Ravi Kumar (Adyar Homes)</dd>"), html)
        val section = html.substringAfter("<h2>Brokers</h2>\n")
        assertTrue(section.startsWith("<h3>Meena Iyer</h3>\n<dl>\n<dt>Houses</dt><dd>Green View | Block &quot;B&quot;</dd>\n</dl>\n<h3>Ravi Kumar</h3>"), section)
        assertTrue(section.contains("<dt>Fee terms</dt><dd>15 days&#39; rent, once</dd>"), section)
        assertTrue(section.contains("<dt>Stars</dt><dd>4</dd>"))
        assertTrue(section.contains("<dt>Houses</dt><dd>Sunrise Apartments</dd>"))

        val md = MarkdownWriter.write(bundle())
        assertTrue(md.contains("- Broker: Ravi Kumar (Adyar Homes)\n"), md)
        assertTrue(md.contains("\n## Brokers\n\n### Meena Iyer\n\n"), md)
        assertTrue(md.contains("| Agency | Adyar Homes |\n"), md)
    }

    @Test
    fun aCopyWithoutBrokersIsWrittenAsBefore() {
        val html = HtmlWriter.write(ExportFixture.bundle())
        assertFalse(html.contains("<h2>Brokers</h2>"))
        assertFalse(html.contains("<dt>Broker</dt>"))
        assertFalse(MarkdownWriter.write(ExportFixture.bundle()).contains("## Brokers"))
    }

    @Test
    fun withoutContactDetailsTheCopyHasNoBrokersAndNoLinks() {
        val b = bundle(ExportFixture.options(includeContacts = false))
        assertTrue(b.brokers.isEmpty())
        assertTrue(b.houses.all { it.brokerId == null })
        assertFalse(ExportRows.houses(b).columns.contains("Broker"))
        assertEquals(4, ExportRows.tables(b).size)
        assertFalse(HtmlWriter.write(b).contains("Brokers"))
        assertNull(BackupData.of(b).brokers)
    }

    @Test
    fun aPartialCopyKeepsOnlyTheBrokersItsHousesUse() {
        val shortlist = bundle(ExportFixture.options(scope = ExportScope.SHORTLISTED))
        assertEquals(listOf("b-ravi"), shortlist.brokers.map { it.id })
        assertEquals(listOf("b-ravi", "b-meena").sorted(), bundle().brokers.map { it.id }.sorted())
    }

    @Test
    fun anUpdateHasTheBrokersChangedSinceAndTheOnesItsHousesNameOnly() {
        val since = ExportFixture.options().copy(since = 1_790_050_000_000)
        val update = ExportBundle.build(since, listOf(house1, house2), emptyList(), emptyList(), listOf(ravi, meena))
        // house1 changed after `since` and names Ravi (changed too); Meena did not change and house2 did not either.
        assertEquals(listOf("b-ravi"), update.brokers.map { it.id })
    }
}
