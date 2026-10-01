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

import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.LengthUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rooms in the readable copies (docs/11 5.6, slice 1c): the `rooms` column after the cost per sq ft, `rooms.csv` and
 * the Rooms sheet in the unit of the device that makes the copy, and a house page's Rooms table with its total.
 */
class ExportRoomTest {
    private val master = HouseRoom("m", "BEDROOM", "Master bedroom", 396, 366, 4, "Damp patch", 0)
    private val kitchen = HouseRoom("k", "KITCHEN", null, 300, 244, null, null, 1)
    private val store = HouseRoom("s", "STORE", "Box room", 150, null, null, null, 2)
    private val rooms = listOf(master, kitchen, store)

    private fun bundle(unit: LengthUnit = LengthUnit.FT, language: String = "en", rooms: List<HouseRoom>? = this.rooms) =
        ExportBundle.build(
            ExportFixture.options(language).copy(lengthUnit = unit),
            listOf(ExportFixture.house1.copy(rooms = rooms), ExportFixture.house2), ExportFixture.visits, ExportFixture.photos,
        )

    @Test
    fun theRoomsColumnFollowsTheCostPerSqFtAndCountsTheRooms() {
        val b = bundle()
        val table = ExportRows.houses(b)
        val at = table.columns.indexOf("Cost per sq ft") + 1
        assertEquals("Rooms", table.columns[at])
        // S4b-BL-87: the floor between the rooms and the visits.
        assertEquals("Floor", table.columns[at + 1])
        assertEquals("Visits", table.columns[at + 2])
        assertEquals(listOf("3", ""), table.rows.map { ExportRows.plain(it[at], b.options) })
        assertEquals(table.columns.size, table.rows.first().size)
    }

    @Test
    fun roomsCsvIsInFeetWithTheTranslatedTypeForABlankName() {
        val b = bundle()
        val table = ExportRows.rooms(b)
        assertEquals("rooms", table.name)
        assertEquals(
            "House,Room,Type,Length (ft),Width (ft),Area (sq ft),Condition,Notes,House id,Id\r\n" +
                "Sunrise Apartments,Master bedroom,Bedroom,13.0,12.0,156,4,Damp patch,h1,m\r\n" +
                "Sunrise Apartments,Kitchen,Kitchen,9.8,8.0,79,,,h1,k\r\n" +
                "Sunrise Apartments,Box room,Store room,4.9,,,,,h1,s\r\n",
            CsvWriter.write(table, b.options),
        )
        assertEquals(listOf("houses", "scores", "visits", "photos", "rooms"), ExportRows.tables(b).map { it.name })
        assertTrue(XlsxWriter.parts(b).first { it.path == "xl/workbook.xml" }.xml.contains("name=\"Rooms\""))
    }

    @Test
    fun roomsCsvInMetresHasTwoDecimalsAndSquareMetres() {
        val b = bundle(LengthUnit.M)
        val csv = CsvWriter.write(ExportRows.rooms(b), b.options)
        assertTrue(csv.startsWith("House,Room,Type,Length (m),Width (m),Area (m²),Condition,Notes,House id,Id\r\n"), csv)
        assertTrue(csv.contains("Sunrise Apartments,Master bedroom,Bedroom,3.96,3.66,14.5,4,Damp patch,h1,m\r\n"), csv)
    }

    @Test
    fun aCopyWithoutRoomsHasNoRoomsTable() {
        val b = bundle(rooms = null)
        assertEquals(4, ExportRows.tables(b).size)
        assertFalse(HtmlWriter.write(b).contains("<h3>Rooms</h3>"))
        assertFalse(MarkdownWriter.write(b).contains("### Rooms"))
    }

    @Test
    fun aHousePageHasTheRoomsTableBeforeTheChecklistAndTheTotalFromTwoSizedRooms() {
        val html = HtmlWriter.write(bundle())
        val table = html.substringAfter("<h3>Rooms</h3>\n").substringBefore("</table>")
        assertEquals(
            "<table>\n<thead><tr><th scope=\"col\">Room</th><th scope=\"col\">Size</th><th scope=\"col\">Area (sq ft)</th>" +
                "<th scope=\"col\">Condition</th><th scope=\"col\">Notes</th></tr></thead>\n<tbody>\n" +
                "<tr><th scope=\"row\">Master bedroom</th><td>13 ft 0 in × 12 ft 0 in</td><td>156</td><td>4/5</td><td>Damp patch</td></tr>\n" +
                "<tr><th scope=\"row\">Kitchen</th><td>9 ft 10 in × 8 ft 0 in</td><td>79</td><td>—</td><td></td></tr>\n" +
                "<tr><th scope=\"row\">Box room</th><td>—</td><td>—</td><td>—</td><td></td></tr>\n" +
                "<tr><th scope=\"row\">Total</th><td></td><td>235</td><td></td><td></td></tr>\n</tbody>\n",
            table,
        )
        assertTrue(html.indexOf("<h3>Rooms</h3>") < html.indexOf("<h3>What I checked</h3>"))
        val md = MarkdownWriter.write(bundle(LengthUnit.M))
        assertTrue(
            md.contains(
                "\n### Rooms\n\n| Room | Size | Area (m²) | Condition | Notes |\n| --- | --- | --- | --- | --- |\n" +
                    "| Master bedroom | 3.96 m × 3.66 m | 14.5 | 4/5 | Damp patch |\n" +
                    "| Kitchen | 3.00 m × 2.44 m | 7.3 | — |  |\n| Box room | — | — | — |  |\n| Total |  | 21.8 |  |  |\n",
            ),
            md,
        )
        assertTrue(md.indexOf("### Rooms") < md.indexOf("### What I checked"))
        // One sized room: no total.
        assertFalse(HtmlWriter.write(bundle(rooms = listOf(master, store))).contains("<th scope=\"row\">Total</th>"))
    }

    @Test
    fun anExportInTamilNamesTheRoomsInTamil() {
        val html = HtmlWriter.write(bundle(language = "ta"))
        assertTrue(html.contains("<h3>" + ExportStrings.TA["table.rooms"] + "</h3>"))
        assertTrue(html.contains("<th scope=\"row\">" + ExportStrings.TA["roomType.KITCHEN"] + "</th>"))
    }
}
