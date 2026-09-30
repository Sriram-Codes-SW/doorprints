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
import app.doorprints.shared.model.MoveIn
import app.doorprints.shared.model.MoveInItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice 5 in the readable copies (docs/11 5.7, 5.24): the Moving in section after the Distances and before the
 * checklist, the room, tags and caption under each photo and the three photo columns, and the two status words.
 */
class ExportMovingInTest {
    private val moveIn = MoveIn(
        1_790_812_800_000L, "Keys from Ravi",
        listOf(MoveInItem("mi_police", "Police verification done", sort = 1), MoveInItem("mi_agreement", "Agreement signed", true, 0)),
    )
    private val kitchen = HouseRoom("c1", "KITCHEN", null, sort = 0)
    private val photo = ExportFixture.photo1.copy(roomId = "c1", tags = listOf("MOVE_IN", "damp corner"), caption = "Tap drips", metaUpdatedAt = 5)

    private fun bundle(language: String = "en", photos: List<ExportPhoto> = listOf(photo)) = ExportBundle.build(
        ExportFixture.options(language),
        listOf(ExportFixture.house1.copy(status = "TAKEN", rooms = listOf(kitchen), moveIn = moveIn), ExportFixture.house2.copy(status = "NOT_CHOSEN")),
        ExportFixture.visits, photos,
    )

    @Test
    fun photosCsvHasTheRoomTagsAndCaptionColumns() {
        val b = bundle()
        assertEquals(
            "House,File,Added,House id,Id,Room,Tags,Caption\r\nSunrise Apartments,p1.jpg,2026-09-21 20:50,h1,p1,Kitchen,MOVE_IN; damp corner,Tap drips\r\n",
            CsvWriter.write(ExportRows.photos(b), b.options),
        )
        // A room that is gone reads as none.
        val gone = bundle(photos = listOf(photo.copy(roomId = "zz")))
        assertTrue(CsvWriter.write(ExportRows.photos(gone), gone.options).endsWith("h1,p1,,MOVE_IN; damp corner,Tap drips\r\n"))
    }

    @Test
    fun aHousePageHasMovingInAfterTheDistancesAndBeforeTheChecklist() {
        val html = HtmlWriter.write(bundle())
        assertTrue(
            html.contains(
                "<h3>Moving in</h3>\n<dl>\n<dt>Move-in date</dt><dd>2026-10-01</dd>\n<dt>Notes</dt><dd>Keys from Ravi</dd>\n</dl>\n" +
                    "<ul class=\"move-in\">\n<li>✓ Agreement signed</li>\n<li>○ Police verification done</li>\n</ul>\n",
            ),
            html,
        )
        assertTrue(html.indexOf("<h3>Moving in</h3>") < html.indexOf("<h3>What I checked</h3>"))
        // No photo bytes are given here, so the listing names the file, then its room, tags and caption.
        assertTrue(html.contains("<p class=\"missing\">p1.jpg · Kitchen · MOVE_IN; damp corner · Tap drips</p>"), html)
        val withSrc = HtmlWriter.write(bundle()) { "data:image/jpeg;base64,AA" }
        assertTrue(withSrc.contains("<figcaption>p1.jpg · Kitchen · MOVE_IN; damp corner · Tap drips</figcaption>"), withSrc)
        assertTrue(html.contains(" · Taken</p>") && html.contains(" · Not chosen</p>"), html)
        val md = MarkdownWriter.write(bundle())
        assertTrue(
            md.contains(
                "\n### Moving in\n\n|  |  |\n| --- | --- |\n| Move-in date | 2026-10-01 |\n| Notes | Keys from Ravi |\n\n" +
                    "- ✓ Agreement signed\n- ○ Police verification done\n",
            ),
            md,
        )
        assertTrue(md.contains("- `p1.jpg`: Kitchen · MOVE\\_IN; damp corner · Tap drips\n"), md)
        assertTrue(md.indexOf("### Moving in") < md.indexOf("### What I checked"))
    }

    @Test
    fun aHouseWithoutAMoveInHasNoSectionAndThePhotoHasNoLine() {
        val b = ExportFixture.bundle()
        assertFalse(HtmlWriter.write(b).contains("Moving in"))
        assertFalse(MarkdownWriter.write(b).contains("Moving in"))
        assertEquals(null, ExportRows.photoLine(ExportFixture.photo1, b))
    }

    @Test
    fun theWordsAreInTheCopysLanguage() {
        for (lang in listOf("en", "hi", "ta", "te")) {
            val s = ExportStrings.of(lang)
            val html = HtmlWriter.write(bundle(lang))
            assertTrue(html.contains("<h3>" + s["section.movingIn"] + "</h3>"), lang)
            assertTrue(html.contains(s.status("TAKEN")) && html.contains(s.status("NOT_CHOSEN")), lang)
            assertEquals(listOf(s["col.room"], s["col.tags"], s["col.caption"]), ExportRows.photos(bundle(lang)).columns.takeLast(3))
        }
    }
}
