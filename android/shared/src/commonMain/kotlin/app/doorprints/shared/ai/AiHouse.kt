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

import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.HouseCost
import app.doorprints.shared.model.HouseRoom

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
    /** Carpet area in sq ft (slice 1a). */
    val areaSqft: Int? = null,
    /** The cost (slice 1a); [HouseDocuments] writes every line of it except `myOffer` (docs/11 5.30 item 5). */
    val cost: HouseCost? = null,
    /** The rooms (slice 1c); [HouseDocuments] writes their names, sizes and condition, never their notes. */
    val rooms: List<HouseRoom>? = null,
    /** The questions asked (slice 3a); [HouseDocuments] writes the answered and the open ones, redacted like notes. */
    val answers: List<HouseAnswer>? = null,
    val checklist: Map<String, Int> = emptyMap(),
    /** Newest last or in any order; only arrivals and departures are read. */
    val visits: List<AiVisit> = emptyList(),
    /** The house's viewings (slice 3b-1), in any order; never `withWhom` (contact data). */
    val viewings: List<AiViewing> = emptyList(),
    /** The area notes that reach this house (slice 4a), in any order: the text and its last edit. */
    val areaNotes: List<AiAreaNote> = emptyList(),
    /** The straight-line distance to each of my places (slice 4a), in any order; never a place's coordinates. */
    val distances: List<AiDistance> = emptyList(),
)

/** An area note as the AI sees it (slice 4a): its id, text and last edit (epoch ms), for the newest-first order. */
data class AiAreaNote(val id: String, val text: String, val updatedAt: Long)

/** A distance as the AI sees it (slice 4a): the place's name and the straight-line metres. */
data class AiDistance(val name: String, val meters: Double)

/** One viewing as the AI sees it: its id, start (epoch ms), kind and status (wire names) and notes; no `withWhom`. */
data class AiViewing(val id: String, val startsAt: Long, val kind: String, val status: String, val notes: String? = null)

/** One visit, in epoch milliseconds. */
data class AiVisit(val arrivedAt: Long, val leftAt: Long? = null)
