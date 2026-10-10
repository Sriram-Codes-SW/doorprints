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

/** One stop the model chose: a house id and its reason, both unchecked. */
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
 * they name a candidate, once each, up to [maxStops]; without a usable plan the candidates in the running within
 * [FALLBACK_MAX_METERS] of the start, nearest first, at most [maxStops], are ordered by nearest neighbour instead, with
 * the same words as the server (S4b-BL-199; the shared vectors' `planFallback`). Legs are always recomputed here.
 */
object PlanChecks {
    const val FALLBACK_REASON = "Found by the search; ordered by walking distance"
    const val FALLBACK_SUMMARY =
        "The assistant could not finish a plan, so these are the houses it found, ordered by nearest neighbour from your start point."

    /** How far from the start point a house may be for the fallback route to offer it, as the server's `FALLBACK_MAX_METERS`: a house hunt is one city. */
    const val FALLBACK_MAX_METERS = 50_000.0

    /** The summary of a fallback when no candidate is within [FALLBACK_MAX_METERS] of the start. */
    const val FALLBACK_NONE_IN_REACH = "No saved houses within reach of your start point were found."
    private val UUID_TEXT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * The fallback's candidates, as the server's `fallbackPoints`: the houses in the running with usable coordinates
     * within [FALLBACK_MAX_METERS] of the start, nearest to the start first (equal distances by house id), at most
     * [maxStops]. The order of [seen] does not matter.
     */
    private fun fallbackPoints(seen: Map<String, PlanCandidate>, startLat: Double, startLon: Double, maxStops: Int): List<RouteOptimizer.Point> {
        val near = ArrayList<Pair<PlanCandidate, Double>>()
        for (h in seen.values) {
            if (!HouseStatusRules.inTheRunning(h.status)) continue
            val meters: Double = RouteOptimizer.haversineMeters(startLat, startLon, h.lat, h.lon)
            // A NaN distance (an unusable coordinate) is not within reach.
            if (meters <= FALLBACK_MAX_METERS) near += h to meters
        }
        return near.sortedWith(compareBy<Pair<PlanCandidate, Double>> { it.second }.thenBy { it.first.id.lowercase() })
            .take(maxStops).map { RouteOptimizer.Point(it.first.id, it.first.lat, it.first.lon) }
    }

    /**
      * The plan to show: [plan]'s stops that name a known candidate (each once, up to [maxStops]) with the legs
      * recomputed
     * from the start point, or the fallback order when [plan] is missing or none of its stops was usable.
     */
    fun assemble(
        plan: AgentPlan?,
        seen: Map<String, PlanCandidate>,
        calls: List<String>,
        startLat: Double,
        startLon: Double,
        maxStops: Int,
    ): PlanResponseDto {
        val byId = seen.mapKeys { it.key.lowercase() }
        // What the model was given about the houses (labels, localities, streets), for AnswerText.clean.
        val context = seen.values.joinToString("\n") { "${it.label}\n${it.locality.orEmpty()}\n${it.street.orEmpty()}" }
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
            reasons += AnswerText.clean(stop.reason?.trim() ?: "", context)
        }
        var fallback = false
        var summary = plan?.summary?.trim()?.let { AnswerText.clean(it, context) }
        val legs: List<RouteOptimizer.Leg>
        if (plan == null || (chosen.isEmpty() && seen.isNotEmpty() && !plan.stops.isNullOrEmpty())) {
            fallback = true
            chosen.clear()
            reasons.clear()
            val points = fallbackPoints(seen, startLat, startLon, maxStops)
            legs = RouteOptimizer.nearestNeighbour(startLat, startLon, points)
            legs.forEach { l ->
                chosen += seen.getValue(l.to.id)
                reasons += FALLBACK_REASON
            }
            summary = if (points.isEmpty()) FALLBACK_NONE_IN_REACH else FALLBACK_SUMMARY
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
