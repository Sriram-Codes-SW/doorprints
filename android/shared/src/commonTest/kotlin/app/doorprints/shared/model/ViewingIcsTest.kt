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

package app.doorprints.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The calendar file of one viewing (docs/11 5.8, design of 2026-09-30): the lines without the optional ones, CRLF
 * endings, the 75-octet fold that never splits a character, and the escaping of text. The byte-for-byte equality
 * with `docs/schemas/viewing-sample.ics` (vector V6) is `ViewingIcsFileTest`, which reads the repository file.
 */
class ViewingIcsTest {
    private val bare = Viewing(
        id = "v_00000001", houseId = "h1", startsAt = 1_790_501_400_000, durationMin = 30, remindMin = 0,
        withWhom = "Ravi Kumar 98450 12345",
    )

    @Test
    fun aViewingWithoutNotesAddressOrReminderHasNoDescriptionLocationOrAlarm() {
        val ics = ViewingIcs.build(bare, "Flat 4", null, dtstampMs = 1_790_072_130_000)
        assertEquals(
            listOf(
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Doorprints//Viewing//EN", "CALSCALE:GREGORIAN", "METHOD:PUBLISH",
                "BEGIN:VEVENT", "UID:v_00000001@doorprints", "DTSTAMP:20260922T101530Z", "DTSTART:20260927T093000Z",
                "DTEND:20260927T100000Z", "SUMMARY:Viewing: Flat 4", "END:VEVENT", "END:VCALENDAR", "",
            ),
            ics.split("\r\n"),
        )
        assertFalse(ics.replace("\r\n", "").contains('\n'), "every line ends with CRLF, none with a bare LF")
        assertFalse(ics.contains("Ravi") || ics.contains("98450"), "withWhom never goes into a calendar file")
        assertFalse(ViewingIcs.build(bare, "Flat 4", "", dtstampMs = 0).contains("LOCATION"))
    }

    @Test
    fun textIsEscapedAndTheWordIsTheLanguagesOwn() {
        assertEquals("a\\\\b\\;c\\,d\\ne", ViewingIcs.escape("a\\b;c,d\r\ne"))
        val ics = ViewingIcs.build(bare.copy(notes = "Gate 2; ask, then\nring"), "A, B", "12, MG Road", "मकान देखना", 0)
        assertTrue(ics.contains("\r\nSUMMARY:मकान देखना: A\\, B\r\n"), ics)
        assertTrue(ics.contains("\r\nLOCATION:12\\, MG Road\r\n"))
        assertTrue(ics.contains("\r\nDESCRIPTION:Gate 2\\; ask\\, then\\nring\r\n"))
    }

    @Test
    fun aLongMultiByteLineFoldsAtSeventyFiveOctetsWithoutSplittingACharacter() {
        val label = "हरा दृश्य 2BHK, एमजी रोड के पास वाला बड़ा मकान"
        val ics = ViewingIcs.build(bare, label, null, dtstampMs = 0)
        val summary = ics.split("\r\n").dropWhile { !it.startsWith("SUMMARY:") }.takeWhile { !it.startsWith("END:") }
        assertTrue(summary.size > 1, "the summary is long enough to fold")
        for ((i, line) in summary.withIndex()) {
            assertTrue(line.encodeToByteArray().size <= 75, "line $i is ${line.encodeToByteArray().size} octets")
            if (i > 0) assertTrue(line.startsWith(" "), "a continuation starts with one space")
            // A whole line of UTF-8 decodes back to itself: no character was cut in two.
            assertEquals(line, line.encodeToByteArray().decodeToString())
        }
        assertEquals("SUMMARY:Viewing: " + ViewingIcs.escape(label), summary.first() + summary.drop(1).joinToString("") { it.drop(1) })
        // A short line is left alone, and a four-byte character (a surrogate pair) moves to the next line whole.
        assertEquals("short", ViewingIcs.fold("short"))
        val emoji = "x".repeat(73) + "😀" + "y"
        val folded = ViewingIcs.fold(emoji).split("\r\n")
        assertEquals(listOf("x".repeat(73), " 😀y"), folded)
    }

    @Test
    fun timesAreUtcToTheSecond() {
        assertEquals("20260927T093000Z", ViewingIcs.utc(1_790_501_400_999))
        assertEquals("19700101T000000Z", ViewingIcs.utc(0))
    }
}
