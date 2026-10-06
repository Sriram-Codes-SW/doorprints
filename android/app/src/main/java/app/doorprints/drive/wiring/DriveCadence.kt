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

package app.doorprints.drive.wiring

import android.content.Context
import app.doorprints.drive.connect.SyncInfo
import app.doorprints.drive.connect.SyncState

/** How far the periodic Drive sync has backed off, and when its last good pass ended. */
data class CadenceState(val level: Int = 0, val lastPassAt: Long = 0L)

/**
 * The periodic Drive sync backs off while nothing happens (battery and network, docs/15 §1.3): the 30-minute worker still
 * wakes, but a wake that is not yet due returns at once, before any Drive object is built and with no `files.list`. Pure
 * rules, apart from Android so JVM tests pin them.
 *
 * - The wait after a pass that wrote nothing and read nothing new: 30 minutes, then 1 hour, then 2 hours ([INTERVALS_MS]).
 * - A pass that wrote or read something, a local change, and the app coming to the front put it back to 30 minutes.
 * - A pass that did not finish (offline, waiting, paused, an error, a question for the person) changes nothing and does not
 *   move the clock, so the next wake tries again.
 * - The triggers of §1.3 (a change: two minutes later; app start; *Sync now*) never ask [due]; only the periodic wake does.
 */
object DriveCadence {
    private const val MINUTE_MS = 60_000L
    val INTERVALS_MS = longArrayOf(30 * MINUTE_MS, 60 * MINUTE_MS, 120 * MINUTE_MS)

    /** A periodic wake lands a little early now and then; this much early still counts as due. */
    const val SLACK_MS = 5 * MINUTE_MS

    /** Whether a periodic wake at [now] should run its pass. At the base level, or if the clock went backwards, always. */
    fun due(state: CadenceState, now: Long): Boolean {
        if (state.level <= 0) return true
        val elapsed = now - state.lastPassAt
        if (elapsed < 0) return true
        return elapsed >= INTERVALS_MS[state.level.coerceAtMost(INTERVALS_MS.size - 1)] - SLACK_MS
    }

    /** The state after a sync pass that gave [info] at [now]. */
    fun afterPass(state: CadenceState, info: SyncInfo, now: Long): CadenceState = when {
        info.state != SyncState.SYNCED -> state
        info.changed -> CadenceState(0, now)
        else -> CadenceState((state.level + 1).coerceAtMost(INTERVALS_MS.size - 1), now)
    }

    /** A local change or the app in the front: back to the base level (the clock stays). */
    fun reset(state: CadenceState): CadenceState = if (state.level == 0) state else state.copy(level = 0)
}

/** Where the cadence is kept between runs. */
interface CadenceStore {
    fun load(): CadenceState
    fun save(state: CadenceState)
}

/** The cadence over a [CadenceStore]; writes only what changed. */
class DriveCadenceGate(private val store: CadenceStore) {
    fun due(now: Long): Boolean = DriveCadence.due(store.load(), now)

    fun record(info: SyncInfo, now: Long) {
        val before = store.load()
        val after = DriveCadence.afterPass(before, info, now)
        if (after != before) store.save(after)
    }

    fun reset() {
        val before = store.load()
        val after = DriveCadence.reset(before)
        if (after != before) store.save(after)
    }

    companion object {
        fun of(context: Context) = DriveCadenceGate(PrefsCadenceStore(context.applicationContext))
    }
}

/** [CadenceStore] over a small private preferences file. */
class PrefsCadenceStore(context: Context) : CadenceStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    override fun load() = CadenceState(prefs.getInt(LEVEL, 0), prefs.getLong(LAST, 0L))
    override fun save(state: CadenceState) {
        prefs.edit().putInt(LEVEL, state.level).putLong(LAST, state.lastPassAt).apply()
    }

    private companion object {
        const val FILE = "drive-cadence"
        const val LEVEL = "level"
        const val LAST = "lastPassAt"
    }
}
