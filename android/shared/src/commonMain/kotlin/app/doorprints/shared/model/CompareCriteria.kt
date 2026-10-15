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

import kotlinx.serialization.json.JsonObject

/**
 * One thing Compare scores for a kind (kinds PR 27, docs/03 ADR-36 section 18.21): a checklist item (scored 0 to 5, higher is
 * better) or a typed extra of the kind. [label] is the Compose string's name; [better] is `higher`, `lower` or null (neither: the
 * value is shown and never marked).
 */
data class CompareCriterion(val id: String, val source: Source, val label: String, val better: String?) {
    enum class Source { CHECK, EXTRA }
}

/** What Compare shows for some candidates of one kind, as `compare-vectors.json` pins it. */
data class CompareOutcome(val ranking: List<String>, val overall: Map<String, Double?>, val best: Map<String, List<String>>)

/** A candidate as [CompareCriteria.outcome] reads it. */
data class CompareCandidate(val id: String, val rating: Int?, val checklist: Map<String, Int>, val extrasOther: JsonObject?)

/**
 * What Compare scores and marks for a kind: the kind file's `capabilities.compare.criteria`, resolved. The twin of the website's
 * `shared/compare-criteria.ts`, held to `docs/schemas/kinds/compare-vectors.json` (`CompareCriteriaVectorsTest`). The direction of
 * an extra is its own `better`; a checklist item is higher-is-better. The house's criteria are its ten checklist keys, which is what
 * Compare scored before.
 */
object CompareCriteria {
    /**
     * Whether the kind is ranked under the person's own scoring (their weights, must-haves and criteria): a kind whose Compare
     * scores the person's ten checklist keys, which is the house's (`capabilities.compare`, docs/03 section 18.21). The other
     * kinds' checklists are their files' and have fixed weights. Kinds PR 29: this replaces the comparisons with the house's id.
     */
    fun usesPersonScoring(kind: BuiltInKind): Boolean = KindCapabilities.of(kind.id).compare?.criteria == Checklist.keys

    /** The kind's criteria in the kind file's order. */
    fun of(kind: BuiltInKind): List<CompareCriterion> = resolve(KindCapabilities.of(kind.id).compare?.criteria.orEmpty(), kind)

    /**
     * [ids] resolved against the kind: a checklist item of the kind, or a typed extra of the kind that its section shows (a
     * number or an amount gets its own direction, any other type none); an id the kind has neither of is dropped.
     */
    fun resolve(ids: List<String>, kind: BuiltInKind): List<CompareCriterion> = ids.mapNotNull { id ->
        val check = kind.labels.check(id)
        if (check != null) return@mapNotNull CompareCriterion(id, CompareCriterion.Source.CHECK, check, "higher")
        val extra = kind.fields.extras.firstOrNull { it.id == id && it.was == null } ?: return@mapNotNull null
        if (!kind.labels.isVisible("field.$id")) return@mapNotNull null
        val numeric = extra.type == "number" || extra.type == "money"
        CompareCriterion(id, CompareCriterion.Source.EXTRA, kind.fields.label(extra), extra.better.takeIf { numeric && (it == "lower" || it == "higher") })
    }

    /**
     * What the kind's candidates are ranked under: the house's is the person's own [base] (the same object); another kind's is its
     * checklist in order, each item Medium and counted when Compare names it (weight 0 when it does not), no must-haves, under the
     * person's rating share.
     */
    fun scoringFor(kind: BuiltInKind, base: Scoring): Scoring {
        if (usesPersonScoring(kind)) return base
        val counted = of(kind).filter { it.source == CompareCriterion.Source.CHECK }.map { it.id }.toSet()
        val criteria = kind.labels.checklistKeys().mapIndexed { index, key ->
            Criterion(key = key, weight = if (key in counted) Criterion.DEFAULT_WEIGHT else 0, sort = index)
        }
        return Scoring(criteria, base.ratingShare)
    }

    /** The checklist rows Compare shows: the house's every criterion not archived; another kind's the items Compare names. */
    fun comparedRows(kind: BuiltInKind, scoring: Scoring): List<Criterion> =
        scoring.criteria.filter { !it.archived && (usesPersonScoring(kind) || it.weight > 0) }

    /** The positions of the highest score, when one exists and not every value ties (a checklist row). */
    fun bestOf(values: List<Int?>): Set<Int> {
        val present = values.filterNotNull()
        val max = present.maxOrNull() ?: return emptySet()
        val best = values.indices.filter { values[it] == max }.toSet()
        return if (best.size == values.size && values.size > 1) emptySet() else best
    }

    /**
     * The lowest or highest figure of an extra by [better]. Marked only when at least two candidates have a value and they do not
     * all tie; no direction, no mark.
     */
    fun bestOfExtra(values: List<Long?>, better: String?): Set<Int> {
        if (better != "lower" && better != "higher") return emptySet()
        val present = values.filterNotNull()
        val target = (if (better == "lower") present.minOrNull() else present.maxOrNull()) ?: return emptySet()
        if (present.all { it == target }) return emptySet()
        return values.indices.filter { values[it] == target }.toSet()
    }

    /** A candidate's figure for an extra: a number, or the amount of a money value; null otherwise. */
    fun extraFigure(fields: KindFields, extrasOther: JsonObject?, id: String): Long? =
        when (val v = KindValues.of(fields, extrasOther).firstOrNull { it.field.id == id }) {
            is KindValue.Count -> v.value
            is KindValue.Money -> v.amount
            else -> null
        }

    /** The pure core of the Compare table: the ranking, the scores and the cells marked best, for [candidates] in the order given. */
    fun outcome(kind: BuiltInKind, candidates: List<CompareCandidate>, base: Scoring): CompareOutcome {
        val scoring = scoringFor(kind, base)
        val results = candidates.associate { it.id to HouseScore.evaluate(it.checklist, it.rating, scoring) }
        val ranking = Ranking.sort(candidates) { RankedHouse(it.id, results.getValue(it.id), null, 0L) }.map { it.id }
        val best = of(kind).associate { c ->
            val marked = if (c.source == CompareCriterion.Source.CHECK) {
                bestOf(candidates.map { it.checklist[c.id] })
            } else {
                bestOfExtra(candidates.map { extraFigure(kind.fields, it.extrasOther, c.id) }, c.better)
            }
            c.id to candidates.filterIndexed { i, _ -> i in marked }.map { it.id }
        }
        return CompareOutcome(ranking, candidates.associate { it.id to results.getValue(it.id).overall }, best)
    }
}
