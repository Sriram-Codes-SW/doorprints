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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pentest observation O-1 (docs/ops/pentest-2026-10-21.md): the website's Markdown copy escaped a question's answer twice. The phones
 * never did (one `cell` escape per table cell); this holds that, with the same input as the website's `markdown-escape-once.spec.ts`,
 * and holds the injection characters neutralised once.
 */
class MarkdownAnswerEscapeTest {
    private fun line(answer: String): String {
        val house = ExportFixture.house1.copy(
            answers = listOf(HouseAnswer(id = "a1", text = "Is it quiet?", answer = answer, status = "ANSWERED", sort = 0)),
        )
        val bundle = ExportBundle.build(ExportOptions(language = "en", exportedAtMillis = 1_790_072_130_000), listOf(house), emptyList(), emptyList())
        return MarkdownWriter.write(bundle).lines().first { it.startsWith("| Is it quiet?") }
    }

    @Test
    fun anAnswerIsEscapedOnce() {
        val l = line("<img src=x")
        assertTrue(l.contains("| \\<img src=x |"), l)
        assertFalse(l.contains("\\\\"), l)
    }

    @Test
    fun markupLinksPipesAndHeadingMarkersStayNeutralised() {
        assertTrue(line("[click](javascript:alert(1)) | *bold* `x` =SUM(A1)").contains("\\[click\\](javascript:alert(1)) \\| \\*bold\\* \\`x\\` =SUM(A1)"))
        assertTrue(line("# not a heading").contains("| \\# not a heading |"))
        assertTrue(line("a\\b").contains("| a\\\\b |"))
        assertEquals(4, line("x | y").replace("\\|", "").count { it == '|' })
    }
}
