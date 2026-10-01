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

/**
 * House status. Stored and sent by [name] (shared with the API, the web app and the Room database), so the constant
 * names must never change. The label is translated by each app (Android: HouseStatus.labelRes in :app). [TAKEN] and
 * [NOT_CHOSEN] (docs/11 5.24, slice 5) are the end of a hunt: at most one house is TAKEN ([HouseStatusRules]), and a
 * file with either is `doorprints-backup/2`. REJECTED keeps its meaning (rejected after looking).
 */
enum class HouseStatus {
    NEW,
    SHORTLISTED,
    REJECTED,
    TAKEN,
    NOT_CHOSEN,
    ;

    companion object {
        /** Parses the wire/database name; anything unknown or missing becomes [NEW] (same as the v0.1 mapper). */
        fun fromWire(value: String?): HouseStatus = entries.firstOrNull { it.name == (value ?: "NEW") } ?: NEW
    }
}

/** How a visit was recorded: by Hunt mode's stay detector or by the user. Stored and sent by [name]. */
enum class VisitSource {
    AUTO,
    MANUAL,
    ;

    companion object {
        /** Parses the wire/database name; anything unknown or missing becomes [MANUAL]. */
        fun fromWire(value: String?): VisitSource = entries.firstOrNull { it.name == (value ?: "MANUAL") } ?: MANUAL
    }
}

/**
 * Things worth checking at every house, each scored 0 (bad) to 5 (great). The keys are language-neutral and shared
 * with the API and the web app (check.water, ...); each app maps them to translated labels. Order is display order.
 */
object Checklist {
    val keys: List<String> = listOf(
        "water",
        "power",
        "parking",
        "sunlight",
        "ventilation",
        "noise",
        "security",
        "maintenance",
        "neighbourhood",
        "commute",
    )

    const val MIN_SCORE = 0
    const val MAX_SCORE = 5
}

/** Same cap as the server's app.limits.max-photos-per-house default (threat model F-06). */
const val MAX_PHOTOS_PER_HOUSE = 20

/**
 * Overall house score (docs/11 5.4, slice 2): the one implementation, used by the list's "best first", Compare, the
 * house form, the Hunt notification and the readable copies. The web's twin is `scoring.ts`; both pass vectors V1..V8.
 */
object HouseScore {
    /**
     * The house's score under [scoring]. Active criteria are the ones not archived and not *Ignore* (weight 0):
     * - the weighted checklist `wc` = Σ(w·s) / Σ(w) over active criteria with a score; null when none. A 0 is a real
     *   score, and a score under a key that is not a known criterion (a newer app's) is ignored;
     * - `overall` = (1 − r)·wc + r·rating when both exist, else whichever exists, null when neither (r = rating share);
     * - `coverage` = Σw of the scored active criteria / Σw of all active ones; null when none is active;
     * - a must-have (not archived; its weight does not matter) scored below its minimum is failed, one not scored yet
     *   is unchecked, not failed.
     */
    fun evaluate(checklist: Map<String, Int>, rating: Int?, scoring: Scoring): ScoreResult {
        var weightSum = 0
        var scoredWeight = 0
        var total = 0.0
        var scored = 0
        var active = 0
        val failed = ArrayList<String>()
        val unchecked = ArrayList<String>()
        for (c in scoring.criteria) {
            val score = checklist[c.key]
            if (c.isActive) {
                active++
                weightSum += c.weight
                if (score != null) {
                    scored++
                    scoredWeight += c.weight
                    total += c.weight * score.toDouble()
                }
            }
            if (c.mustHave && !c.archived) {
                when {
                    score == null -> unchecked += c.key
                    score < c.minScore -> failed += c.key
                }
            }
        }
        val wc = if (scoredWeight > 0) total / scoredWeight else null
        val stars = rating?.toDouble()
        val r = scoring.ratingShare
        val overall = when {
            wc != null && stars != null -> (1 - r) * wc + r * stars
            else -> wc ?: stars
        }
        val coverage = if (weightSum > 0) scoredWeight.toDouble() / weightSum else null
        return ScoreResult(overall, coverage, failed, unchecked, wc, scored, active)
    }

    /** The overall score alone ([evaluate]); the default scoring is the pre-slice formula (vector V7). */
    fun of(checklist: Map<String, Int>, rating: Int?, scoring: Scoring = Scoring.DEFAULT): Double? =
        evaluate(checklist, rating, scoring).overall
}
