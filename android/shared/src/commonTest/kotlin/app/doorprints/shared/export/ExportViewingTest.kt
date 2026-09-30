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
import kotlin.test.assertTrue

/**
 * Viewings in the readable copies (docs/11 5.8, slice 3b-1): a house page's Viewings table after the Questions and
 * before the checklist (HTML, Markdown; the PDF writes the same rows), upcoming PLANNED ones first and then the rest
 * newest first, with whom only with contact details; `viewings.csv` and the Viewings sheet only when the copy has a
 * viewing, by start then id; no new column in the houses table.
 */
class ExportViewingTest {
    private val done = ExportViewing(
        "v_3c4d5e6f", "h1", 1_788_604_800_000, 30, "FIRST", "DONE", 60, withWhom = "Ravi Kumar", visitId = "v1",
        updatedAt = 1_788_606_600_000,
    )
    private val planned = ExportViewing(
        "v_a1b2c3d4", "h1", 1_790_501_400_000, 45, "SECOND", "PLANNED", 30, notes = "Ask for the water bill, please",
        updatedAt = 1_790_072_130_000,
    )
    private val older = ExportViewing("v_00000002", "h1", 1_788_000_000_000, 30, "FOLLOW_UP", "CANCELLED", 0, updatedAt = 1)
    private val gone = ExportViewing("v_00000003", "gone", 1_789_000_000_000, 60, "FIRST", "PLANNED", 60, updatedAt = 2)

    private fun bundle(language: String = "en", contacts: Boolean = true, viewings: List<ExportViewing> = listOf(done, planned, older, gone)) =
        ExportBundle.build(
            ExportFixture.options(language, includeContacts = contacts), ExportFixture.houses, ExportFixture.visits, ExportFixture.photos,
            viewings = viewings,
        )

    @Test
    fun viewingsCsvListsEveryViewingByStartThenId() {
        val b = bundle()
        val table = ExportRows.viewings(b)
        assertEquals("viewings", table.name)
        assertEquals("Viewings", table.title)
        assertEquals(
            "House,When,Duration (minutes),Kind,Status,Reminder (minutes before),With whom,Notes,House id,Id,Visit id\r\n" +
                "Sunrise Apartments,2026-08-29 16:10,30,Follow-up,Cancelled,0,,,h1,v_00000002,\r\n" +
                "Sunrise Apartments,2026-09-05 16:10,30,First viewing,Done,60,Ravi Kumar,,h1,v_3c4d5e6f,v1\r\n" +
                ",2026-09-10 05:56,60,First viewing,Planned,60,,,gone,v_00000003,\r\n" +
                "Sunrise Apartments,2026-09-27 15:00,45,Second viewing,Planned,30,,\"Ask for the water bill, please\",h1,v_a1b2c3d4,\r\n",
            CsvWriter.write(table, b.options),
        )
        assertEquals(listOf("houses", "scores", "visits", "photos", "viewings"), ExportRows.tables(b).map { it.name })
        assertTrue(XlsxWriter.parts(b).first { it.path == "xl/workbook.xml" }.xml.contains("name=\"Viewings\""))
        assertEquals(ExportRows.houses(ExportFixture.bundle()).columns, ExportRows.houses(b).columns)
        // Without contact details the column stays, blank.
        assertFalse(CsvWriter.write(ExportRows.viewings(bundle(contacts = false)), b.options).contains("Ravi"))
    }

    @Test
    fun aCopyWithoutViewingsHasNoTableAndNoSection() {
        val b = bundle(viewings = emptyList())
        assertFalse(ExportRows.tables(b).any { it.name == "viewings" })
        assertFalse(HtmlWriter.write(b).contains("<h3>Viewings</h3>"))
        assertFalse(MarkdownWriter.write(b).contains("### Viewings"))
    }

    @Test
    fun aHousePageHasTheViewingsTableUpcomingFirstThenNewestBeforeTheChecklist() {
        val html = HtmlWriter.write(bundle())
        val table = html.substringAfter("<h3>Viewings</h3>\n").substringBefore("</table>")
        assertEquals(
            "<table>\n<thead><tr><th scope=\"col\">When</th><th scope=\"col\">Kind</th><th scope=\"col\">Status</th>" +
                "<th scope=\"col\">Notes</th><th scope=\"col\">With whom</th></tr></thead>\n<tbody>\n" +
                "<tr><th scope=\"row\">2026-09-27 15:00</th><td>Second viewing</td><td>Planned</td><td>Ask for the water bill, please</td><td>–</td></tr>\n" +
                "<tr><th scope=\"row\">2026-09-05 16:10</th><td>First viewing</td><td>Done</td><td>–</td><td>Ravi Kumar</td></tr>\n" +
                "<tr><th scope=\"row\">2026-08-29 16:10</th><td>Follow-up</td><td>Cancelled</td><td>–</td><td>–</td></tr>\n</tbody>\n",
            table,
        )
        assertTrue(html.indexOf("<h3>Viewings</h3>") < html.indexOf("<h3>What I checked</h3>"))
        val md = MarkdownWriter.write(bundle(contacts = false))
        assertTrue(
            md.contains(
                "\n### Viewings\n\n| When | Kind | Status | Notes |\n| --- | --- | --- | --- |\n" +
                    "| 2026-09-27 15:00 | Second viewing | Planned | Ask for the water bill, please |\n",
            ),
            md,
        )
        assertFalse(md.contains("With whom") || md.contains("Ravi"))
    }

    @Test
    fun aCopyInEachLanguageNamesTheSectionKindsAndStatuses() {
        for ((lang, s) in listOf("hi" to ExportStrings.HI, "ta" to ExportStrings.TA, "te" to ExportStrings.TE)) {
            val html = HtmlWriter.write(bundle(lang))
            assertTrue(html.contains("<h3>" + s["section.viewings"] + "</h3>"), lang)
            assertTrue(html.contains("<td>" + s["viewingKind.SECOND"] + "</td><td>" + s["viewingStatus.PLANNED"] + "</td>"), lang)
            assertEquals(s["table.viewings"], ExportRows.viewings(bundle(lang)).title)
        }
    }
}
