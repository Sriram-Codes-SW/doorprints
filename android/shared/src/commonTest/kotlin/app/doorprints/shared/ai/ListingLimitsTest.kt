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

package app.doorprints.shared.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4b-BL-182, the phones' side: the warning when the pasted listing holds several links, and the cut of a pasted text
 * longer than Extract reads. The vectors (`listingLinks`, `listingCut`, the `sanitize` cases) are in [ParityVectorsTest];
 * these are the rules that do not fit a table.
 */
class ListingLimitsTest {
    private val severalLinks = "listingUrl: the text has 2 links, check this is the right one"

    @Test
    fun warnsOnlyWhenTheDraftHasALinkAndTheTextHasSeveral() {
        val text = "Flat in Thane West: https://a.example/p/1 and the broker's https://b.example/p/2"
        assertEquals(listOf(severalLinks), DraftSanitizer.sanitize(RawListing(label = "Flat", listingUrl = "https://a.example/p/1"), text).warnings)
        assertEquals(emptyList(), DraftSanitizer.sanitize(RawListing(label = "Flat", listingUrl = null), text).warnings)
        assertEquals(
            emptyList(),
            DraftSanitizer.sanitize(RawListing(label = "Flat", listingUrl = "https://a.example/p/1"), "Flat https://a.example/p/1 https://a.example/p/1").warnings,
        )
    }

    @Test
    fun countsThePastedTextNotTheLinksTheModelWroteInTheNotes() {
        val raw = RawListing(label = "Flat", listingUrl = "https://a.example/k/1", notes = "Also https://b.example/2 https://c.example/3")
        assertEquals(emptyList(), DraftSanitizer.sanitize(raw, "Salt Lake flat https://a.example/k/1 only").warnings)
    }

    @Test
    fun countsLinksInLinearTime() {
        assertEquals(0, DraftSanitizer.linkCount("http:// ".repeat(50_000)))
        assertEquals(2, DraftSanitizer.linkCount("https://a" + "a".repeat(200_000) + " https://b" + ".".repeat(200_000)))
        assertEquals(1, DraftSanitizer.linkCount(("https://x.example/" + "p".repeat(30) + " ").repeat(5_000)))
    }

    @Test
    fun theLimitIsTheOneExtractEnforces() {
        val cap = OnDeviceAi.MAX_INPUT_CHARS
        assertEquals(ListingCut("x".repeat(cap), 0), ListingCut.of("x".repeat(cap)))
        assertEquals(ListingCut("x".repeat(cap), 1), ListingCut.of("x".repeat(cap + 1)))
        val cut = ListingCut.of("y".repeat(cap) + "TAIL")
        assertTrue(cut.text.length <= cap)
        assertEquals(4, cut.leftOut)
    }
}
