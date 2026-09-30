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
 * The readable copies with criteria (docs/11 5.4, slice 2): the `criteria` table and sheet, a custom criterion's label
 * in the scores table and on the house page, the ranking by `Ranking` with "Must-have missed", the coverage line and
 * the rating share on the cover.
 */
class ExportCriteriaTest {
    private val pets = ExportCriterion("c_1a2b3c4d", "Pets allowed", 3, false, 3, 10, updatedAt = 2)
    // Parking is a must-have of at least 5: house1's 4 misses it.
    private val parking = ExportCriterion("parking", null, 2, true, 5, 2, updatedAt = 1)
    private val share = ExportPreference("score.ratingShare", "0.4", 3)
    private val house1 = ExportFixture.house1.copy(checklist = ExportFixture.house1.checklist + ("c_1a2b3c4d" to 5))
    private val house3 = ExportFixture.house2.copy(id = "h3", label = "Lake View", checklist = mapOf("water" to 1), rating = 2)

    private fun bundle(language: String = "en", criteria: List<ExportCriterion> = listOf(parking, pets)) = ExportBundle.build(
        ExportFixture.options(language = language), listOf(house1, ExportFixture.house2, house3), ExportFixture.visits,
        ExportFixture.photos, criteria = criteria, preferences = listOf(share),
    )

    @Test
    fun theCriteriaTableListsEveryCriterionWithItsTranslatedWeight() {
        val b = bundle()
        val tables = ExportRows.tables(b)
        assertEquals("criteria", tables.last().name)
        val csv = CsvWriter.write(ExportRows.criteria(b), b.options)
        val lines = csv.split("\r\n")
        assertEquals("Item key,Name,Weight,Must-have,Minimum score,Archived,Order", lines[0])
        assertEquals("water,Water supply,Medium,No,3,No,0", lines[1])
        assertEquals("parking,Parking,Medium,Yes,5,No,2", lines[3])
        assertEquals("c_1a2b3c4d,Pets allowed,High,No,3,No,10", lines[11])
        assertTrue(XlsxWriter.parts(b).first { it.path == "xl/workbook.xml" }.xml.contains("<sheet name=\"Criteria\""))
        // In Tamil the weight is a word of the copy's language.
        val ta = bundle("ta")
        assertEquals(ExportStrings.TA["weight.3"], (ExportRows.criteria(ta).rows.last()[2] as Cell.Text).value)
        // A copy without criterion records has no such table.
        assertFalse(ExportRows.tables(bundle(criteria = emptyList())).any { it.name == "criteria" })
    }

    @Test
    fun aCustomCriterionShowsItsLabelInTheScoresTableAndOnTheHousePage() {
        val b = bundle()
        val scores = CsvWriter.write(ExportRows.scores(b), b.options)
        assertTrue(scores.contains("Sunrise Apartments,c_1a2b3c4d,Pets allowed,5,h1"), scores)
        val html = HtmlWriter.write(b)
        assertTrue(html.contains("<tr><th scope=\"row\">Pets allowed</th><td>5/5</td></tr>"), html)
        assertTrue(MarkdownWriter.write(b).contains("| Pets allowed | 5/5 |"))
    }

    @Test
    fun theRankingPutsAMissedMustHaveLastAndMarksIt() {
        val b = bundle()
        // house1 scores best but misses parking; house3 (scored) comes first, the unscored house2 before house1.
        assertEquals(listOf("h3", "h2", "h1"), b.ranked.map { it.id })
        assertEquals(3, b.rankOf(house1))
        val html = HtmlWriter.write(b)
        // A Must-haves column, because a house misses one: the missed ones by name, "—" for the others.
        assertTrue(html.contains("<th scope=\"col\">Score</th><th scope=\"col\">Must-haves</th><th scope=\"col\">Price</th>"), html)
        assertTrue(html.contains("<tr><td>3</td><th scope=\"row\">Sunrise Apartments</th><td>4.1</td><td>Parking</td><td>₹32,000</td>"), html)
        assertTrue(html.contains("<tr><td>1</td><th scope=\"row\">Lake View</th><td>1.4</td><td>—</td>"), html)
        val md = MarkdownWriter.write(b)
        assertTrue(md.contains("| 3 | Sunrise Apartments | 4.1 | Parking | ₹32,000 | Shortlisted |"), md)
        // Without a missed must-have there is no such column.
        assertFalse(HtmlWriter.write(bundle(criteria = listOf(pets))).contains("Must-haves"))
        assertEquals("4.1 · Must-have missed", ExportRows.rankedScore(house1, b))
        // The house page names the missed must-have and says how much is scored.
        assertTrue(html.contains("<dt>Checked</dt><dd>Scored 4 of 11 that matter</dd>\n<dt>Must-have missed</dt><dd>Parking</dd>"), html)
        // The houses table's rank column follows the same order.
        val houses = ExportRows.houses(b)
        assertEquals(Cell.Count(3), houses.rows.first()[0])
    }

    @Test
    fun theCoverShowsTheRatingShareOnlyWhenTheScoringIsNotTheDefault() {
        val html = HtmlWriter.write(bundle())
        assertTrue(html.contains("<dt>Rating counts for</dt><dd>40%</dd>"), html)
        assertTrue(MarkdownWriter.write(bundle()).contains("- **Rating counts for**: 40%\n"))
        assertFalse(HtmlWriter.write(ExportFixture.bundle()).contains("Rating counts for"))
    }

    @Test
    fun theScoreUsesTheCopysScoring() {
        val b = bundle()
        // house1: water 5 (w2), noise 2 (w2), parking 4 (w2), pets 5 (w3) -> 37/9; rating 4 at 40 %: 0.6 * 37/9 + 1.6.
        assertEquals(0.6 * 37.0 / 9 + 0.4 * 4, b.overallOf(house1)!!, 1e-9)
        assertEquals("4.1", ExportRows.fixed(b.overallOf(house1)!!, 1))
    }
}
