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
 * The space on screen (kinds PR C, S4b-BL-274; docs/03 ADR-36 §18.12): the [kind] whose candidates the list, the map,
 * Compare, Ask, Plan and the copies read, the [default] kind and the [available] kinds the person has on. A space is a
 * filter over the one candidate store, never a table. With one kind on there is one space and it holds everything
 * ([SpaceView.ONE]); with two or more, a candidate is in its own kind's space, or in the default space when its kind has
 * none here. The twin of the website's `core/space-scope.ts`.
 */
data class SpaceView(val kind: String, val multi: Boolean, val default: String, val available: List<String>) {
    /** Whether this space holds a candidate of [candidateKind] (`null`: a house). */
    fun holds(candidateKind: String?): Boolean = SpaceScope.holdsFor(kind, multi, default, available)(candidateKind)

    companion object {
        /** The house alone: the one space of the app as it always was, which holds every candidate. */
        val ONE = SpaceView(Candidates.BASELINE_KIND, multi = false, default = Candidates.BASELINE_KIND, available = listOf(Candidates.BASELINE_KIND))
    }
}

/**
 * How the store is asked for one space's candidates (S4b-BL-303), the same set [SpaceView.holds] chooses in memory: the rows of
 * these [kinds] ([except] false), or the rows of every kind but these ([except] true: the default space also holds a kind this
 * build does not have). [none] holds nothing (a space that is neither an available kind nor the default).
 */
data class SpaceQuery(val kinds: List<String>, val except: Boolean) {
    val none: Boolean get() = !except && kinds.isEmpty()
}

/** The store query that selects exactly the candidates [SpaceView.holds] (`SpaceQueryTest` holds the two together). */
fun SpaceView.query(): SpaceQuery = when {
    !multi -> SpaceQuery(emptyList(), except = true)
    kind == default -> SpaceQuery(available.filter { it != kind }, except = true)
    kind in available -> SpaceQuery(listOf(kind), except = false)
    else -> SpaceQuery(emptyList(), except = false)
}

/** A candidate as Compare's rules read it: its id, kind (`null`: a house), status by name and whether it is deleted. */
data class ScopedCandidate(
    val id: String,
    val kind: String? = null,
    val status: String? = null,
    val deleted: Boolean = false,
    /** When the row last changed (epoch ms): a tombstone's time of deletion, for [DeleteScope.binRows]. */
    val updatedAt: Long = 0,
)

/** What Compare keeps of a pick in a space, and what it refuses (a live candidate of another space). */
data class ComparePick(val kept: List<String>, val refused: List<String>)

/**
 * The rules that scope the tools to one space (kinds PR C, S4b-BL-289), held to `docs/schemas/kinds/space-scope-vectors.json`
 * by `SpaceScopeVectorsTest`, the same file the website's `space-scope.spec.ts` reads. With one kind on, every rule is
 * today's.
 */
object SpaceScope {
    /** The most candidates Compare puts side by side. */
    const val COMPARE_MAX = 4

    /**
     * Whether a space holds a candidate: with one space ([multi] false) every candidate; with two or more, a candidate of a
     * kind that has a space here (is [available]) is in its own, and any other kind's (an unavailable kind, a newer app's)
     * is in the [default] space.
     */
    fun holdsFor(space: String, multi: Boolean, default: String, available: List<String>): (String?) -> Boolean = { candidateKind ->
        if (!multi) {
            true
        } else {
            val kind = Candidates.kindOf(candidateKind)
            (if (kind in available) kind else default) == space
        }
    }

    /**
     * Compare's pick in a space: the picked ids that are live candidates in the running that [holds] says belong to the
     * space, in order, without duplicates, at most [COMPARE_MAX]; a live candidate of another space is refused (the screen
     * says so in a sentence); an id that is no candidate, is deleted or is out of the running is dropped without a word.
     */
    fun comparePick(picked: List<String>, candidates: List<ScopedCandidate>, holds: (String?) -> Boolean): ComparePick {
        val live = candidates.filter { !it.deleted }.associateBy { it.id }
        val kept = ArrayList<String>()
        val refused = ArrayList<String>()
        for (raw in picked) {
            val id = raw.trim()
            val c = live[id]
            if (c == null || id in kept || id in refused) continue
            if (!holds(c.kind)) refused += id else if (HouseStatusRules.inTheRunning(c.status) && kept.size < COMPARE_MAX) kept += id
        }
        return ComparePick(kept, refused)
    }

    /** A kind's plural noun as a file-name word: the English words, lower case, letters and digits, joined by hyphens. */
    fun fileSlug(many: String): String = many.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /**
     * The file name of a space's copy: the plural noun goes before the first date (`Doorprints-2026-09-22-csv.zip` becomes
     * `Doorprints-schools-2026-09-22-csv.zip`). The house's names, a name with no date, a noun with no letters or digits and
     * every backup (a null [space]) are left as they are, byte for byte.
     */
    fun spaceFileName(name: String, space: String?, many: String): String {
        val slug = fileSlug(many)
        if (space == null || KindCapabilities.of(space).baseline || slug.isEmpty()) return name
        return DATE.find(name)?.let { name.replaceRange(it.range, "$slug-${it.value}") } ?: name
    }

    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    /**
     * Whether Ask and Plan are shown in a space: the house whenever the assistant is [on]; any other kind only when the
     * build offers it ([available]) and the assistant reads it: the person's own ([ownKey]) or the server's, whose status named
     * the kind ([serverKinds], S4b-BL-290; an older server names none, and its Ask and Plan take no kind and would read every
     * space).
     */
    fun aiShownIn(kind: String, available: List<String>, on: Boolean, ownKey: Boolean, serverKinds: List<String> = emptyList()): Boolean =
        on && (KindCapabilities.of(kind).baseline || (kind in available && (ownKey || kind in serverKinds)))

    /** The kind a shared listing lands in: the [chosen] one when it is [enabled], else the [default] kind. */
    fun shareKind(chosen: String?, enabled: List<String>, default: String): String =
        if (chosen != null && chosen in enabled) chosen else default

    /**
     * The kind a new entry is made as, from the [space] it is made in (docs/03 §18.18): the space's own while that kind is
     * [enabled], else the [default] kind, which is always enabled. A kind that is off keeps its entries and takes no new one.
     */
    fun createKind(space: String, enabled: List<String>, default: String): String =
        if (space in enabled) space else default
}
