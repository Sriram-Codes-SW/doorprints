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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What every reader keeps of a house's answers ([HouseAnswers.coerced], docs/11 5.5, slice 3a), as the web's
 * `cleanAnswers` does, the order shown, the cost pre-fill's words and the answer's validity. The shared vectors Q1..Q6
 * are in the host test `HouseAnswersTest`, which reads the defaults' file.
 */
class HouseAnswersCoercionTest {
    @Test fun aTypedAnswerReadsAnsweredAndAnAnsweredOneWithoutAnswerReadsOpen() {
        val kept = HouseAnswers.coerced(
            listOf(
                HouseAnswer("a", text = "Water?", answer = "Borewell", status = "OPEN"),
                HouseAnswer("b", text = "Lift?", status = "ANSWERED", sort = 1),
                HouseAnswer("c", text = "Pets?", answer = "  ", status = "ANSWERED", sort = 2),
                HouseAnswer("d", text = "Terrace?", answer = "No", status = "SKIPPED", sort = 3),
                HouseAnswer("e", text = "Floor?", status = "LATER", sort = 4),
            ),
        )!!
        assertEquals(listOf("ANSWERED", "OPEN", "OPEN", "SKIPPED", "OPEN"), kept.map { it.status })
        assertNull(kept[2].answer, "a blank answer is none")
        assertEquals("No", kept[3].answer, "a skipped question keeps what was typed")
    }

    @Test fun aBadOrRepeatedIdOrABlankOrOverLongQuestionIsDroppedAndAnOverLongAnswerIsNone() {
        val kept = HouseAnswers.coerced(
            listOf(
                HouseAnswer("a", text = "First"),
                HouseAnswer("a", text = "Second", sort = 1),
                HouseAnswer("..", text = "Dots", sort = 2),
                HouseAnswer("b", text = "  ", sort = 3),
                HouseAnswer("c", text = "x".repeat(301), sort = 4),
                HouseAnswer("d", questionId = "a/b", text = "Long", answer = "y".repeat(2001), status = "ANSWERED", sort = -2),
            ),
        )!!
        // d's negative sort is 0, so it follows a by id.
        assertEquals(listOf("a", "d"), kept.map { it.id })
        assertEquals(HouseAnswer("d", text = "Long", status = "OPEN", sort = 0), kept[1])
        assertNull(HouseAnswers.coerced(emptyList()))
        assertNull(HouseAnswers.coerced(null))
        assertNull(HouseAnswers.coerced(listOf(HouseAnswer("a", text = ""))))
    }

    @Test fun moreThanSixtyKeepTheFirstSixtyBySort() {
        val kept = HouseAnswers.coerced((0 until 65).map { HouseAnswer("a$it", text = "Q$it", sort = 64 - it) })!!
        assertEquals(60, kept.size)
        assertEquals((0 until 60).toList(), kept.map { it.sort })
    }

    @Test fun theOrderShownIsOpenFirstThenSortThenId() {
        val answers = listOf(
            HouseAnswer("x", text = "t", answer = "yes", status = "ANSWERED", sort = 0),
            HouseAnswer("b", text = "t", sort = 2),
            HouseAnswer("s", text = "t", status = "SKIPPED", sort = 1),
            HouseAnswer("a", text = "t", sort = 2),
            HouseAnswer("o", text = "t", sort = 5, answer = "typed", status = "OPEN"),
        )
        assertEquals(listOf("a", "b", "x", "s", "o"), HouseAnswers.ordered(answers).map { it.id })
        assertEquals(6, HouseAnswers.nextSort(answers))
        assertEquals(0, HouseAnswers.nextSort(null))
    }

    @Test fun theJsonTextHasTheFormatsKeysInOrderAndReadsBack() {
        val answers = listOf(
            HouseAnswer("a1", "qd_maintenance", "Maintenance?", "₹2,500", "ANSWERED", 0),
            HouseAnswer("a2", text = "Terrace?", sort = 1),
        )
        val text = HouseAnswers.encode(answers)
        assertEquals(
            """[{"id":"a1","questionId":"qd_maintenance","text":"Maintenance?","answer":"₹2,500","status":"ANSWERED","sort":0},""" +
                """{"id":"a2","text":"Terrace?","status":"OPEN","sort":1}]""",
            text,
        )
        assertEquals(answers, HouseAnswers.decode(text))
        assertNull(HouseAnswers.encode(emptyList()))
        assertNull(HouseAnswers.decode("not json"))
    }

    @Test fun theCostPreFillsTheFourMoneyQuestionsInWords() {
        val cost = HouseCost(
            deposit = 64_000, depositMonths = 2, maintenance = 2_500, maintenanceIncluded = false, brokerage = 16_000,
            lockInMonths = 11, noticeMonths = 2,
        )
        assertEquals("₹64,000, 2 months", HouseAnswers.prefill("qd_deposit", cost))
        assertEquals("₹2,500 a month (not included)", HouseAnswers.prefill("qd_maintenance", cost))
        assertEquals("₹2,500 a month (included)", HouseAnswers.prefill("qd_maintenance", cost.copy(maintenanceIncluded = true)))
        assertEquals("₹2,500 a month", HouseAnswers.prefill("qd_maintenance", cost.copy(maintenanceIncluded = null)))
        assertEquals("₹16,000", HouseAnswers.prefill("qd_brokerage", cost))
        assertEquals("1 month", HouseAnswers.prefill("qd_brokerage", HouseCost(brokerageMonths = 1)))
        assertEquals("Lock-in 11 months, notice 2 months", HouseAnswers.prefill("qd_lockin", cost))
        assertEquals("Lock-in 11 months", HouseAnswers.prefill("qd_lockin", HouseCost(lockInMonths = 11)))
        assertEquals("Notice 1 month", HouseAnswers.prefill("qd_lockin", HouseCost(noticeMonths = 1)))
        assertEquals("3 months", HouseAnswers.prefill("qd_deposit", HouseCost(depositMonths = 3)))
        assertNull(HouseAnswers.prefill("qd_water", cost))
        assertNull(HouseAnswers.prefill("qd_deposit", HouseCost(maintenance = 1)))
        assertNull(HouseAnswers.prefill("qd_deposit", null))
        // The app's own words: a Hindi template fills the same way.
        val hi = AnswerWords(months = "%1\$d महीने", amountAndMonths = "%1\$s, %2\$s")
        assertEquals("₹64,000, 2 महीने", HouseAnswers.prefill("qd_deposit", cost, hi))
    }

    @Test fun anAnswersValidityIsWhatABackupsCheckDemands() {
        assertTrue(HouseAnswer("a1", "qd_water", "Water?", "Borewell", "ANSWERED", 0).isValid)
        assertTrue(HouseAnswer("a1", "dangling_id", "Water?").isValid, "a question id may name a question that is gone")
        for (bad in listOf(
            HouseAnswer("..", text = "t"), HouseAnswer("a/b", text = "t"), HouseAnswer("a", text = ""),
            HouseAnswer("a", text = "x".repeat(301)), HouseAnswer("a", text = "t", answer = "y".repeat(2001)),
            HouseAnswer("a", text = "t", status = "LATER"), HouseAnswer("a", text = "t", sort = -1),
        )) assertFalse(bad.isValid, bad.toString())
    }

    @Test fun askMakesAnOpenAnswerAtTheEnd() {
        val existing = listOf(HouseAnswer("a", text = "t", sort = 4))
        assertEquals(
            HouseAnswer("n", null, "Is the terrace open?", null, "OPEN", 5),
            HouseAnswers.ask("  Is the terrace open? ", null, existing) { "n" },
        )
    }
}
