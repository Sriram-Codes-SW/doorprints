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

import app.doorprints.shared.records.RecordRules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Criteria and preferences as records (docs/11 5.4, slice 2): the payload's keys, `Scoring.of` merging the defaults
 * with the records, the coercion on read, the custom keys and the rating share's text.
 */
class CriteriaTest {

    @Test
    fun thePayloadHasTheFormatsKeysInOrderWithArchivedOnlyWhenTrue() {
        assertEquals(
            """{"weight":3,"mustHave":true,"minScore":4,"sort":0}""",
            CriterionType.encode(Criterion("water", weight = 3, mustHave = true, minScore = 4, sort = 0)),
        )
        assertEquals(
            """{"label":"Pets allowed","weight":2,"mustHave":false,"minScore":3,"sort":10,"archived":true}""",
            CriterionType.encode(Criterion("c_1a2b3c4d", "Pets allowed", sort = 10, archived = true)),
        )
        assertEquals("""{"value":"0.4"}""", PreferenceType.encode(Preference("0.4")))
        // The key is the record id, never in the payload; a payload read back has none until the caller sets it.
        val back = RecordRules.json.decodeFromString(Criterion.serializer(), """{"weight":1,"sort":2,"archived":true,"x":1}""")
        assertEquals(Criterion(weight = 1, sort = 2, archived = true), back)
    }

    @Test
    fun theDefaultScoringIsTheTenBuiltInsAtMediumInTheirOrder() {
        val s = Scoring.DEFAULT
        assertEquals(Checklist.keys, s.criteria.map { it.key })
        assertTrue(s.criteria.all { it.weight == 2 && !it.mustHave && it.minScore == 3 && !it.archived })
        assertEquals((0..9).toList(), s.criteria.map { it.sort })
        assertEquals(0.5, s.ratingShare)
    }

    @Test
    fun recordsReplaceTheirDefaultsCustomOnesJoinAndTheListIsSortedBySortThenKey() {
        val s = Scoring.of(
            listOf(
                Criterion("commute", weight = 3, sort = 0),
                Criterion("c_1a2b3c4d", "Pets allowed", sort = 0),
                Criterion("noise", weight = 0, sort = 5, archived = true),
            ),
            mapOf(Preference.RATING_SHARE to "0.4"),
        )
        assertEquals(11, s.criteria.size)
        assertEquals(listOf("c_1a2b3c4d", "commute", "water"), s.criteria.take(3).map { it.key })
        assertEquals(3, s["commute"]!!.weight)
        assertTrue(s["noise"]!!.archived)
        assertEquals(10, s.active.size)
        assertEquals(0.4, s.ratingShare)
    }

    @Test
    fun coercionMendsValuesAndSkipsABadKey() {
        val s = Scoring.of(
            listOf(
                Criterion("water", label = "not for a built-in", weight = 9, minScore = 0, sort = -1),
                Criterion("c_00000001", label = "  " + "x".repeat(70) + "  ", sort = 11),
                Criterion("bad key!", weight = 3),
                Criterion("fromANewerApp", weight = 1, sort = 12),
            ),
            emptyMap(),
        )
        val water = s["water"]!!
        assertNull(water.label)
        assertEquals(2, water.weight)
        assertEquals(3, water.minScore)
        assertEquals(0, water.sort)
        assertEquals(60, s["c_00000001"]!!.label!!.length)
        assertNull(s["bad key!"])
        // A key this app does not know is kept as a criterion (a newer app's), scored like any other.
        assertEquals(1, s["fromANewerApp"]!!.weight)
    }

    @Test
    fun aBuiltInAtItsDefaultNeedsNoRecord() {
        assertTrue(Criterion.default("parking").isDefault)
        assertTrue(Criterion("parking", sort = 2).isDefault)
        assertFalse(Criterion("parking", sort = 3).isDefault)
        assertFalse(Criterion("parking", weight = 3, sort = 2).isDefault)
        assertFalse(Criterion("c_1a2b3c4d", "Pets", sort = 10).isDefault)
    }

    @Test
    fun validityIsWhatABackupsCheckDemands() {
        assertTrue(Criterion("water", weight = 0, minScore = 5, sort = 0).isValid)
        assertFalse(Criterion("water", label = "Water", sort = 0).isValid)
        assertFalse(Criterion("c_1a2b3c4d", "x".repeat(61), sort = 0).isValid)
        assertFalse(Criterion("water", weight = 4).isValid)
        assertFalse(Criterion("water", minScore = 0).isValid)
        assertFalse(Criterion("water", sort = -1).isValid)
        assertFalse(Criterion("a/b").isValid)
    }

    @Test
    fun aCustomKeyIsCPlusEightHexAndIsDrawnAgainOnAClash() {
        val first = Criterion.newCustomKey({ false }, Random(7))
        assertTrue(Criterion.isCustomKey(first), first)
        // The same seed draws the same key first; with it taken, the next draw is used.
        val second = Criterion.newCustomKey({ it == first }, Random(7))
        assertTrue(Criterion.isCustomKey(second))
        assertTrue(second != first)
        assertFalse(Criterion.isCustomKey("c_ABCDEF12"))
        assertFalse(Criterion.isCustomKey("water"))
    }

    @Test
    fun theRatingShareIsReadLeniently() {
        assertEquals(0.5, Scoring.ratingShare(null))
        assertEquals(0.5, Scoring.ratingShare("lots"))
        assertEquals(0.5, Scoring.ratingShare("1.5"))
        assertEquals(0.5, Scoring.ratingShare("-0.1"))
        assertEquals(0.5, Scoring.ratingShare("NaN"))
        assertEquals(0.0, Scoring.ratingShare("0"))
        assertEquals(1.0, Scoring.ratingShare("1"))
        assertEquals(0.75, Scoring.ratingShare(" 0.75 "))
        assertEquals("0", Scoring.shareText(0.0))
        assertEquals("0.25", Scoring.shareText(0.25))
        assertEquals("1", Scoring.shareText(1.0))
    }
}
