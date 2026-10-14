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

import kotlin.math.max

/**
 * The rules of the deletion safeguards on the phones (deletion safeguards PR 3, S4b-BL-284; docs/02 T-D, docs/03 §18.12), the
 * twin of the website's `house-bin.ts` and `bulk-guard.service.ts`, held to `docs/schemas/delete-scope-vectors.json` by
 * `DeleteScopeVectorsTest`. A delete never reaches a candidate of another space: *Delete all in this space* takes the ids
 * [liveIdsOf] names and no others, and *Recently deleted* lists [binRows] of one space.
 */
object DeleteScope {
    /** How long a deleted candidate stays in *Recently deleted*, in days: the 30 days photo bytes are kept in Drive (docs/15 1.4). */
    const val BIN_DAYS = 30

    /** A space with more candidates than this wants a tick box before *Delete all in this space*. */
    const val TICK_OVER = 10

    /** The pause between two bulk actions on one device, in milliseconds. */
    const val BULK_MS = 10_000L

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** The stamp of a row written now: never at or before the one it replaces, so a phone whose clock is behind still moves it on. */
    fun nextStamp(previous: Long, now: Long): Long = max(now, previous + 1)

    /** The ids of the live candidates that [holds] says belong to the space, in order: what *Delete all in this space* takes. */
    fun liveIdsOf(rows: List<ScopedCandidate>, holds: (String?) -> Boolean): List<String> =
        rows.filter { !it.deleted && holds(it.kind) }.map { it.id }

    /** True when the row is a tombstone younger than 30 days (`deleted && updatedAt > now - 30 days`). */
    fun inBin(row: ScopedCandidate, now: Long): Boolean = row.deleted && row.updatedAt > now - BIN_DAYS * DAY_MS

    /** This space's tombstones younger than 30 days, newest first, ties by id. With one space every tombstone is this space's. */
    fun binRows(rows: List<ScopedCandidate>, holds: (String?) -> Boolean, now: Long): List<ScopedCandidate> =
        rows.filter { inBin(it, now) && holds(it.kind) }.sortedWith(compareByDescending<ScopedCandidate> { it.updatedAt }.thenBy { it.id })
}

/**
 * One bulk action per 10 seconds per device (deletion safeguards PR 3): *Delete all in this space* asks [tryStart] first, so a
 * slip or a double tap cannot empty one space after another in a blink; the person has the time of the Undo to see what
 * happened. Kept in memory: the pause is the length of one Undo, and a restart of the app is longer than that.
 */
class BulkGuard(private val clock: () -> Long) {
    private var last = Long.MIN_VALUE

    /** True and starts the pause when no bulk action ran in the last 10 seconds; false otherwise (nothing is started). */
    fun tryStart(): Boolean {
        val now = clock()
        if (waitSeconds(now) > 0) return false
        last = now
        return true
    }

    /** Forgets the pause (a test starts from none). */
    fun reset() {
        last = Long.MIN_VALUE
    }

    /** Whole seconds left of the pause, 0 when a bulk action may start. */
    fun waitSeconds(now: Long = clock()): Int {
        if (last == Long.MIN_VALUE) return 0
        val left = last + DeleteScope.BULK_MS - now
        return if (left > 0 && left <= DeleteScope.BULK_MS) ((left + 999) / 1000).toInt() else 0
    }
}
