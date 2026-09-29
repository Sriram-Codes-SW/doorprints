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

/** Ask's optional filters (the server's `AskModels.Filters`); null means no filter. */
data class AskFilters(
    val status: String? = null,
    val priceType: String? = null,
    val maxPrice: Long? = null,
    val minBedrooms: Int? = null,
    val minRating: Int? = null,
)

/**
 * Which houses on-device AI sends (docs/03 §13.1, ADR-26). The server searches its index by meaning; on the device
 * there is no index, so Ask applies the filters and then sends every house when there are at most [MAX_HOUSES], else
 * the [MAX_HOUSES] that share the most words with the question (ties: the most recently changed first, the order the
 * caller gives). Plan gets at most [MAX_HOUSES] candidates, nearest to the start point first.
 */
object OnDeviceSelection {
    const val MAX_HOUSES = 40

    fun forAsk(houses: List<AiHouse>, question: String, filters: AskFilters? = null): List<AskDocument> {
        val kept = houses.filter { matches(it, filters) }
        val chosen = if (kept.size <= MAX_HOUSES) kept else {
            val q = AskChecks.words(question)
            kept.withIndex()
                .sortedWith(compareByDescending<IndexedValue<AiHouse>> { (_, h) -> AskChecks.words(HouseDocuments.text(h)).count { it in q } }
                    .thenBy { it.index })
                .take(MAX_HOUSES)
                .map { it.value }
        }
        return chosen.map { AskDocument(it.id, HouseDocuments.text(it), HouseDocuments.label(it)) }
    }

    private fun matches(h: AiHouse, f: AskFilters?): Boolean {
        if (f == null) return true
        if (f.status != null && h.status != f.status) return false
        if (f.priceType != null && h.priceType != f.priceType) return false
        if (f.maxPrice != null && (h.price == null || h.price > f.maxPrice)) return false
        if (f.minBedrooms != null && (h.bedrooms == null || h.bedrooms < f.minBedrooms)) return false
        if (f.minRating != null && (h.rating == null || h.rating < f.minRating)) return false
        return true
    }

    /** Plan's candidates: every house (Rejected included, the prompt says to skip them unless asked), nearest first. */
    fun forPlan(houses: List<AiHouse>, startLat: Double, startLon: Double): List<PlanCandidate> =
        houses.map { h ->
            val r = ContactRedactor.forContact(h.contactName, h.contactPhone)
            PlanCandidate(
                id = h.id,
                label = r.freeText(h.label) ?: "",
                locality = r.place(h.locality),
                street = r.place(h.street),
                status = h.status,
                price = h.price,
                priceType = h.priceType,
                bedrooms = h.bedrooms,
                rating = h.rating,
                lat = h.lat,
                lon = h.lon,
                distanceMeters = RouteOptimizer.roundHalfUp(RouteOptimizer.haversineMeters(startLat, startLon, h.lat, h.lon)),
            )
        }.withIndex().sortedWith(compareBy<IndexedValue<PlanCandidate>> { it.value.distanceMeters }.thenBy { it.index })
            .take(MAX_HOUSES).map { it.value }

    /** One line per candidate for the Plan prompt: `id | label | locality | status | price | bedrooms | rating | metres`. */
    fun candidateLines(candidates: List<PlanCandidate>): String = candidates.joinToString("\n") { c ->
        listOf(
            "id: ${c.id}",
            "label: ${c.label}",
            "locality: ${c.locality ?: c.street ?: "-"}",
            "status: ${c.status ?: "-"}",
            "price: ${c.price?.let { p -> "Rs $p" + if (c.priceType == "RENT") " per month" else if (c.priceType == "SALE") " (sale)" else "" } ?: "-"}",
            "bedrooms: ${c.bedrooms ?: "-"}",
            "rating: ${c.rating?.let { "$it/5" } ?: "-"}",
            "distance: ${c.distanceMeters} m",
        ).joinToString(" | ")
    }
}
