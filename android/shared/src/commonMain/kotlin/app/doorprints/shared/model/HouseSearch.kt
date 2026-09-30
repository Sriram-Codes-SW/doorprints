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
 * The house list's search, the same rule on Android and the website (owner rule, CLAUDE.md: "search grows with the
 * house values"; readiness review 2026-09-29, docs/14 §8 finding 5): a house matches when any of its text values
 * contains the query, case-insensitively, as `searchText` in `web/src/app/pages/map/map-list.ts` does. A change that
 * adds a house field adds it to [fields] here and to `searchText`, and one case to both tests (`HouseSearchTest`,
 * `map-list.spec.ts`), which keep the same list of cases.
 */
object HouseSearch {
    /**
     * The values a house is searched by: label, address, street, locality, notes, the contact's name and, with a
     * linked broker (slice 1b), [brokerText] (`Broker.searchText`: its name, agency and fee terms), and every room's
     * name and notes (slice 1c), in the order shown.
     */
    fun fields(
        label: String?, address: String?, street: String?, locality: String?, notes: String?, contactName: String?,
        brokerText: String? = null, rooms: List<HouseRoom>? = null,
    ): List<String> =
        (listOfNotNull(label, address, street, locality, notes, contactName, brokerText) +
            rooms.orEmpty().flatMap { listOfNotNull(it.name, it.notes) }).filter { it.isNotEmpty() }

    /** True when [query] is blank or one of [fields] contains it, ignoring case. */
    fun matches(query: String, fields: List<String>): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return fields.any { it.contains(q, ignoreCase = true) }
    }
}
