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

import app.doorprints.shared.model.KindCapabilities

/**
 * The order of the tables of a CSV or XLSX copy follows the kind file like the Markdown, the readable and the PDF copy do (docs/03
 * section 18.29): `capabilities.copyOrder` names the blocks that come first after the houses table, the rest follow in the tables'
 * fixed order. The web half is `table-order.ts`; both are held to `docs/schemas/kinds/capabilities-vectors.json`, `copyOrder.tables`.
 */
object TableOrder {
    /** The block of a copy each table belongs to. The houses table (always first) and brokers belong to none. */
    val TABLE_BLOCK: Map<String, String> = mapOf(
        "scores" to "checklist", "criteria" to "checklist", "visits" to "visits", "photos" to "photos",
        "rooms" to "rooms", "answers" to "questions", "viewings" to "viewings",
    )

    /** [tables] with the tables of the kind's `copyOrder` blocks right after the houses table, in that order; with no `copyOrder` (the house, the school), as given. */
    fun inCopyOrder(tables: List<ExportTable>, kindId: String?): List<ExportTable> {
        val order = KindCapabilities.of(kindId).copyOrder
        if (order.isEmpty() || tables.isEmpty()) return tables
        val rest = tables.drop(1)
        val first = order.flatMap { block -> rest.filter { TABLE_BLOCK[it.name] == block } }
        return listOf(tables.first()) + first + rest.filter { it !in first }
    }
}
