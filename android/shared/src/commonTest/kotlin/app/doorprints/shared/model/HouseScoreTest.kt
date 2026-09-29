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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Checklist average, blended 50/50 with the star rating when both exist; null when neither (was ChecklistScoreTest). */
class HouseScoreTest {

    private fun score(checklist: Map<String, Int> = emptyMap(), rating: Int? = null) = HouseScore.of(checklist, rating)

    private fun assertScore(expected: Double, actual: Double?) {
        assertNotNull(actual)
        assertEquals(expected, actual, 1e-9)
    }

    @Test
    fun nothingScoredIsNull() {
        assertNull(score())
    }

    @Test
    fun checklistOnlyIsItsAverage() {
        assertScore(5.0, score(mapOf("water" to 5)))
        assertScore(3.5, score(mapOf("water" to 5, "power" to 2)))
        assertScore(4.0 / 3, score(mapOf("water" to 1, "power" to 1, "noise" to 2)))
    }

    @Test
    fun ratingOnlyIsTheRating() {
        assertScore(4.0, score(rating = 4))
    }

    @Test
    fun zeroIsAScoreNotMissing() {
        assertScore(0.0, score(mapOf("water" to 0, "power" to 0)))
        assertScore(0.0, score(rating = 0))
        assertScore(2.5, score(mapOf("water" to 0), rating = 5))
    }

    @Test
    fun checklistAndRatingBlendHalfAndHalf() {
        // Checklist average 3 (4 and 2), rating 5: (3 + 5) / 2 = 4, however many checklist items there are.
        assertScore(4.0, score(mapOf("water" to 4, "power" to 2), rating = 5))
        assertScore(3.0, score(mapOf("water" to 5, "power" to 5, "parking" to 5, "noise" to 5), rating = 1))
        assertScore(3.5, score(mapOf("water" to 2, "parking" to 4), rating = 4))
    }

    @Test
    fun rankingPutsUnscoredLast() {
        val ranked = listOf(null, 2.0, 4.5, 0.0).sortedByDescending { HouseScore.rankKey(it) }
        assertEquals(listOf(4.5, 2.0, 0.0, null), ranked)
    }
}
