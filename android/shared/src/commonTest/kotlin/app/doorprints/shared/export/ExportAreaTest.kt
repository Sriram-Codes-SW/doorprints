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
 * Area notes and distances in the readable copies (docs/11 "Design of slice 4a"): a house page's *Area notes* (the
 * notes that reach it, newest first, with where each comes from) and *Distances* (place, km with one decimal, nearest
 * first) after the Viewings section and before the checklist, in HTML and Markdown (the PDF writes the same rows);
 * absent when empty; no new table or CSV file.
 */
class ExportAreaTest {
    private val area = ExportArea("a_5b6c7d8e", "Indiranagar 2nd stage", 12.9784, 77.6408, 1200, false, 10)
    private val areaNote = ExportAreaNote("n_11223344", areaId = area.id, text = "Water tanker every morning.", updatedAt = 30)
    private val streetNote = ExportAreaNote("n_55667788", street = "5th cross", text = "Noisy <after> 9 pm.", updatedAt = 40)
    private val goneNote = ExportAreaNote("n_99999999", areaId = "a_gone0000", text = "Never shown.", updatedAt = 50)
    private val office = ExportPlace("p_0a1b2c3d", "Office", 13.0827, 80.2707, 1)
    private val near = ExportPlace("p_4e5f6a7b", "Gym", 12.9716, 77.6408, 2)

    private fun bundle(language: String = "en", withData: Boolean = true) = ExportBundle.build(
        ExportFixture.options(language), ExportFixture.houses, ExportFixture.visits, ExportFixture.photos,
        viewings = listOf(ExportViewing("v_00000001", "h1", 1_790_501_400_000, updatedAt = 1)),
        areas = if (withData) listOf(area) else emptyList(),
        places = if (withData) listOf(office, near) else emptyList(),
        areaNotes = if (withData) listOf(areaNote, streetNote, goneNote) else emptyList(),
    )

    @Test
    fun aHousePageHasAreaNotesThenDistancesAfterTheViewingsAndBeforeTheChecklist() {
        val html = HtmlWriter.write(bundle())
        val notes = html.substringAfter("<h3>Area notes</h3>\n").substringBefore("</table>")
        assertEquals(
            "<table>\n<thead><tr><th scope=\"col\">Notes</th><th scope=\"col\">From</th></tr></thead>\n<tbody>\n" +
                "<tr><th scope=\"row\">Noisy &lt;after&gt; 9 pm.</th><td>5th cross</td></tr>\n" +
                "<tr><th scope=\"row\">Water tanker every morning.</th><td>Indiranagar 2nd stage</td></tr>\n</tbody>\n",
            notes,
        )
        val distances = html.substringAfter("<h3>Distances</h3>\n").substringBefore("</table>")
        assertEquals(
            "<table>\n<thead><tr><th scope=\"col\">Place</th><th scope=\"col\">Distance (km)</th></tr></thead>\n<tbody>\n" +
                "<tr><th scope=\"row\">Gym</th><td>0.7</td></tr>\n<tr><th scope=\"row\">Office</th><td>288.8</td></tr>\n</tbody>\n",
            distances,
        )
        val viewings = html.indexOf("<h3>Viewings</h3>")
        assertTrue(viewings in 0 until html.indexOf("<h3>Area notes</h3>"))
        assertTrue(html.indexOf("<h3>Area notes</h3>") < html.indexOf("<h3>Distances</h3>"))
        assertTrue(html.indexOf("<h3>Distances</h3>") < html.indexOf("<h3>What I checked</h3>"))
        assertFalse(html.contains("Never shown."), "a note whose area is gone reaches no house")
        val md = MarkdownWriter.write(bundle())
        assertTrue(md.contains("\n### Area notes\n\n| Notes | From |\n| --- | --- |\n"), md)
        assertTrue(md.contains("\n### Distances\n\n| Place | Distance (km) |\n| --- | --- |\n| Gym | 0.7 |\n| Office | 288.8 |\n"), md)
        // House 2 (12.9, 77.6, no street) is outside the area: no Area notes there, but its distances.
        assertEquals(emptyList(), ExportRows.areaNoteRows(ExportFixture.house2, bundle()))
        assertEquals(2, ExportRows.distanceRows(ExportFixture.house2, bundle()).size)
    }

    @Test
    fun withoutThemTheSectionsAreAbsentAndNoTableIsAdded() {
        val b = bundle(withData = false)
        assertFalse(HtmlWriter.write(b).contains("<h3>Area notes</h3>"))
        assertFalse(HtmlWriter.write(b).contains("<h3>Distances</h3>"))
        assertFalse(MarkdownWriter.write(b).contains("### Distances"))
        assertEquals(ExportRows.tables(b).map { it.name }, ExportRows.tables(bundle()).map { it.name })
        // A house without a point gets no distances.
        assertEquals(emptyList(), ExportRows.distanceRows(ExportFixture.house1.copy(lat = 0.0, lon = 0.0), bundle()))
    }

    @Test
    fun eachLanguageNamesTheSectionsAndColumns() {
        for ((lang, s) in listOf("hi" to ExportStrings.HI, "ta" to ExportStrings.TA, "te" to ExportStrings.TE)) {
            val html = HtmlWriter.write(bundle(lang))
            for (key in listOf("section.areaNotes", "section.distances")) assertTrue(html.contains("<h3>" + s[key] + "</h3>"), "$lang $key")
            assertTrue(html.contains("<th scope=\"col\">" + s["col.place"] + "</th><th scope=\"col\">" + s["col.km"] + "</th>"), lang)
        }
    }
}
