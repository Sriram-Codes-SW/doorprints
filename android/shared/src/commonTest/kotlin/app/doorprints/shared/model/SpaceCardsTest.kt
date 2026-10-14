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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chooser's cards (kinds PR C, S4b-BL-274; docs/03 §18.12): the twin of the website's `spaceCards` spec. One card per kind
 * that is on, the default first, each with its count and its newest change; a kind that is off but still has entries is a
 * hidden card (never dropped); a candidate of a kind the build does not have counts in the default space.
 */
class SpaceCardsTest {
    private val both = listOf("house", "school")

    private val rows = listOf(
        SpaceRow(null, 1_000), SpaceRow("house", 3_000), SpaceRow("school", 2_000), SpaceRow("school", 5_000), SpaceRow("school", 4_000),
    )

    @Test
    fun theDefaultComesFirstThenTheOthersInTheBuildsOrder() {
        val houseFirst = spaceCards(rows, enabled = both, default = "house", available = both).shown
        assertEquals(listOf("house", "school"), houseFirst.map { it.kind })
        val schoolFirst = spaceCards(rows, enabled = both, default = "school", available = both).shown
        assertEquals(listOf("school", "house"), schoolFirst.map { it.kind })
        assertEquals(listOf(true, false), schoolFirst.map { it.isDefault })
    }

    @Test
    fun eachCardHasItsCountAndItsNewestChange() {
        val shown = spaceCards(rows, both, "house", both).shown.associateBy { it.kind }
        assertEquals(2, shown.getValue("house").count)
        assertEquals(3_000L, shown.getValue("house").updated)
        assertEquals(3, shown.getValue("school").count)
        assertEquals(5_000L, shown.getValue("school").updated)
    }

    @Test
    fun aKindWithNothingSavedHasACardWithNoDate() {
        val card = spaceCards(listOf(SpaceRow(null, 7)), both, "house", both).shown.single { it.kind == "school" }
        assertEquals(0, card.count)
        assertNull(card.updated)
    }

    @Test
    fun aKindThatIsOffButHasEntriesIsAHiddenCardAndOneWithNoneIsNot() {
        val cards = spaceCards(rows, enabled = listOf("house"), default = "house", available = both)
        assertEquals(listOf("house"), cards.shown.map { it.kind })
        assertEquals(listOf("school"), cards.hidden.map { it.kind })
        assertEquals(3, cards.hidden.single().count)
        assertTrue(spaceCards(listOf(SpaceRow(null, 1)), listOf("house"), "house", both).hidden.isEmpty())
    }

    @Test
    fun aCandidateOfAKindTheBuildDoesNotHaveCountsInTheDefaultSpace() {
        val cards = spaceCards(listOf(SpaceRow("clinic", 9), SpaceRow(null, 1)), both, "school", both)
        assertEquals(1, cards.shown.single { it.kind == "house" }.count)
        assertEquals(1, cards.shown.single { it.kind == "school" }.count)
        assertEquals(mapOf("school" to 1, "house" to 1), spaceCounts(listOf(SpaceRow("clinic", 9), SpaceRow(null, 1)), "school", both))
    }

    @Test
    fun theCountsOfWhatYouTrackFollowTheSameRule() {
        assertEquals(mapOf("house" to 2, "school" to 3), spaceCounts(rows, "house", both))
        assertEquals(mapOf("house" to 5), spaceCounts(rows, "house", listOf("house")))
    }
}
