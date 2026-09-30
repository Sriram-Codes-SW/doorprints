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

/**
 * The one scoring (docs/11 5.4, slice 2): the eight vectors V1..V8 both stacks run under the same names (the web's
 * `scoring.spec.ts`), then the cases of the pre-slice formula, which the default scoring still gives (was ChecklistScoreTest).
 */
class HouseScoreTest {

    private fun scoring(vararg criteria: Criterion, share: Double? = null) = Scoring.of(
        criteria.toList(), share?.let { mapOf(Preference.RATING_SHARE to Scoring.shareText(it)) } ?: emptyMap(),
    )

    private fun assertScore(expected: Double, actual: Double?) {
        assertNotNull(actual)
        assertEquals(expected, actual, 1e-9)
    }

    // V1 defaults: checklist {water:5, parking:4}, rating 4 -> wc 4.5, overall 4.25, coverage 0.2.
    @Test
    fun v1Defaults() {
        val r = HouseScore.evaluate(mapOf("water" to 5, "parking" to 4), 4, Scoring.DEFAULT)
        assertScore(4.5, r.weighted)
        assertScore(4.25, r.overall)
        assertScore(0.2, r.coverage)
        assertEquals(2, r.scored)
        assertEquals(10, r.active)
    }

    // V2 weights: water w3 s5, power w1 s1, no rating -> wc 4.0, overall 4.0.
    @Test
    fun v2Weights() {
        val s = scoring(Criterion("water", weight = 3, sort = 0), Criterion("power", weight = 1, sort = 1))
        val r = HouseScore.evaluate(mapOf("water" to 5, "power" to 1), null, s)
        assertScore(4.0, r.weighted)
        assertScore(4.0, r.overall)
    }

    // V3 ignore + archive: water w0 s5, power w2 s3 -> wc 3.0; a criterion archived with a score is ignored too.
    @Test
    fun v3IgnoreAndArchive() {
        val s = scoring(Criterion("water", weight = 0, sort = 0), Criterion("parking", sort = 2, archived = true))
        assertScore(3.0, HouseScore.evaluate(mapOf("water" to 5, "power" to 3), null, s).weighted)
        val r = HouseScore.evaluate(mapOf("water" to 5, "power" to 3, "parking" to 0), null, s)
        assertScore(3.0, r.weighted)
        // Neither counts in the coverage: 1 of the 8 that matter.
        assertEquals(8, r.active)
        assertScore(2.0 / 16, r.coverage)
    }

    // V4 must-have: security mustHave minScore 4 with score 2 -> failed [security]; with no score -> unchecked [security],
    // not failed; with score 4 -> neither.
    @Test
    fun v4MustHave() {
        val s = scoring(Criterion("security", mustHave = true, minScore = 4, sort = 6))
        val failed = HouseScore.evaluate(mapOf("security" to 2), null, s)
        assertEquals(listOf("security"), failed.failedMustHave)
        assertEquals(emptyList(), failed.uncheckedMustHave)
        val unchecked = HouseScore.evaluate(emptyMap(), 5, s)
        assertEquals(emptyList(), unchecked.failedMustHave)
        assertEquals(listOf("security"), unchecked.uncheckedMustHave)
        val met = HouseScore.evaluate(mapOf("security" to 4), null, s)
        assertEquals(emptyList(), met.failedMustHave)
        assertEquals(emptyList(), met.uncheckedMustHave)
        // Weight does not matter for a must-have; archiving does.
        val ignored = scoring(Criterion("security", weight = 0, mustHave = true, minScore = 4, sort = 6))
        assertEquals(listOf("security"), HouseScore.evaluate(mapOf("security" to 2), null, ignored).failedMustHave)
        val archived = scoring(Criterion("security", mustHave = true, minScore = 4, sort = 6, archived = true))
        assertEquals(emptyList(), HouseScore.evaluate(mapOf("security" to 2), null, archived).failedMustHave)
    }

