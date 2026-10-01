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

import app.doorprints.shared.model.Checklist
import app.doorprints.shared.model.Criterion
import app.doorprints.shared.model.Question
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An import stays within the question bank's 100 and the 40 criteria (built-in ones included), counting what is here
 * and what the file adds (S4b-BL-90b): a row that replaces a live one here always goes, one that adds a row goes while
 * there is room, in file order; the preview says the same as the plan.
 */
class ImportCapsTest {
    private fun hex(i: Int) = i.toString(16).padStart(8, '0')
    private fun question(i: Int, at: Long = 2_000) = ExportQuestion("q_${hex(i)}", "Q$i", "OTHER", "BOTH", false, i, updatedAt = at)
    private fun criterion(key: String, at: Long = 2_000) = ExportCriterion(key, if (key in Checklist.keys) null else key, 2, false, 3, 0, updatedAt = at)

    private fun plan(data: BackupData, mode: ImportMode, local: Map<String, Long>, liveQ: Set<String> = emptySet(), liveC: Set<String> = emptySet()) =
        ImportPlan.plan(
            data, emptyMap(), emptyMap(), emptySet(), emptySet(), mode, newId = { "id" },
            localQuestions = local, localCriteria = local, liveQuestions = liveQ, liveCriteria = liveC,
        )

    private fun preview(data: BackupData, mode: ImportMode, local: Map<String, Long>, liveQ: Set<String> = emptySet(), liveC: Set<String> = emptySet()) =
        ImportPlan.preview(
            data, emptyMap(), emptyMap(), emptySet(), emptySet(), mode,
            localQuestions = local, localCriteria = local, liveQuestions = liveQ, liveCriteria = liveC,
        )

    @Test
    fun aMergedImportCannotTakeTheBankPastOneHundredQuestions() {
        // 95 here (one of them older than the file's copy), a tombstone, and a file of 10: the update and 5 new ones go.
        val here = (0 until 95).map { question(it, at = 1_000) }
        val local = here.associate { it.id to it.updatedAt } + ("q_${hex(500)}" to 1_000L)
        val live = here.mapTo(HashSet()) { it.id }
        val file = listOf(question(0)) + listOf(question(500)) + (200 until 208).map { question(it) }
        val data = BackupData(format = BackupFormat.ID_2, exportedAt = 1, questions = file)
        for (mode in listOf(ImportMode.MERGE, ImportMode.COPY)) {
            val written = plan(data, mode, local, liveQ = live).questions.map { it.id }
            // The update of q_0 adds none; the tombstone's revival and four new ones fill the bank to 100.
            assertEquals(listOf(question(0), question(500)).map { it.id } + (200 until 204).map { question(it).id }, written, mode.name)
            assertEquals(Question.MAX_QUESTIONS, live.size + written.count { it !in live })
            val p = preview(data, mode, local, liveQ = live)
            assertEquals(1 + 1, p.updatedQuestions, mode.name) // q_0 and the tombstone's id are both known here
            assertEquals(4, p.newQuestions, mode.name)
        }
    }

    @Test
    fun aFileOnItsOwnIsCutAtOneHundredToo() {
        val data = BackupData(format = BackupFormat.ID_2, exportedAt = 1, questions = (0 until 120).map { question(it) })
        assertEquals(Question.MAX_QUESTIONS, plan(data, ImportMode.MERGE, emptyMap()).questions.size)
        assertEquals(Question.MAX_QUESTIONS, preview(data, ImportMode.MERGE, emptyMap()).newQuestions)
    }

    @Test
    fun aMergedImportCannotTakeTheCriteriaPastForty() {
        // The ten built-ins always count; 28 custom here leave room for 2 more. Built-in keys in the file add none.
        val here = (0 until 28).map { "c_${hex(it)}" }
        val local = here.associateWith { 1_000L }
        val file = Checklist.keys.map { criterion(it) } + (100 until 105).map { criterion("c_${hex(it)}") } + criterion(here[0])
        val data = BackupData(format = BackupFormat.ID_2, exportedAt = 1, criteria = file)
        val written = plan(data, ImportMode.MERGE, local, liveC = here.toSet()).criteria.map { it.key }
        assertEquals(Checklist.keys + listOf("c_${hex(100)}", "c_${hex(101)}") + here[0], written)
        assertEquals(Criterion.MAX_CRITERIA, Checklist.keys.size + here.size + 2)
        assertEquals(12, preview(data, ImportMode.MERGE, local, liveC = here.toSet()).newCriteria)
    }
}
