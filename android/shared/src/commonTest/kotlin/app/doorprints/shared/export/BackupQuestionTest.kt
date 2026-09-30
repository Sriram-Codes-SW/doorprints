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

package app.doorprints.shared.export

import app.doorprints.shared.model.HouseAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The question bank and the answers in `doorprints-backup/2` (docs/11 5.5, slice 3a): `/1` with neither, `/2` with
 * questions only or answers only, the `questions` list after `preferences` ordered by `updatedAt` then id, `answers`
 * after `rooms`, both kept in a copy without contact details (the answer text whole), `counts.questions`, every check
 * that refuses the file, and the import's merge by id with the last write winning.
 */
class BackupQuestionTest {
    private val deposit = ExportQuestion(
        "qd_deposit", "How many months is the deposit, and when and how is it refunded?", "MONEY", "RENT", true, 1,
        updatedAt = 1_788_242_400_000,
    )
    private val maintenance = ExportQuestion(
        "qd_maintenance", "How much is the maintenance per month, and what does it cover?", "MONEY", "BOTH", true, 0,
        updatedAt = 1_788_242_400_000,
    )
    private val meter = ExportQuestion("q_9f8e7d6c", "Is there a water meter?", "WATER_POWER", "RENT", false, 20, true, 1_788_328_800_000)
    private val asked = HouseAnswer(
        "a1111111-1111-4111-8111-111111111111", "qd_maintenance", "How much is the maintenance per month, and what does it cover?",
        "₹2,500 a month; call Ravi on 98400 11111", "ANSWERED", 0,
    )
    private val open = HouseAnswer("a2222222-2222-4222-8222-222222222222", text = "Is the terrace open to tenants?", sort = 1)
    private val house1 = ExportFixture.house1.copy(answers = listOf(asked, open))

    private fun bundle(
        options: ExportOptions = ExportFixture.options(),
        houses: List<ExportHouse> = ExportFixture.houses,
        questions: List<ExportQuestion> = listOf(meter, deposit, maintenance),
    ) = ExportBundle.build(options, houses, ExportFixture.visits, ExportFixture.photos, questions = questions)

