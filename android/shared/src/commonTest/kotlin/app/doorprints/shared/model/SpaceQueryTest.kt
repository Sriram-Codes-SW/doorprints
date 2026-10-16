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
import kotlin.test.assertTrue

/**
 * The store query of a space ([query], S4b-BL-303) selects exactly the candidates the in-memory rule keeps ([SpaceView.holds],
 * `space-scope-vectors.json`): every kind (a house, a school, a kind this build does not have, none), in every space, with
 * every default, with one kind on and with two. The tallies make the same cards as the rows do.
 */
class SpaceQueryTest {
    private val kinds = listOf("house", "school", "clinic", null)
    private val both = listOf("house", "school")

    private fun SpaceQuery.keeps(candidate: String?): Boolean {
        val kind = candidate ?: KindSettingRules.HOUSE
        return if (except) kind !in this.kinds else kind in this.kinds
    }

    @Test
    fun theQuerySelectsWhatTheRuleHolds() {
        val views = buildList {
            add(SpaceView.ONE)
            for (default in both) for (space in both + "clinic") add(SpaceView(space, multi = true, default = default, available = both))
            add(SpaceView("house", multi = true, default = "house", available = listOf("house", "school", "clinic")))
            add(SpaceView("school", multi = false, default = "house", available = both))
        }
        for (view in views) for (kind in kinds) {
            assertEquals(view.holds(kind), view.query().keeps(kind), "$view holds $kind")
        }
    }

    @Test
    fun theShapesOfTheQuery() {
        assertEquals(SpaceQuery(emptyList(), except = true), SpaceView.ONE.query(), "one kind: every row")
        assertEquals(SpaceQuery(listOf("school"), except = true), SpaceView("house", true, "house", both).query(), "the default: every kind but the others")
        assertEquals(SpaceQuery(listOf("school"), except = false), SpaceView("school", true, "house", both).query())
        assertTrue(SpaceView("clinic", true, "house", both).query().none, "a space that is not a kind of the build and not the default holds nothing")
    }

    @Test
    fun theTalliesMakeTheCardsTheRowsMake() {
        val rows = listOf(SpaceRow(null, 1_000), SpaceRow("house", 3_000), SpaceRow("school", 2_000), SpaceRow("clinic", 9_000), SpaceRow("school", 5_000))
        val tallies = listOf(KindTally("clinic", 1, 9_000), KindTally("house", 2, 3_000), KindTally("school", 2, 5_000)) // the clinic is counted in the default space, first
        for (default in both) for (enabled in listOf(both, listOf("house"), listOf("school"))) {
            assertEquals(spaceCards(rows, enabled, default, both), spaceCardsOf(tallies, enabled, default, both), "$default $enabled")
        }
        assertEquals(spaceCounts(rows, "house", both), spaceCountsOf(tallies, "house", both))
        assertEquals(emptyMap(), spaceCountsOf(emptyList(), "house", both))
    }
}