    // V5 rating share 0.25: wc 4, rating 2 -> overall 3.5. No checklist score, rating 3 -> overall 3.0. Neither -> null.
    @Test
    fun v5RatingShare() {
        val s = scoring(share = 0.25)
        assertEquals(0.25, s.ratingShare)
        assertScore(3.5, HouseScore.of(mapOf("water" to 4), 2, s))
        assertScore(3.0, HouseScore.of(emptyMap(), 3, s))
        assertNull(HouseScore.of(emptyMap(), null, s))
    }

    // V6 custom: c_ab12cd34 w3 s5 + water w2 s1 -> wc 3.4.
    @Test
    fun v6Custom() {
        val s = scoring(Criterion("c_ab12cd34", label = "Pets allowed", weight = 3, sort = 10))
        assertScore(3.4, HouseScore.evaluate(mapOf("c_ab12cd34" to 5, "water" to 1), null, s).weighted)
        assertEquals(11, s.active.size)
    }

    // V7 compatibility: DEFAULT scoring, checklist {water:4, power:2}, rating 5 -> overall 4.0 (= the pre-slice formula
    // (avg 3 + 5)/2).
    @Test
    fun v7Compatibility() {
        assertScore(4.0, HouseScore.of(mapOf("water" to 4, "power" to 2), 5))
        assertScore(4.0, HouseScore.of(mapOf("water" to 4, "power" to 2), 5, Scoring.DEFAULT))
    }

    // V8 ranking: A (failed must-have, overall 5.0), B (overall 4, coverage 0.5, price 20000), C (overall 4, coverage 0.5,
    // price 15000), D (overall 4, coverage 0.2), E (overall null), all otherwise equal -> order C, B, D, E, A.
    @Test
    fun v8Ranking() {
        fun r(overall: Double?, coverage: Double?, failed: List<String> = emptyList()) = ScoreResult(overall, coverage, failed, emptyList())
        val houses = listOf(
            RankedHouse("A", r(5.0, 0.5, listOf("security")), null, 1),
            RankedHouse("B", r(4.0, 0.5), 20_000, 1),
            RankedHouse("C", r(4.0, 0.5), 15_000, 1),
            RankedHouse("D", r(4.0, 0.2), null, 1),
            RankedHouse("E", r(null, null), null, 1),
        )
        assertEquals(listOf("C", "B", "D", "E", "A"), houses.shuffled().sortedWith(Ranking.comparator).map { it.id })
        assertEquals(listOf("C", "B", "D", "E", "A"), Ranking.sort(houses.reversed()) { it }.map { it.id })
    }

    @Test
    fun rankingBreaksTiesByUnknownPriceLastThenTheLatestChangeThenTheId() {
        val r = ScoreResult(3.0, 0.5, emptyList(), emptyList())
        val houses = listOf(
            RankedHouse("x", r, null, 9), RankedHouse("y", r, 100, 1), RankedHouse("z", r, 100, 5), RankedHouse("w", r, 100, 5),
        )
        assertEquals(listOf("w", "z", "y", "x"), houses.sortedWith(Ranking.comparator).map { it.id })
        assertEquals(0, Ranking.compare(houses[2], houses[2]))
    }

    // ---- The default scoring gives the pre-slice formula (FR-005) ----

    private fun score(checklist: Map<String, Int> = emptyMap(), rating: Int? = null) = HouseScore.of(checklist, rating, Scoring.DEFAULT)

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
        assertScore(3.0, score(mapOf("water" to 5, "power" to 5, "parking" to 5, "noise" to 5), rating = 1))
        assertScore(3.5, score(mapOf("water" to 2, "parking" to 4), rating = 4))
    }

    @Test
    fun aKeyThatIsNotACriterionIsIgnored() {
        // A newer app's key: kept on the house, not scored (docs/11 5.4).
        assertScore(5.0, score(mapOf("water" to 5, "fromANewerApp" to 0)))
        assertNull(score(mapOf("fromANewerApp" to 3)))
    }
}
