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

package app.doorprints.shared.ai

/**
 * A saved house as on-device AI sees it (docs/03 §13.1): the fields the server's `HouseDto` gives its AI code, from
 * the phone's database (`HouseEntity`) or the website's store. The contact fields are here only so [ContactRedactor]
 * can remove them from the other fields; they are never put in a prompt.
 */
data class AiHouse(
    val id: String,
    val label: String?,
    val address: String? = null,
    val street: String? = null,
    val locality: String? = null,
    val lat: Double,
    val lon: Double,
    /** NEW, SHORTLISTED or REJECTED. */
    val status: String? = "NEW",
    val price: Long? = null,
    /** RENT or SALE. */
    val priceType: String? = null,
    val bedrooms: Int? = null,
    val rating: Int? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val listingUrl: String? = null,
    val notes: String? = null,
    val checklist: Map<String, Int> = emptyMap(),
    /** Newest last or in any order; only arrivals and departures are read. */
    val visits: List<AiVisit> = emptyList(),
)

/** One visit, in epoch milliseconds. */
data class AiVisit(val arrivedAt: Long, val leftAt: Long? = null)
