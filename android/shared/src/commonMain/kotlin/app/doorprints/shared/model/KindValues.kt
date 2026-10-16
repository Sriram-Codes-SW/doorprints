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

import app.doorprints.shared.export.ExportRows
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** One typed extra of a candidate, held to its type ([KindValues]). */
sealed interface KindValue {
    val field: KindExtra

    data class Money(override val field: KindExtra, val amount: Long) : KindValue
    data class Count(override val field: KindExtra, val value: Long) : KindValue
    data class Bool(override val field: KindExtra, val value: Boolean) : KindValue

    /** An option the kind offers, by its label's Android key. */
    data class Option(override val field: KindExtra, val label: String) : KindValue

    /** A text or a date, not empty. */
    data class Text(override val field: KindExtra, val value: String) : KindValue
}

/**
 * A candidate's typed extras as search and the readable copies read them (kinds PR 6, S4b-BL-210; docs/03 ADR-36
 * §18.10), the twin of the website's `shared/kind-values.ts`: the extras the kind has no `was` for ([KindFields.others]),
 * held to their type as the form holds them (`:ui` `KindExtrasFields`): a whole number, a money object with an amount, a
 * switch, an option the kind offers, a text or a date. A wrong value is no value. A house has no such extra, so it reads
 * nothing here. `docs/schemas/kinds/kind-search-vectors.json` holds both stacks to the same words.
 */
object KindValues {
    /** The values [extrasOther] holds for the kind's other extras (only those `search` names when [searched]), in order. */
    fun of(fields: KindFields, extrasOther: JsonObject?, searched: Boolean = false): List<KindValue> =
        if (extrasOther == null) emptyList() else fields.others(searched).mapNotNull { valueOf(it, extrasOther[it.id]) }

    private fun valueOf(f: KindExtra, raw: JsonElement?): KindValue? {
        val p = (raw as? JsonPrimitive)
        val number = p?.takeUnless { it.isString }
        val text = p?.takeIf { it.isString }?.content
        return when (f.type) {
            "money" -> ((raw as? JsonObject)?.get("amount") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                ?.let { KindValue.Money(f, it) }
            "number" -> number?.longOrNull?.let { KindValue.Count(f, it) }
            "bool" -> number?.booleanOrNull?.let { KindValue.Bool(f, it) }
            "enum" -> f.options.firstOrNull { it.id == text }?.let { KindValue.Option(f, it.label) }
            else -> text?.takeIf { it.isNotEmpty() }?.let { KindValue.Text(f, it) }
        }
    }

    /**
     * The best of a kind's number or amount where the kind file says which way is better ([KindExtra.better], S4b-BL-292; the
     * twin of the website's `bestExtra`): the positions of the lowest or the highest of [values] (one per compared candidate, null
     * where it has none). Marked only when at least two candidates have a value and they do not all tie (one value, or none, is a
     * tie with itself); an extra with no hint, or one that is not a number or an amount, has none.
     */
    fun best(extra: KindExtra, values: List<KindValue?>): Set<Int> {
        val lower = when (extra.better) { "lower" -> true; "higher" -> false; else -> return emptySet() }
        val nums = values.map { v -> (v as? KindValue.Count)?.value ?: (v as? KindValue.Money)?.amount }
        val present = nums.filterNotNull()
        val target = (if (lower) present.minOrNull() else present.maxOrNull()) ?: return emptySet()
        if (present.all { it == target }) return emptySet()
        return nums.indices.filter { nums[it] == target }.toSet()
    }

    /**
     * The words a candidate's extras add to the list search, after every other: an option's label and a switch's label
     * (only when it is on) in English and, when [language] is another and its words differ, in it too (as the floor is
     * found, S4b-BL-104 b); a number's digits; a text as typed; money as its digits and as rupees (`₹96,000`).
     */
    fun searchWords(fields: KindFields, extrasOther: JsonObject?, language: String): List<String> = buildList {
        fun both(key: String) {
            val en = KindWords.of(key, "en") ?: return
            add(en)
            KindWords.of(key, language)?.takeIf { it != en }?.let(::add)
        }
        for (v in of(fields, extrasOther, searched = true)) {
            when (v) {
                is KindValue.Option -> both(v.label)
                is KindValue.Bool -> if (v.value) both(fields.label(v.field))
                is KindValue.Money -> addAll(listOf(v.amount.toString(), ExportRows.rupees(v.amount)))
                is KindValue.Count -> add(v.value.toString())
                is KindValue.Text -> add(v.value)
            }
        }
    }
}
