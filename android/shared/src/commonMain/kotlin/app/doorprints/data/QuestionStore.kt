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

package app.doorprints.data

import app.doorprints.shared.export.ExportQuestion
import app.doorprints.shared.model.DefaultQuestions
import app.doorprints.shared.model.Question
import app.doorprints.shared.model.QuestionCategory
import app.doorprints.shared.model.QuestionScope
import app.doorprints.shared.model.QuestionType
import app.doorprints.shared.records.RecordLimitException
import app.doorprints.shared.records.decode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The viewing-question bank on this phone: how a question is read from its record row, seeding the fourteen defaults,
 * *Reset to defaults* and the local edits of the bank (S4b-BL-168, slice 4). It is separate from [CommonRepository]
 * because the Questions screen, the house form, the exporters, the import (and its undo) all read a question the same
 * way; a new question value edits this file, the model and the backup mapper, not the repository. A house's answers are
 * not here: they are a field of the house, saved with it.
 *
 * A question is a row of the generic record table (type `question`). [records] is the repository's own writer, which
 * checks the id and the size, stamps the row and asks for a sync; [settings] holds the once-only seeding mark.
 */
internal class QuestionStore(
    private val db: AppDatabase,
    private val records: RecordWriter,
    private val settings: SettingsStore,
) {
    private val seedGate = Mutex()

    fun observeAll(): Flow<List<Question>> = db.records().byType(QuestionType.name).map(::sorted)

    suspend fun all(): List<Question> = sorted(db.records().listByType(QuestionType.name))

    /** Writes the defaults that have no record yet and returns how many. A tombstone is a record: a deleted default stays deleted. */
    suspend fun seed(language: String): Int {
        var written = 0
        db.withImmediateTransaction {
            for (d in DefaultQuestions.ALL) {
                if (db.records().get(QuestionType.name, d.id) != null) continue
                // Clean and stamped SEEDED_AT (S4b-BL-90a): an untouched default is never pushed, and whatever another
                // device did to it wins when it is pulled. An edit here makes it dirty with a real time.
                val payload = QuestionType.encode(d.question(language))
                db.records().upsert(
                    RecordEntity(QuestionType.name, d.id, payload, updatedAt = DefaultQuestions.SEEDED_AT, dirty = false),
                )
                written++
            }
        }
        return written
    }

    /** [seed] once per install (the mark is not synced); two starts at once seed once. */
    suspend fun seedOnce(language: String) {
        seedGate.withLock {
            if (settings.questionsSeeded()) return
            seed(language)
            settings.markQuestionsSeeded()
        }
    }

    suspend fun reset(language: String) {
        db.withImmediateTransaction {
            for (d in DefaultQuestions.ALL) {
                // A deleted default that would be the 101st question stays deleted (the web skips it too).
                val live = db.records().get(QuestionType.name, d.id)?.deleted == false
                if (!live && db.records().countLive(QuestionType.name) >= Question.MAX_QUESTIONS) continue
                records.save(QuestionType, d.id, d.question(language))
            }
        }
    }

    suspend fun save(question: Question) {
        db.withImmediateTransaction { write(question) }
    }

    suspend fun saveAll(questions: List<Question>) {
        db.withImmediateTransaction { for (q in questions) write(q) }
    }

    /** One question as a record; nothing is written when the record already says the same (a renumbering stamps only the moved). */
    private suspend fun write(question: Question) {
        val clean = requireNotNull(question.copy(text = question.text.trim()).coerced()) {
            "a question needs a record id and a text of 1..${Question.MAX_TEXT} characters"
        }
        val stored = db.records().get(QuestionType.name, clean.id)?.takeUnless { it.deleted }
        if (stored?.let(::of) == clean) return
        if (stored == null && db.records().countLive(QuestionType.name) >= Question.MAX_QUESTIONS) {
            throw RecordLimitException(QuestionType.name, Question.MAX_QUESTIONS)
        }
        records.save(QuestionType, clean.id, clean)
    }

    /** A new custom question at the end of the bank; returns its id. */
    suspend fun add(text: String, category: QuestionCategory, appliesTo: QuestionScope, defaultOn: Boolean): String {
        val words = text.trim()
        require(words.isNotEmpty() && words.length <= Question.MAX_TEXT) { "a question needs 1..${Question.MAX_TEXT} characters" }
        var id = ""
        db.withImmediateTransaction {
            val live = db.records().listByType(QuestionType.name)
            if (live.size >= Question.MAX_QUESTIONS) throw RecordLimitException(QuestionType.name, Question.MAX_QUESTIONS)
            // An id a tombstone holds is taken too: reusing it would bring the old question back on another device.
            val used = db.records().versions(QuestionType.name).mapTo(HashSet()) { it.id }
            id = Question.newCustomId({ it in used })
            val sort = (live.mapNotNull(::of).maxOfOrNull { it.sort } ?: -1) + 1
            records.save(QuestionType, id, Question(id, words, category.name, appliesTo.name, defaultOn, sort))
        }
        return id
    }

    suspend fun delete(id: String) = records.delete(QuestionType, id)

    private fun sorted(rows: List<RecordEntity>): List<Question> = rows.mapNotNull(::of).sortedWith(Question.ORDER)

    companion object {
        /** A question row with its id, coerced; null when the payload does not decode or cannot be trusted (skipped). */
        fun of(row: RecordEntity): Question? = row.decode(QuestionType)?.copy(id = row.id)?.coerced()

        /** A backup's question as its record row: coerced (the plan checked it), dirty so it is pushed. */
        fun importedRow(q: ExportQuestion, updatedAt: Long): RecordEntity = RecordEntity(
            type = QuestionType.name, id = q.id,
            payload = QuestionType.encode(checkNotNull(q.toQuestion().coerced()) { "question ${q.id} was not checked" }),
            updatedAt = updatedAt, deleted = false, dirty = true,
        )
    }
}
