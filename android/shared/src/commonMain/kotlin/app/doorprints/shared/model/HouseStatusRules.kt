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

/** A house as the status rules see it: its id and its status. */
data class StatusHouse(val id: String, val status: HouseStatus)

/**
 * The end of a hunt (docs/11 5.24, slice 5), the same rules as the web's `house-status.ts` and the server's, pinned by
 * the vectors M1..M3 on each stack.
 */
object HouseStatusRules {
    /**
     * [status] chosen for house [id] (M1, M3): that house takes it, and when it is [HouseStatus.TAKEN] the house that was
     * TAKEN before returns to [HouseStatus.SHORTLISTED], so at most one house is ever TAKEN. Every other house is left
     * as it is; an unknown [id] changes nothing.
     */
    fun choose(houses: List<StatusHouse>, id: String, status: HouseStatus): List<StatusHouse> {
        if (houses.none { it.id == id }) return houses
        return houses.map { h ->
            when {
                h.id == id -> if (h.status == status) h else h.copy(status = status)
                status == HouseStatus.TAKEN && h.status == HouseStatus.TAKEN -> h.copy(status = HouseStatus.SHORTLISTED)
                else -> h
            }
        }
    }

    /**
     * The houses *Close this hunt* (and *Mark them Not chosen*) marks NOT_CHOSEN (M2): every house that is not the
     * TAKEN one [takenId] and not already REJECTED or NOT_CHOSEN, in [houses]' order. Nothing is deleted.
     */
    fun closeTargets(houses: List<StatusHouse>, takenId: String): List<String> =
        houses.filter { it.id != takenId && it.status != HouseStatus.REJECTED && it.status != HouseStatus.NOT_CHOSEN }
            .map { it.id }
}
