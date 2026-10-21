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

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The list's filter by a kind's typed extra (S4b-BL-265; docs/03 §18.10, §18.16, §18.29), the twin of the website's
 * `shared/extra-filter.ts`: one chip row per enum or switch the kind has with no house field, the choice of an option (or
 * `yes` or `no` for a switch) or nothing (*Any*), and, since §18.29, a minimum and a maximum for a number or an amount. A house has
 * none, so its list is as it was. `docs/schemas/kinds/kind-filter-vectors.json` holds both stacks to the same rules
 * (`KindFilterVectorsTest`).
 *
 * A filter is a map of extra id to the chosen option id (`yes` or `no` for a switch) or to a range text `min:max` (either end may
 * be left out: `2:`, `:5`, `2:5`); an id absent, or mapped to null, is *Any*.
 */
object KindFilter {
    const val YES = "yes"
    const val NO = "no"

    /** The two ends of a range row (`null`: no limit on that side). */
    data class Range(val min: Double?, val max: Double?)

    private const val END = "-?\\d{1,12}(?:\\.\\d{1,6})?"
    private val RANGE = Regex("^($END)?:($END)?$")

    /**
     * The extras of [fields] the list can be filtered by: the ones the kind file names in `capabilities.filters` (kinds PR 30, docs/03
     * section 18.24), in that order, that are enums, switches, numbers or amounts of the kind. A kind that names none, the house, has no rows.
     */
    fun rows(fields: KindFields): List<KindExtra> {
        val own = fields.others().associateBy { it.id }
        return KindCapabilities.of(fields.kind).filters.mapNotNull { id -> own[id]?.takeIf { it.type == "enum" || it.type == "bool" || isRange(it) } }
    }

    /** Whether the row of [extra] is a range (a number or an amount) rather than a choice. */
    fun isRange(extra: KindExtra): Boolean = extra.type == "number" || extra.type == "money"

    /** The choices of a filterable [extra]: an enum's option ids, or `yes` and `no` for a switch. A range row has none. */
    fun choices(extra: KindExtra): List<String> = when {
        isRange(extra) -> emptyList()
        extra.type == "bool" -> listOf(YES, NO)
        else -> extra.options.map { it.id }
    }

    /**
     * A range text read: its ends, or null when the text is not a range (no end at all, a minimum above the maximum, a number with
     * more than 12 digits or 6 decimals).
     */
    fun parseRange(text: String): Range? {
        val m = RANGE.matchEntire(text) ?: return null
        val min = m.groups[1]?.value?.toDouble()
        val max = m.groups[2]?.value?.toDouble()
        if (min == null && max == null) return null
        return if (min != null && max != null && min > max) null else Range(min, max)
    }

    /**
     * The range text of the two ends a person typed (empty text is an end left out), the ends swapped when the minimum is above the
     * maximum; null for no range (both left out, or a text that is not a number).
     */
    fun rangeText(min: String, max: String): String? {
        var lo = min.trim()
        var hi = max.trim()
        val a = if (lo.isEmpty()) null else lo.toDoubleOrNull()
        val b = if (hi.isEmpty()) null else hi.toDoubleOrNull()
        if ((lo.isNotEmpty() && a == null) || (hi.isNotEmpty() && b == null)) return null
        if (a != null && b != null && a > b) lo = hi.also { hi = lo }
        return "$lo:$hi".takeIf { parseRange(it) != null }
    }

    /** Whether [want] is a value the row of [f] can hold: one of its choices, or a range text. */
    private fun wanted(f: KindExtra, want: String): Boolean = if (isRange(f)) parseRange(want) != null else want in choices(f)

    /** How many rows are set (a value the kind does not offer is no row). */
    fun active(filter: Map<String, String?>, fields: KindFields): Int = rows(fields).count { filter[it.id]?.let { c -> wanted(it, c) } == true }

    /** The number a candidate holds for a range row: a number extra's number, or an amount extra's amount; null for no value. */
    private fun numberOf(f: KindExtra, raw: JsonElement?): Double? = when (f.type) {
        "money" -> ((raw as? JsonObject)?.get("amount") as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        "number" -> (raw as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        else -> null
    }

    /**
     * Whether a candidate holding [extrasOther] passes every set row: an enum equals the option, a switch is on for `yes` and
     * off for `no`, a number or an amount is within the range (an amount is compared as stored, whatever its cadence). A
     * candidate with no value there (not set, or not one the kind offers) passes no set row: *No* means a switch somebody turned
     * off. A row the kind does not have, or a value it does not offer, is ignored.
     */
    fun matches(filter: Map<String, String?>, fields: KindFields, extrasOther: JsonObject?): Boolean {
        for (f in rows(fields)) {
            val want = filter[f.id]?.takeIf { wanted(f, it) } ?: continue
            val raw: JsonElement? = extrasOther?.get(f.id)
            if (isRange(f)) {
                val range = checkNotNull(parseRange(want))
                val n = numberOf(f, raw)
                if (n == null || (range.min != null && n < range.min) || (range.max != null && n > range.max)) return false
                continue
            }
            val ok = if (f.type == "bool") {
                val on = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                if (want == YES) on == true else on == false
            } else {
                (raw as? JsonPrimitive)?.takeIf { it.isString }?.content == want
            }
            if (!ok) return false
        }
        return true
    }
}
