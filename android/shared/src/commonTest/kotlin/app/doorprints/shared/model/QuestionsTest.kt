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

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The question bank's record (docs/11 5.5, slice 3a): what a reader keeps, the payload's keys and the seeded defaults. */
class QuestionsTest {
    @Test fun anUnknownCategoryIsOtherAnUnknownScopeBothAndANegativeSortZero() {
        val q = Question("q_12345678", "Is there a lift?", category = "GARDEN", appliesTo = "LEASE", sort = -4).coerced()!!
        assertEquals(Question("q_12345678", "Is there a lift?", "OTHER", "BOTH", false, 0), q)
        assertEquals(QuestionCategory.OTHER, q.questionCategory)
        assertEquals(QuestionScope.BOTH, q.scope)
    }

    @Test fun aBlankOrOverLongTextOrABadIdIsUntrustedAndSkipped() {
        assertNull(Question("q_12345678", "   ").coerced())
        assertNull(Question("q_12345678", "x".repeat(301)).coerced())
        assertNull(Question("..", "Text").coerced())
        assertNull(Question("a/b", "Text").coerced())
        assertEquals(300, Question("q_12345678", "x".repeat(300)).coerced()!!.text.length)
    }

    @Test fun validityIsWhatABackupsCheckDemands() {
        assertTrue(Question("qd_water", "Water?", "WATER_POWER", "BOTH", true, 4).isValid)
        assertTrue(Question("q_1", "Text", category = "GARDEN").isValid, "an unknown category reads as OTHER, it is not refused")
        for (bad in listOf(Question("..", "t"), Question("q", ""), Question("q", " "), Question("q", "x".repeat(301)), Question("q", "t", sort = -1))) {
            assertFalse(bad.isValid, bad.toString())
        }
    }

    @Test fun thePayloadHasTheFormatsKeysInOrderAndArchivedOnlyWhenTrue() {
        assertEquals(
            """{"text":"Is there a lift?","category":"BUILDING","appliesTo":"SALE","defaultOn":true,"sort":3}""",
            QuestionType.encode(Question("q_1", "Is there a lift?", "BUILDING", "SALE", true, 3)),
        )
        assertEquals(
            """{"text":"Pets?","category":"RULES","appliesTo":"RENT","defaultOn":false,"sort":6,"archived":true}""",
            QuestionType.encode(Question("qd_pets", "Pets?", "RULES", "RENT", false, 6, archived = true)),
        )
    }

    @Test fun aQuestionAppliesToARentASaleOrBoth() {
        val rent = Question("a", "t", appliesTo = "RENT")
        val sale = Question("b", "t", appliesTo = "SALE")
        val both = Question("c", "t", appliesTo = "BOTH")
        assertTrue(rent.appliesToHouse("RENT") && rent.appliesToHouse(null) && !rent.appliesToHouse("SALE"))
        assertTrue(sale.appliesToHouse("SALE") && !sale.appliesToHouse("RENT") && !sale.appliesToHouse(null))
        assertTrue(both.appliesToHouse("RENT") && both.appliesToHouse("SALE") && both.appliesToHouse(null))
    }

    @Test fun theFourteenDefaultsHaveFixedIdsAndFourTexts() {
        assertEquals(14, DefaultQuestions.ALL.size)
        assertTrue(DefaultQuestions.ALL.all { it.id.startsWith("qd_") })
        assertTrue(DefaultQuestions.ALL.all { d -> DefaultQuestions.LANGUAGES.all { !d.text[it].isNullOrBlank() } })
        assertEquals((0 until 14).toList(), DefaultQuestions.ALL.map { it.sort })
        assertTrue(Question("qd_water", "x").isDefault)
        assertFalse(Question("q_12345678", "x").isDefault)
    }

    @Test fun aDefaultIsInTheAppsLanguageAndEnglishForAnyOther() {
        val water = DefaultQuestions.byId("qd_water")!!
        assertEquals(water.text.getValue("ta"), water.question("ta").text)
        assertEquals(water.text.getValue("en"), water.question("mr").text)
        assertEquals(water.text.getValue("en"), water.question(null).text)
        assertEquals(Question("qd_water", water.text.getValue("hi"), "WATER_POWER", "BOTH", true, 4), water.question("hi"))
        assertEquals(DefaultQuestions.ALL.map { it.id }, DefaultQuestions.bank("te").map { it.id })
    }

    @Test fun aCustomIdIsQAndEightHexAndNeverATakenOne() {
        val random = Random(7)
        val first = Question.newCustomId({ false }, Random(7))
        assertTrue(Question.isCustomId(first), first)
        // The same draw again is refused, so the next one is taken.
        val second = Question.newCustomId({ it == first }, random)
        assertTrue(Question.isCustomId(second) && second != first)
        assertFalse(Question.isCustomId("qd_water"))
        assertFalse(Question.isCustomId("q_1234567G"))
    }

    @Test fun theBanksOrderIsSortThenId() {
        val list = listOf(Question("b", "t", sort = 1), Question("c", "t"), Question("a", "t", sort = 1))
        assertEquals(listOf("c", "a", "b"), list.sortedWith(Question.ORDER).map { it.id })
    }
}
