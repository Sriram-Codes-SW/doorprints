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
import app.doorprints.shared.records.RecordType
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.random.Random

/**
 * A checklist criterion (docs/11 5.4, slice 2 of 5.30): a record of type `criterion` whose id is [key]. The payload
 * keys are, in this order, [label] (custom criteria only; a built-in's name stays translated in the apps), [weight],
 * [mustHave], [minScore], [sort] and [archived] (written only when true). A built-in with no record is [default].
 * The TypeScript twin is `web/src/app/shared/scoring.ts`; the server stores the same keys in its `record` table.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Criterion(
    /** The record id, not part of the payload: a built-in key (`Checklist.keys`) or `c_` + 8 lowercase hex. */
    @Transient val key: String = "",
    /** 1..[MAX_LABEL] characters, custom criteria only. */
    val label: String? = null,
    /** 0 Ignore, 1 Low, 2 Medium, 3 High. */
    val weight: Int = DEFAULT_WEIGHT,
    val mustHave: Boolean = false,
    /** 1..5: a must-have whose score is below this is missed. */
    val minScore: Int = DEFAULT_MIN_SCORE,
    /** The place in the list, 0 first; ties go by key. */
    val sort: Int = 0,
    /** Hidden from the form and left out of the score; its stored scores stay. Written only when true. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val archived: Boolean = false,
) {
    val isBuiltIn: Boolean get() = key in Checklist.keys

    /** Counted in the weighted checklist and the coverage: not archived and not *Ignore*. */
    val isActive: Boolean get() = !archived && weight > 0

    /**
     * What a reader keeps (the records table, a sync, another device), like [Broker.coerced]: a weight or minimum score
     * out of range becomes the default, a negative sort the default place, a built-in's label goes and a custom one is
     * trimmed and cut to [MAX_LABEL]. Null for a key outside `RecordRules.isValidId`: the row is skipped.
     */
    fun coerced(): Criterion? {
        if (!RecordRules.isValidId(key)) return null
        return copy(
            label = if (isBuiltIn) null else label?.trim()?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() },
            weight = weight.takeIf { it in WEIGHTS } ?: DEFAULT_WEIGHT,
            minScore = minScore.takeIf { it in MIN_SCORES } ?: DEFAULT_MIN_SCORE,
            sort = sort.takeIf { it >= 0 } ?: defaultSort(key),
        )
    }

    /** True when every value is in range: what a backup's check demands (a bad file is refused whole). */
    val isValid: Boolean
        get() = RecordRules.isValidId(key) && weight in WEIGHTS && minScore in MIN_SCORES && sort >= 0 &&
            (label == null || (!isBuiltIn && label.length <= MAX_LABEL))

    /** True when this built-in says nothing its [default] does not: its record is not needed. */
    val isDefault: Boolean get() = isBuiltIn && copy(label = null) == default(key)

    companion object {
        const val MAX_LABEL = 60
        const val DEFAULT_WEIGHT = 2
        const val DEFAULT_MIN_SCORE = 3

        /** At most this many criteria in all, built-in ones included; the repository refuses one more. */
        const val MAX_CRITERIA = 40
        const val CUSTOM_PREFIX = "c_"

        val WEIGHTS: IntRange = 0..3
        val MIN_SCORES: IntRange = 1..5
        private val CUSTOM_KEY = Regex("c_[0-9a-f]{8}")

        /** A built-in criterion with no record: Medium, not a must-have, minimum 3, in its `Checklist.keys` place. */
        fun default(key: String): Criterion = Criterion(key = key, sort = defaultSort(key))

        /** A built-in's index in `Checklist.keys`, and after them for any other key. */
        fun defaultSort(key: String): Int = Checklist.keys.indexOf(key).takeIf { it >= 0 } ?: Checklist.keys.size

        fun isCustomKey(key: String): Boolean = CUSTOM_KEY.matches(key)

        /** A fresh custom key, `c_` + 8 random lowercase hex digits, drawn again while [taken] says it is used. */
        fun newCustomKey(taken: (String) -> Boolean, random: Random = Random.Default): String {
            while (true) {
                val hex = (1..8).joinToString("") { random.nextInt(16).toString(16) }
                val key = CUSTOM_PREFIX + hex
                if (!taken(key)) return key
            }
        }
    }
}

/** The `criterion` record type of the `records` table (slice 0). */
val CriterionType: RecordType<Criterion> = RecordType("criterion", Criterion.serializer())

