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

import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.HouseRoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The questions asked in the readable copies (docs/11 5.5, slice 3a): a house page's Questions table after the Rooms
 * and before the checklist (HTML, Markdown; the PDF writes the same rows), open ones first with "–" for no answer;
 * `answers.csv` and the Answers sheet only when a house has answers, by house then the order shown; no new column in the
 * houses table and no `questions.csv`.
 */
class ExportAnswerTest {
    private val answered = HouseAnswer("a1", "qd_water", "Where does the water come from?", "Borewell, 24 hours", "ANSWERED", 0)
    private val open = HouseAnswer("a2", null, "Is the terrace open?", null, "OPEN", 2)
    private val skipped = HouseAnswer("a3", "qd_pets", "Are pets allowed?", null, "SKIPPED", 1)
    private val answers = listOf(answered, open, skipped)

    private fun bundle(language: String = "en", answers: List<HouseAnswer>? = this.answers) = ExportBundle.build(
        ExportFixture.options(language),
        listOf(
            ExportFixture.house1.copy(answers = answers, rooms = listOf(HouseRoom("r1", "HALL", "Hall"))),
            ExportFixture.house2.copy(answers = listOf(HouseAnswer("b1", text = "Which floor?"))),
        ),
        ExportFixture.visits, ExportFixture.photos,
    )

    @Test
    fun answersCsvListsEveryQuestionByHouseOpenFirst() {
        val b = bundle()
        val table = ExportRows.answers(b)
        assertEquals("answers", table.name)
        assertEquals("Answers", table.title)
        assertEquals(
            "House,Question,Answer,Status,House id,Id,Question id\r\n" +
                "Sunrise Apartments,Is the terrace open?,,Open,h1,a2,\r\n" +
                "Sunrise Apartments,Where does the water come from?,\"Borewell, 24 hours\",Answered,h1,a1,qd_water\r\n" +
                "Sunrise Apartments,Are pets allowed?,,Skipped,h1,a3,qd_pets\r\n" +
                "\"Green View | Block \"\"B\"\"\",Which floor?,,Open,h2,b1,\r\n",
            CsvWriter.write(table, b.options),
        )
        assertEquals(listOf("houses", "scores", "visits", "photos", "rooms", "answers"), ExportRows.tables(b).map { it.name })
        assertTrue(XlsxWriter.parts(b).first { it.path == "xl/workbook.xml" }.xml.contains("name=\"Answers\""))
        // The houses table gets no new column.
        assertEquals(ExportRows.houses(ExportFixture.bundle()).columns, ExportRows.houses(b).columns)
    }

    @Test
    fun aCopyWithoutAnswersHasNoAnswersTableAndNoQuestionsSection() {
        val b = ExportFixture.bundle()
        assertFalse(ExportRows.tables(b).any { it.name == "answers" })
        assertFalse(HtmlWriter.write(b).contains("<h3>Questions</h3>"))
        assertFalse(MarkdownWriter.write(b).contains("### Questions"))
    }

    @Test
    fun aHousePageHasTheQuestionsTableAfterTheRoomsAndBeforeTheChecklist() {
        val html = HtmlWriter.write(bundle())
        val table = html.substringAfter("<h3>Questions</h3>\n").substringBefore("</table>")
        assertEquals(
            "<table>\n<thead><tr><th scope=\"col\">Question</th><th scope=\"col\">Answer</th><th scope=\"col\">Status</th></tr></thead>\n<tbody>\n" +
                "<tr><th scope=\"row\">Is the terrace open?</th><td>–</td><td>Open</td></tr>\n" +
                "<tr><th scope=\"row\">Where does the water come from?</th><td>Borewell, 24 hours</td><td>Answered</td></tr>\n" +
                "<tr><th scope=\"row\">Are pets allowed?</th><td>–</td><td>Skipped</td></tr>\n</tbody>\n",
            table,
        )
        assertTrue(html.indexOf("<h3>Rooms</h3>") < html.indexOf("<h3>Questions</h3>"))
        assertTrue(html.indexOf("<h3>Questions</h3>") < html.indexOf("<h3>What I checked</h3>"))
        val md = MarkdownWriter.write(bundle())
        assertTrue(
            md.contains(
                "\n### Questions\n\n| Question | Answer | Status |\n| --- | --- | --- |\n| Is the terrace open? | – | Open |\n" +
                    "| Where does the water come from? | Borewell, 24 hours | Answered |\n| Are pets allowed? | – | Skipped |\n",
            ),
            md,
        )
        assertTrue(md.indexOf("### Rooms") < md.indexOf("### Questions") && md.indexOf("### Questions") < md.indexOf("### What I checked"))
    }

    @Test
    fun aCopyInEachLanguageNamesTheSectionAndTheStatusesInIt() {
        for ((lang, s) in listOf("hi" to ExportStrings.HI, "ta" to ExportStrings.TA, "te" to ExportStrings.TE)) {
            val html = HtmlWriter.write(bundle(lang))
            assertTrue(html.contains("<h3>" + s["section.questions"] + "</h3>"), lang)
            assertTrue(html.contains("<td>" + s["answerStatus.ANSWERED"] + "</td>"), lang)
            assertEquals(s["table.answers"], ExportRows.answers(bundle(lang)).title)
        }
    }
}
