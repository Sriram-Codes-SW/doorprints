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

package app.doorprints.shared.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TC-U-249 (docs/06): where a transcript lands in the box (voice PR 5, S4b-BL-222); the twin of the website's `transcript-insert.spec.ts`. */
class TranscriptInsertTest {
    @Test
    fun putsTheWordsInAnEmptyBoxAsTheyAre() {
        val r = appendTranscript("", " two bhk near Baner under 28 thousand ")
        assertEquals("two bhk near Baner under 28 thousand", r.text)
        assertFalse(r.cut)
    }

    @Test
    fun addsOneSpaceAfterTextThatDoesNotEndInWhiteSpaceNoneAfterTextThatDoes() {
        assertEquals("Which flat is cheapest?", appendTranscript("Which flat", "is cheapest?").text)
        assertEquals("Which flat is cheapest?", appendTranscript("Which flat ", "is cheapest?").text)
        assertEquals("Line one\nline two", appendTranscript("Line one\n", "line two").text)
    }

    @Test
    fun addsNothingForEmptyOrBlankSpeech() {
        val r = appendTranscript("keep", "   ")
        assertEquals("keep", r.text)
        assertFalse(r.cut)
    }

    @Test
    fun cutsAtTheBoxLimitNeverTouchingWhatWasTypedAndSaysItCut() {
        appendTranscript("abcde", "fghij klmno", 12).let { assertEquals("abcde fghij", it.text); assertTrue(it.cut) }
        appendTranscript("abcdefghij", "klm", 4).let { assertEquals("abcdefghij", it.text); assertTrue(it.cut) }
        appendTranscript("abc", "def", 7).let { assertEquals("abc def", it.text); assertFalse(it.cut) }
    }

    @Test
    fun leavesTheWordsAsOrdinaryTextANumberSaidAloudIsNotRedactedHere() {
        assertEquals("call 98450 12345", appendTranscript("", "call 98450 12345").text)
    }
}