/** A preference (slice 2): a record of type `preference`, id = its key, payload `{value}` (≤ [MAX_VALUE] characters). */
@Serializable
data class Preference(val value: String) {
    companion object {
        const val MAX_VALUE = 500

        /** How much the star rating counts against the checklist, "0".."1"; default [Scoring.DEFAULT_RATING_SHARE]. */
        const val RATING_SHARE = "score.ratingShare"
    }
}

/** The `preference` record type of the `records` table. */
val PreferenceType: RecordType<Preference> = RecordType("preference", Preference.serializer())

/**
 * The effective scoring: [criteria] is the full list, the defaults merged with the records, sorted by `sort` then key
 * (archived ones included; [active] leaves them out), and [ratingShare] the star rating's share of the overall score.
 */
data class Scoring(val criteria: List<Criterion>, val ratingShare: Double) {
    private val byKey: Map<String, Criterion> = criteria.associateBy { it.key }

    /** The criteria that count: not archived, weight above 0. */
    val active: List<Criterion> get() = criteria.filter { it.isActive }

    operator fun get(key: String): Criterion? = byKey[key]

    companion object {
        const val DEFAULT_RATING_SHARE = 0.5

        /** The ten built-ins at their defaults and a rating share of 0.5: the scoring before slice 2 (vector V7). */
        val DEFAULT: Scoring = of(emptyList(), emptyMap())

        /**
         * The effective scoring from the criterion records (each with its [Criterion.key] set; coerced here, a bad key
         * skipped) and the preference records as key to value. A built-in without a record gets [Criterion.default].
         */
        fun of(records: List<Criterion>, preferences: Map<String, String>): Scoring {
            val kept = records.mapNotNull { it.coerced() }.associateBy { it.key }
            val builtIns = Checklist.keys.map { kept[it] ?: Criterion.default(it) }
            val custom = kept.values.filter { !it.isBuiltIn }
            val all = (builtIns + custom).sortedWith(compareBy({ it.sort }, { it.key }))
            return Scoring(all, ratingShare(preferences[Preference.RATING_SHARE]))
        }

        /** A stored share as a number: 0..1, else (absent, not a number, out of range) the default 0.5. */
        fun ratingShare(value: String?): Double =
            value?.trim()?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: DEFAULT_RATING_SHARE

        /** A share as it is stored: "0", "0.25", "1", as the web's `String(n)` writes it. */
        fun shareText(share: Double): String {
            val whole = share.toLong()
            return if (whole.toDouble() == share) whole.toString() else share.toString()
        }
    }
}

/**
 * One house's score under a [Scoring] ([HouseScore.evaluate]). [overall] is 0..5 or null when nothing is scored;
 * [coverage] the share of the active weight that is scored (null with no active criteria); [failedMustHave] and
 * [uncheckedMustHave] the keys of must-haves scored below their minimum and not scored yet, in the scoring's order.
 * [weighted] is the checklist part alone, and [scored] of [active] criteria have a score (the form's coverage line).
 */
data class ScoreResult(
    val overall: Double?,
    val coverage: Double?,
    val failedMustHave: List<String>,
    val uncheckedMustHave: List<String>,
    val weighted: Double? = null,
    val scored: Int = 0,
    val active: Int = 0,
) {
    val missedMustHave: Boolean get() = failedMustHave.isNotEmpty()
}

/** What [Ranking] orders a house by: its score, price and last change, and its id for a total order. */
data class RankedHouse(val id: String, val score: ScoreResult, val price: Long?, val updatedAt: Long)

/**
 * The one ranking of both apps (docs/11 5.4): (1) no missed must-have before a missed one, (2) overall descending,
 * unscored last, (3) coverage descending (none as 0), (4) price ascending, unknown last, (5) latest change first, then
 * the id, so the order is total and the same on every device. Best first in the list, Compare and the readable copies.
 */
object Ranking {
    val comparator: Comparator<RankedHouse> = compareBy<RankedHouse> { it.score.missedMustHave }
        .thenBy { it.score.overall == null }
        .thenByDescending { it.score.overall ?: 0.0 }
        .thenByDescending { it.score.coverage ?: 0.0 }
        .thenBy { it.price == null }
        .thenBy { it.price ?: 0L }
        .thenByDescending { it.updatedAt }
        .thenBy { it.id }

    /** Orders two houses by [comparator]: negative when [a] ranks first. */
    fun compare(a: RankedHouse, b: RankedHouse): Int = comparator.compare(a, b)

    /** [items] best first, each ranked by [of]. */
    fun <T> sort(items: List<T>, of: (T) -> RankedHouse): List<T> {
        val keyed = items.map { it to of(it) }
        return keyed.sortedWith { a, b -> comparator.compare(a.second, b.second) }.map { it.first }
    }
}
