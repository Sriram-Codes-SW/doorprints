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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * *Add the usual questions* ([HouseAnswers.addUsual], docs/11 5.5 and 5.21, slice 3a): the shared vectors Q1..Q6 in the
 * web's order and names (`house-answers.spec.ts`), on the seeded bank, with the expected ids worked out from
 * `docs/schemas/default-questions.json` rather than typed twice. Not in commonTest: it reads a repository file (the
 * reader's coercion and the order shown are in `HouseAnswersCoercionTest`).
 */
class HouseAnswersTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val file = Json.parseToJsonElement(repoFile("docs/schemas/default-questions.json").readText()).jsonObject
    private val seed: List<JsonObject> = file.getValue("questions").jsonArray.map { it.jsonObject }
    private val bank = DefaultQuestions.bank("en")
    private var ids = 0
    private val newId = { "n${ids++}" }

    /** The ids the vectors expect: the file's `defaultOn` questions for BOTH or [scope], by sort then id. */
    private fun expectedIds(scope: String): List<String> = seed
        .filter { it.getValue("defaultOn").jsonPrimitive.boolean }
        .filter { it.getValue("appliesTo").jsonPrimitive.content.let { a -> a == "BOTH" || a == scope } }
        .sortedWith(compareBy({ it.getValue("sort").jsonPrimitive.int }, { it.getValue("id").jsonPrimitive.content }))
        .map { it.getValue("id").jsonPrimitive.content }

    @Test
    fun q1ARentHouseWithTheSeededBankAsksExactlyTheseEightInBankOrder() {
        val added = HouseAnswers.addUsual("RENT", null, bank, emptyList(), newId = newId)
        assertEquals(expectedIds("RENT"), added.map { it.questionId })
        assertEquals(
            listOf("qd_maintenance", "qd_deposit", "qd_brokerage", "qd_lockin", "qd_water", "qd_power", "qd_parking", "qd_floor"),
            added.map { it.questionId },
        )
        assertTrue(added.all { it.status == "OPEN" && it.answer == null })
        assertEquals((0..7).toList(), added.map { it.sort })
        assertEquals(bank.first { it.id == "qd_maintenance" }.text, added.first().text)
        // A house with no price type is a rent house.
        assertEquals(expectedIds("RENT"), HouseAnswers.addUsual(null, null, bank, null, newId = newId).map { it.questionId })
    }

    @Test
    fun q2ASaleHouseGetsExactlyTheseNine() {
        val added = HouseAnswers.addUsual("SALE", null, bank, emptyList(), newId = newId)
        assertEquals(expectedIds("SALE"), added.map { it.questionId })
        assertEquals(
            listOf(
                "qd_maintenance", "qd_brokerage", "qd_water", "qd_power", "qd_parking", "qd_floor", "qd_occupancy",
                "qd_rera", "qd_khata",
            ),
            added.map { it.questionId },
        )
    }

    @Test
    fun q3CallingItTwiceAddsNothing() {
        val once = HouseAnswers.addUsual("RENT", null, bank, emptyList(), newId = newId)
        assertEquals(once, HouseAnswers.addUsual("RENT", null, bank, once, newId = newId))
        assertTrue(HouseAnswers.usual("RENT", bank, once).isEmpty())
        // Only a questionId matches: the same words asked ad hoc do not.
        val adHoc = HouseAnswer("x", text = bank.first().text)
        assertTrue("qd_maintenance" in HouseAnswers.addUsual("RENT", null, bank, listOf(adHoc), newId = newId).map { it.questionId })
    }

    @Test
    fun q4AnArchivedOrNonDefaultOnQuestionIsNotAdded() {
        assertFalse("qd_pets" in HouseAnswers.addUsual("RENT", null, bank, null, newId = newId).map { it.questionId })
        val archived = bank.map { if (it.id == "qd_water") it.copy(archived = true) else it }
        assertFalse("qd_water" in HouseAnswers.addUsual("RENT", null, archived, null, newId = newId).map { it.questionId })
        val custom = Question("q_00000001", "Custom", "OTHER", "BOTH", defaultOn = true, sort = 99)
        assertEquals("q_00000001", HouseAnswers.addUsual("RENT", null, bank + custom, null, newId = newId).last().questionId)
    }

    @Test
    fun q5TheCostPreFillMentionsTheDepositInRupeesAndMonthsAndMaintenanceNotIncluded() {
        val cost = HouseCost(deposit = 64_000, depositMonths = 2, maintenance = 2_500, maintenanceIncluded = false)
        val added = HouseAnswers.addUsual("RENT", cost, bank, null, newId = newId)
        val deposit = added.first { it.questionId == "qd_deposit" }
        assertEquals("ANSWERED", deposit.status)
        assertTrue(deposit.answer!!, deposit.answer!!.contains("64,000") && deposit.answer!!.contains("2 months"))
        val maintenance = added.first { it.questionId == "qd_maintenance" }
        assertEquals("ANSWERED", maintenance.status)
        assertTrue(maintenance.answer!!, maintenance.answer!!.contains("2,500") && maintenance.answer!!.contains("not included"))
        // A question the cost says nothing about stays open.
        assertEquals("OPEN", added.first { it.questionId == "qd_water" }.status)
        assertEquals(null, added.first { it.questionId == "qd_brokerage" }.answer)
    }

    @Test
    fun q6StopsAtSixtyAnswers() {
        val full = (0 until 58).map { HouseAnswer("f$it", text = "Q $it", sort = it) }
        val added = HouseAnswers.addUsual("RENT", null, bank, full, newId = newId)
        assertEquals(HouseAnswers.MAX, added.size)
        assertEquals(listOf("qd_maintenance", "qd_deposit"), added.drop(58).map { it.questionId })
        assertEquals(listOf(58, 59), added.drop(58).map { it.sort })
        assertEquals(HouseAnswers.MAX, HouseAnswers.addUsual("RENT", null, bank, added, newId = newId).size)
    }
}