    private fun text(b: ExportBundle) = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(b))

    @Test
    fun aCopyWithNoQuestionAndNoAnswerIsFormat1WithNeitherKey() {
        val t = text(bundle(questions = emptyList()))
        assertTrue(t.startsWith("{\"format\":\"doorprints-backup/1\","), t)
        assertFalse(t.contains("\"questions\"") || t.contains("\"answers\""), t)
        assertEquals("doorprints-backup/1", BackupFormat.idFor(0, 0, 0, 0, 0, 0))
        assertNull(BackupCounts.of(BackupData.of(bundle(questions = emptyList()))).questions)
    }

    @Test
    fun aCopyWithQuestionsOnlyIsFormat2WithTheListLastInItsOrder() {
        val t = text(bundle())
        assertTrue(t.startsWith("{\"format\":\"doorprints-backup/2\","), t)
        assertTrue(
            t.endsWith(
                "\"questions\":[{\"id\":\"qd_deposit\",\"text\":\"${deposit.text}\",\"category\":\"MONEY\",\"appliesTo\":\"RENT\"," +
                    "\"defaultOn\":true,\"sort\":1,\"updatedAt\":1788242400000},{\"id\":\"qd_maintenance\",\"text\":\"${maintenance.text}\"," +
                    "\"category\":\"MONEY\",\"appliesTo\":\"BOTH\",\"defaultOn\":true,\"sort\":0,\"updatedAt\":1788242400000}," +
                    "{\"id\":\"q_9f8e7d6c\",\"text\":\"Is there a water meter?\",\"category\":\"WATER_POWER\",\"appliesTo\":\"RENT\"," +
                    "\"defaultOn\":false,\"sort\":20,\"archived\":true,\"updatedAt\":1788328800000}]}",
            ),
            t,
        )
        assertEquals("doorprints-backup/2", BackupFormat.idFor(0, 0, 0, 0, 1, 0))
        val counts = BackupCounts.of(BackupData.of(bundle()))
        assertEquals(BackupCounts(2, 1, 1, questions = 3), counts)
        assertTrue(BackupFormat.json.encodeToString(BackupCounts.serializer(), counts).endsWith("\"photos\":1,\"questions\":3}"))
    }

    @Test
    fun aCopyWithAnswersOnlyIsFormat2WithTheAnswersAfterTheRooms() {
        val t = text(bundle(houses = listOf(house1, ExportFixture.house2), questions = emptyList()))
        assertTrue(t.startsWith("{\"format\":\"doorprints-backup/2\","), t)
        assertFalse(t.contains("\"questions\""), "no questions list without a question: $t")
        assertTrue(
            t.contains(
                "\"answers\":[{\"id\":\"${asked.id}\",\"questionId\":\"qd_maintenance\",\"text\":\"${asked.text}\"," +
                    "\"answer\":\"${asked.answer}\",\"status\":\"ANSWERED\",\"sort\":0},{\"id\":\"${open.id}\"," +
                    "\"text\":\"Is the terrace open to tenants?\",\"status\":\"OPEN\",\"sort\":1}],\"checklist\"",
            ),
            t,
        )
        assertEquals("doorprints-backup/2", BackupFormat.idFor(0, 0, 0, 0, 0, 2))
        // The counts do not change: answers are part of their house.
        assertEquals(BackupCounts(2, 1, 1), BackupCounts.of(BackupData.of(bundle(houses = listOf(house1, ExportFixture.house2), questions = emptyList()))))
        val back = BackupFormat.json.decodeFromString(BackupData.serializer(), t)
        assertEquals(listOf(asked, open), back.houses.first().answers)
        assertNull(BackupValidation.checkData(back))
    }

    @Test
    fun aCopyWithoutContactDetailsKeepsTheQuestionsAndTheAnswersWhole() {
        val without = BackupData.of(bundle(ExportFixture.options(includeContacts = false), houses = listOf(house1, ExportFixture.house2)))
        assertEquals("doorprints-backup/2", without.format)
        assertEquals(3, without.questionRows.size)
        // A copy is the person's own data: the number in the answer stays (only AI text is redacted).
        assertEquals(listOf(asked, open), without.houses.first { it.id == "h1" }.answers)
        assertNull(without.houses.first { it.id == "h1" }.contactPhone)
        // An update carries the questions changed since.
        val update = bundle(ExportFixture.options().copy(since = 1_788_300_000_000))
        assertEquals(listOf("q_9f8e7d6c"), update.questions.map { it.id })
    }

    @Test
    fun theListReadsBackAndAnAbsentListIsNone() {
        val back = BackupFormat.json.decodeFromString(BackupData.serializer(), text(bundle()))
        assertEquals(listOf(deposit, maintenance, meter), back.questionRows)
        assertNull(BackupValidation.checkData(back))
        val v1 = BackupFormat.json.decodeFromString(BackupData.serializer(), "{\"format\":\"doorprints-backup/1\",\"exportedAt\":1}")
        assertEquals(emptyList(), v1.questionRows)
    }

    @Test
    fun aBadQuestionRefusesTheWholeFile() {
        val good = BackupData.of(bundle())
        fun q(vararg rows: ExportQuestion) = BackupValidation.checkData(good.copy(questions = rows.toList()))
        assertNull(q(meter.copy(category = "GARDEN", appliesTo = "LEASE")), "an unknown category or scope reads as OTHER / BOTH")
        assertEquals(BackupProblem.BROKEN_DATA, q(meter.copy(id = "a/b")))
        assertEquals(BackupProblem.BROKEN_DATA, q(meter.copy(id = "..")))
        assertEquals(BackupProblem.BROKEN_DATA, q(meter.copy(text = "  ")))
        assertEquals(BackupProblem.BROKEN_DATA, q(meter.copy(text = "x".repeat(301))))
        assertEquals(BackupProblem.BROKEN_DATA, q(meter.copy(sort = -1)))
        assertEquals(BackupProblem.BROKEN_DATA, q(meter, meter))
        val many = (0..100).map { meter.copy(id = "q_" + it.toString(16).padStart(8, '0')) }
        assertEquals(BackupProblem.BROKEN_DATA, q(*many.toTypedArray()))
        assertNull(q(*many.take(100).toTypedArray()))
        assertEquals(
            BackupProblem.BROKEN_DATA,
            BackupValidation.checkManifest(
                BackupManifest(format = "doorprints-backup/2", createdAt = "x", counts = BackupCounts(0, 0, 0, questions = -1)),
            ),
        )
    }

    @Test
    fun aBadAnswerRefusesTheWholeFile() {
        val good = BackupData.of(bundle(houses = listOf(house1, ExportFixture.house2)))
        fun with(vararg answers: HouseAnswer) =
            BackupValidation.checkData(good.copy(houses = listOf(house1.copy(answers = answers.toList()), ExportFixture.house2)))
        assertNull(with(asked, open))
        for (bad in listOf(
            asked.copy(id = "a/b"), asked.copy(id = "."), asked.copy(text = ""), asked.copy(text = "x".repeat(301)),
            asked.copy(answer = "y".repeat(2001)), asked.copy(status = "LATER"), asked.copy(sort = -1),
        )) assertEquals(BackupProblem.BROKEN_DATA, with(bad), bad.toString())
        assertEquals(BackupProblem.BROKEN_DATA, with(asked, asked.copy(sort = 1)), "a duplicate id")
        val sixtyOne = (0..60).map { HouseAnswer("a$it", text = "Q$it", sort = it) }
        assertEquals(BackupProblem.BROKEN_DATA, with(*sixtyOne.toTypedArray()), "61 answers")
        assertNull(with(*sixtyOne.take(60).toTypedArray()))
    }

    // ---- the import ----

    private fun file(questions: List<ExportQuestion>) = BackupData(format = "doorprints-backup/2", exportedAt = 1, questions = questions)

    private var ids = 0
    private fun plan(data: BackupData, local: Map<String, Long>, mode: ImportMode = ImportMode.MERGE, skip: Boolean = false) =
        ImportPlan.plan(
            data, emptyMap(), emptyMap(), emptySet(), emptySet(), mode, newId = { "id${ids++}" }, skipUpdates = skip,
            localQuestions = local,
        )

    private fun preview(data: BackupData, local: Map<String, Long>, mode: ImportMode = ImportMode.MERGE, skip: Boolean = false) =
        ImportPlan.preview(data, emptyMap(), emptyMap(), emptySet(), emptySet(), mode, skipUpdates = skip, localQuestions = local)

    @Test
    fun questionsMergeByIdTheNewerWinsAndNothingIsDeleted() {
        val data = file(listOf(deposit, meter))
        // deposit is newer in the file, meter is new here; maintenance (only here) is not touched.
        val local = mapOf(deposit.id to deposit.updatedAt - 1, maintenance.id to maintenance.updatedAt)
        assertEquals(listOf(deposit, meter), plan(data, local).questions)
        val p = preview(data, local)
        assertEquals(1, p.newQuestions)
        assertEquals(1, p.updatedQuestions)
        assertEquals(1, p.overwrites)
        assertFalse(p.isEmpty)
        // Older or equal here: left alone. A tombstone here with a later time keeps a deleted default deleted.
        assertEquals(emptyList(), plan(data, mapOf(deposit.id to deposit.updatedAt, meter.id to meter.updatedAt + 5)).questions)
        // "Keep mine": only the new ones.
        assertEquals(listOf(meter), plan(data, local, skip = true).questions)
        assertEquals(0, preview(data, local, skip = true).updatedQuestions)
    }

    @Test
    fun aCopyImportKeepsTheQuestionIdsAndTheAnswersOfItsHouses() {
        val data = BackupData(format = BackupFormat.ID_2, exportedAt = 1, houses = listOf(house1), questions = listOf(deposit, meter))
        val actions = plan(data, mapOf(deposit.id to deposit.updatedAt + 1), ImportMode.COPY)
        assertEquals(listOf(meter), actions.questions)
        assertEquals(listOf(asked, open), actions.houses.single().answers, "the answer ids are the house's own")
        assertEquals(1, preview(data, mapOf(deposit.id to deposit.updatedAt + 1), ImportMode.COPY).newQuestions)
    }
}
