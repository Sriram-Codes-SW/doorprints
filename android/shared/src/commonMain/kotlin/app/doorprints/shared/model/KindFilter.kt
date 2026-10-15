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

/**
 * The list's filter by a kind's typed extra (S4b-BL-265; docs/03 §18.10, §18.16), the twin of the website's
 * `shared/extra-filter.ts`: one chip row per enum or switch the kind has with no house field, the choice of an option (or
 * `yes` or `no` for a switch) or nothing (*Any*). A house has none, so its list is as it was.
 * `docs/schemas/kinds/kind-filter-vectors.json` holds both stacks to the same rules (`KindFilterVectorsTest`).
 *
 * A filter is a map of extra id to the chosen option id (`yes` or `no` for a switch); an id absent, or mapped to null, is *Any*.
 */
object KindFilter {
    const val YES = "yes"
    const val NO = "no"

    /** The extras of [fields] the list can be filtered by, in the kind file's order: enums and switches. */
    fun rows(fields: KindFields): List<KindExtra> = fields.others().filter { it.type == "enum" || it.type == "bool" }

    /** The choices of a filterable [extra]: an enum's option ids, or `yes` and `no` for a switch. */
    fun choices(extra: KindExtra): List<String> = if (extra.type == "bool") listOf(YES, NO) else extra.options.map { it.id }

    /** How many rows are set (a choice the kind does not offer is no row). */
    fun active(filter: Map<String, String?>, fields: KindFields): Int = rows(fields).count { filter[it.id]?.let { c -> c in choices(it) } == true }

    /**
     * Whether a candidate holding [extrasOther] passes every set row: an enum equals the option, a switch is on for `yes` and
     * off for `no`. A candidate with no value there (not set, or not one the kind offers) passes no set row: *No* means a
     * switch somebody turned off. A row the kind does not have, or a choice it does not offer, is ignored.
     */
    fun matches(filter: Map<String, String?>, fields: KindFields, extrasOther: JsonObject?): Boolean {
        for (f in rows(fields)) {
            val want = filter[f.id]?.takeIf { it in choices(f) } ?: continue
            val raw: JsonElement? = extrasOther?.get(f.id)
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
