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

import app.doorprints.shared.api.PlanResponseDto
import app.doorprints.shared.api.PlannedStopDto
import app.doorprints.shared.model.HouseStatusRules
import kotlinx.serialization.Serializable

/** What the model returns for Plan (the server's `PlanModels.AgentPlan`). */
@Serializable
data class AgentPlan(val summary: String? = null, val stops: List<AgentStop>? = null)

@Serializable
data class AgentStop(val houseId: String? = null, val reason: String? = null)

/** A house Plan may choose: the server's `HouseSummary` (redacted label, no notes, no contact). */
data class PlanCandidate(
    val id: String,
    val label: String,
    val locality: String?,
    val street: String?,
    val status: String?,
    val price: Long?,
    val priceType: String?,
    val bedrooms: Int?,
    val rating: Int?,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Long,
)

/**
 * The checks on a plan: the server's `VisitPlannerService.assemble`, ported (docs/03 §13.1). Stops are kept only if
 * they name a candidate, once each, up to [maxStops]; without a usable plan the non-rejected candidates are ordered by
 * nearest neighbour instead, with the same words as the server. Legs are always recomputed here.
 */
object PlanChecks {
    const val FALLBACK_REASON = "Found by the search; ordered by walking distance"
    const val FALLBACK_SUMMARY =
        "The assistant could not finish a plan, so these are the houses it found, ordered by nearest neighbour from your start point."
    private val UUID_TEXT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun assemble(
        plan: AgentPlan?,
        seen: Map<String, PlanCandidate>,
        calls: List<String>,
        startLat: Double,
        startLon: Double,
        maxStops: Int,
    ): PlanResponseDto {
        val byId = seen.mapKeys { it.key.lowercase() }
        val chosen = mutableListOf<PlanCandidate>()
        val reasons = mutableListOf<String>()
        val used = HashSet<String>()
        plan?.stops?.forEach { stop ->
            if (chosen.size == maxStops) return@forEach
            val id = stop.houseId?.trim()?.lowercase() ?: return@forEach
            if (!UUID_TEXT.matches(id)) return@forEach
            val h = byId[id] ?: return@forEach
            if (!used.add(id)) return@forEach
            chosen += h
            reasons += stop.reason?.trim() ?: ""
        }
        var fallback = false
        var summary = plan?.summary?.trim()
        val legs: List<RouteOptimizer.Leg>
        if (plan == null || (chosen.isEmpty() && seen.isNotEmpty() && !plan.stops.isNullOrEmpty())) {
            fallback = true
            chosen.clear()
            reasons.clear()
            val points = seen.values.filter { HouseStatusRules.inTheRunning(it.status) }.take(maxStops)
                .map { RouteOptimizer.Point(it.id, it.lat, it.lon) }
            legs = RouteOptimizer.nearestNeighbour(startLat, startLon, points)
            legs.forEach { l ->
                chosen += seen.getValue(l.to.id)
                reasons += FALLBACK_REASON
            }
            summary = FALLBACK_SUMMARY
        } else {
            legs = RouteOptimizer.legsInOrder(startLat, startLon, chosen.map { RouteOptimizer.Point(it.id, it.lat, it.lon) })
        }
        val stops = legs.mapIndexed { i, leg ->
            val h = chosen[i]
            PlannedStopDto(i + 1, h.id, h.label, h.lat, h.lon, reasons[i], RouteOptimizer.roundHalfUp(leg.meters), leg.walkMinutes)
        }
        if (summary.isNullOrBlank()) {
            summary = if (stops.isEmpty()) "No saved houses matched the request." else "Visit plan with ${stops.size} stops."
        }
        return PlanResponseDto(summary, stops, stops.sumOf { it.legMeters }, stops.sumOf { it.walkMinutes }, calls, fallback)
    }
}
