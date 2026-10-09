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

import app.doorprints.shared.export.ExportCriterion
import app.doorprints.shared.export.ExportPreference
import app.doorprints.shared.model.Checklist
import app.doorprints.shared.model.Criterion
import app.doorprints.shared.model.CriterionType
import app.doorprints.shared.model.Preference
import app.doorprints.shared.model.PreferenceType
import app.doorprints.shared.model.Scoring
import app.doorprints.shared.records.RecordLimitException
import app.doorprints.shared.records.decode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The house-scoring criteria on this phone: how a criterion and the rating share are read from their record rows (the
 * [Scoring] every screen reads), the local edits (save, renumber, add a custom one, delete one no house uses, the rating
 * share, *Reset to defaults*) and the backup's rows for both (S4b-BL-168, slice 5). It is separate from
 * [CommonRepository] because the house form, the list's score, Compare, the Criteria screen, the exporters and the
 * import (and its undo) all read a criterion the same way; a new criterion value edits this file, the model and the
 * backup mapper, not the repository.
 *
 * A criterion and a preference are rows of the generic record table. [records] is the repository's own writer, which
 * checks the id and the size, stamps the row and asks for a sync.
 */
internal class CriterionStore(
    private val db: AppDatabase,
    private val records: RecordWriter,
) {
    fun observeScoring(): Flow<Scoring> =
        combine(db.records().byType(CriterionType.name), db.records().byType(PreferenceType.name), ::scoringOf)

    suspend fun scoring(): Scoring =
        scoringOf(db.records().listByType(CriterionType.name), db.records().listByType(PreferenceType.name))

    private fun scoringOf(criteria: List<RecordEntity>, preferences: List<RecordEntity>): Scoring = Scoring.of(
        criteria.mapNotNull(::of),
        preferences.mapNotNull { row -> row.decode(PreferenceType)?.let { row.id to it.value } }.toMap(),
    )

    suspend fun save(criterion: Criterion) {
        db.withImmediateTransaction { write(criterion, scoring()) }
    }

    suspend fun saveAll(criteria: List<Criterion>) {
        db.withImmediateTransaction {
            for (c in criteria) write(c, scoring())
        }
    }

    /**
     * One criterion as a record, or no record for a built-in at its default (a reset of that one). Nothing is written
     * when the record already says the same, so a renumbering does not stamp criteria that did not move.
     */
    private suspend fun write(criterion: Criterion, current: Scoring) {
        val clean = requireNotNull(criterion.coerced()) { "criterion key '${criterion.key}' is not [A-Za-z0-9._-]{1,64}" }
        if (clean.isDefault) {
            records.delete(CriterionType, clean.key)
            return
        }
        val stored = db.records().get(CriterionType.name, clean.key)?.takeUnless { it.deleted }?.let(::of)
        if (stored == clean) return
        if (current[clean.key] == null && current.criteria.size >= Criterion.MAX_CRITERIA) {
            throw RecordLimitException(CriterionType.name, Criterion.MAX_CRITERIA)
        }
        records.save(CriterionType, clean.key, clean)
    }

    /** A new custom criterion at the end of the list; returns its key. */
    suspend fun add(label: String, weight: Int): String {
        val name = label.trim()
        require(name.isNotEmpty() && name.length <= Criterion.MAX_LABEL) { "a criterion needs a name of 1..${Criterion.MAX_LABEL} characters" }
        var key = ""
        db.withImmediateTransaction {
            val current = scoring()
            if (current.criteria.size >= Criterion.MAX_CRITERIA) throw RecordLimitException(CriterionType.name, Criterion.MAX_CRITERIA)
            // A key a tombstone holds is taken too: reusing it would bring back the old criterion's scores on the houses.
            val used = db.records().versions(CriterionType.name).mapTo(HashSet()) { it.id }
            key = Criterion.newCustomKey({ it in used })
            val sort = (current.criteria.maxOfOrNull { it.sort } ?: -1) + 1
            records.save(CriterionType, key, Criterion(key = key, label = name, weight = weight, sort = sort).coerced()!!)
        }
        return key
    }

    /** Deletes a custom criterion no live house has a score for; false for a built-in or one in use. */
    suspend fun delete(key: String): Boolean {
        if (key in Checklist.keys) return false
        var deleted = false
        db.withImmediateTransaction {
            if (db.houses().all().none { !it.deleted && it.checklist.containsKey(key) }) {
                records.delete(CriterionType, key)
                deleted = true
            }
        }
        return deleted
    }

    suspend fun saveRatingShare(share: Double) {
        val value = share.coerceIn(0.0, 1.0)
        if (value == Scoring.DEFAULT_RATING_SHARE) {
            records.delete(PreferenceType, Preference.RATING_SHARE)
        } else {
            records.save(PreferenceType, Preference.RATING_SHARE, Preference(Scoring.shareText(value)))
        }
    }

    suspend fun reset() {
        db.withImmediateTransaction {
            for (row in db.records().listByType(CriterionType.name)) records.delete(CriterionType, row.id)
            for (row in db.records().listByType(PreferenceType.name)) records.delete(PreferenceType, row.id)
        }
    }

    companion object {
        /** A criterion row with its key, coerced; null when the payload does not decode or the key is not a usable id (skipped). */
        fun of(row: RecordEntity): Criterion? = row.decode(CriterionType)?.copy(key = row.id)?.coerced()

        /** A backup's criterion as its record row: coerced (the plan checked it), dirty so it is pushed. */
        fun importedCriterion(c: ExportCriterion, updatedAt: Long): RecordEntity = RecordEntity(
            type = CriterionType.name, id = c.key,
            payload = CriterionType.encode(checkNotNull(c.toCriterion().coerced()) { "criterion ${c.key} was not checked" }),
            updatedAt = updatedAt, deleted = false, dirty = true,
        )

        /** A backup's preference as its record row, dirty so it is pushed. */
        fun importedPreference(p: ExportPreference, updatedAt: Long): RecordEntity = RecordEntity(
            type = PreferenceType.name, id = p.key, payload = PreferenceType.encode(Preference(p.value)),
            updatedAt = updatedAt, deleted = false, dirty = true,
        )
    }
}
