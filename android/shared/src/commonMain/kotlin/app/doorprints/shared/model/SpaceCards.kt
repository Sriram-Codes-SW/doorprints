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

/** One card of the chooser: a kind, how many of its candidates are saved and when the newest changed (epoch ms; `null`: none). */
data class SpaceCard(val kind: String, val count: Int, val updated: Long?, val isDefault: Boolean)

/** The chooser's cards: one per kind that is on, then one per kind that is off but still has entries. */
data class SpaceCards(val shown: List<SpaceCard>, val hidden: List<SpaceCard>)

/** A candidate as the chooser tallies it: its kind (`null`: a house) and when it last changed (epoch ms). */
data class SpaceRow(val kind: String?, val updatedAt: Long)

/**
 * How many live candidates a kind holds and when the newest changed (epoch ms): the chooser's tally, one row per kind, read by
 * `HouseDao.observeKindTallies` so the chooser does not load every candidate (S4b-BL-303). A Room query projection, not a table.
 */
data class KindTally(val kind: String, val count: Int, val updated: Long)

/** How many candidates each space holds (a kind with none is absent): *What you track*'s *Saved: N*. */
fun spaceCounts(rows: List<SpaceRow>, default: String, available: List<String>): Map<String, Int> =
    rows.groupingBy { r -> (Candidates.kindOf(r.kind)).let { if (it in available) it else default } }.eachCount()

/** [spaceCounts] from the store's tallies (S4b-BL-303): the same numbers without reading every candidate. */
fun spaceCountsOf(tallies: List<KindTally>, default: String, available: List<String>): Map<String, Int> {
    val out = HashMap<String, Int>()
    for (t in tallies) {
        val space = if (t.kind in available) t.kind else default
        out[space] = (out[space] ?: 0) + t.count
    }
    return out
}

/**
 * Groups the live candidates by space for the chooser (kinds PR C, S4b-BL-274; the twin of the website's `spaceCards`): one
 * card per [enabled] kind (the [default] first, then the build's order), then one per [available] kind that is turned off
 * but still has entries (`hidden`), so a kind with data is never hidden silently and never deleted. A candidate of a kind
 * that is not [available] counts in the default space, as everywhere (`SpaceScope.holdsFor`).
 */
fun spaceCards(rows: List<SpaceRow>, enabled: List<String>, default: String, available: List<String>): SpaceCards =
    spaceCardsOf(
        rows.groupBy { Candidates.kindOf(it.kind) }.map { (kind, list) -> KindTally(kind, list.size, list.maxOf { it.updatedAt }) },
        enabled, default, available,
    )

/**
 * The same cards from the store's tallies, one per kind (S4b-BL-303): the chooser reads a count and a newest time per kind
 * instead of every candidate. A kind that is not [available] counts in the [default] space; the numbers are those of
 * [spaceCards] over the rows the tallies sum.
 */
fun spaceCardsOf(tallies: List<KindTally>, enabled: List<String>, default: String, available: List<String>): SpaceCards {
    val count = HashMap<String, Int>()
    val updated = HashMap<String, Long>()
    for (t in tallies) {
        val space = if (t.kind in available) t.kind else default
        count[space] = (count[space] ?: 0) + t.count
        if (t.updated > (updated[space] ?: Long.MIN_VALUE)) updated[space] = t.updated
    }
    fun card(kind: String) = SpaceCard(kind, count[kind] ?: 0, updated[kind], kind == default)
    val order = listOf(default) + enabled.filter { it != default }
    return SpaceCards(
        shown = order.map(::card),
        hidden = available.filter { it !in enabled && (count[it] ?: 0) > 0 }.map(::card),
    )
}
