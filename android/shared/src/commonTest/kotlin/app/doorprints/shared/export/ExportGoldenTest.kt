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
import kotlin.test.assertTrue

/**
 * Golden-file tests for the six offline copies (S4-02). They compare the whole output, not a few fields, because
 * the point of a golden is to notice an accidental change — a moved column, a lost escape, a different date — that
 * a targeted assertion would let through. The web exporters (S4-03) are held to the same strings.
 */
class ExportGoldenTest {

    private val bundle = ExportFixture.bundle()

    @Test
    fun housesCsvMatchesTheGolden() {
        assertEquals(ExportGolden.HOUSES_CSV, CsvWriter.write(ExportRows.houses(bundle), bundle.options))
    }

    @Test
    fun scoresCsvMatchesTheGolden() {
        assertEquals(ExportGolden.SCORES_CSV, CsvWriter.write(ExportRows.scores(bundle), bundle.options))
    }

    @Test
    fun visitsCsvMatchesTheGolden() {
        assertEquals(ExportGolden.VISITS_CSV, CsvWriter.write(ExportRows.visits(bundle), bundle.options))
    }

    @Test
    fun photosCsvMatchesTheGolden() {
        assertEquals(ExportGolden.PHOTOS_CSV, CsvWriter.write(ExportRows.photos(bundle), bundle.options))
    }

    @Test
    fun markdownMatchesTheGolden() {
        assertEquals(ExportGolden.MARKDOWN, MarkdownWriter.write(bundle))
    }

    @Test
    fun housesSheetMatchesTheGolden() {
        val sheet = XlsxWriter.parts(bundle).first { it.path == "xl/worksheets/sheet1.xml" }
        assertEquals(ExportGolden.HOUSES_SHEET_XML, sheet.xml)
    }

    @Test
    fun htmlMatchesTheGolden() {
        val html = HtmlWriter.write(bundle, ExportFixture.fakePhotoSrc)
        assertEquals(ExportGolden.HTML_WITHOUT_CSS, withoutCss(html))
    }

    /** The CSS is presentation (Design Director); everything around it is the contract the golden pins. */
    private fun withoutCss(html: String): String =
        html.substringBefore("<style>\n") + html.substringAfter("</style>\n")

    @Test
    fun htmlKeepsItsStyleBlockAndContentSecurityPolicy() {
        val html = HtmlWriter.write(bundle, ExportFixture.fakePhotoSrc)
        assertTrue(html.contains("<style>\n"), "the style block must still be inline")
        assertTrue(
            html.contains("content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'\""),
            "the CSP meta tag is what keeps the copy inert",
        )
        // No script of any kind, and no way to reach the network from the saved file.
        assertTrue(!html.contains("<script"), "an exported copy must never contain JavaScript")
        assertTrue(!html.contains("<a href"), "an exported copy must not carry clickable links")
        assertTrue(!html.contains("http-equiv=\"refresh\""))
    }
}
