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

import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.connect.SyncInfo
import app.doorprints.drive.connect.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The periodic Drive sync's back-off (30 min, 1 h, 2 h), its resets, and that the triggers of docs/15 section 1.3 never wait. */
class DriveCadenceTest {
    private val min = 60_000L
    private val quiet = SyncInfo(SyncState.SYNCED, 1L)
    private val moved = SyncInfo(SyncState.SYNCED, 1L, changed = true)

    private class MemoryStore(var state: CadenceState = CadenceState()) : CadenceStore {
        var saves = 0
        override fun load() = state
        override fun save(state: CadenceState) {
            this.state = state
            saves++
        }
    }

    private fun after(state: CadenceState, info: SyncInfo, now: Long = 1_000 * min) = DriveCadence.afterPass(state, info, now)

    @Test
    fun theBaseLevelIsAlwaysDue() {
        assertTrue(DriveCadence.due(CadenceState(0, 500 * min), 500 * min))
        assertTrue(DriveCadence.due(CadenceState(0, 0), 0))
    }

    @Test
    fun quietPassesBackOffFromThirtyMinutesToAnHourToTwoHours() {
        var s = CadenceState()
        s = after(s, quiet)
        assertEquals(1, s.level)
        s = after(s, quiet)
        assertEquals(2, s.level)
        assertEquals("the ladder stops at two hours", 2, after(s, quiet).level)
        assertEquals(listOf(30L * min, 60L * min, 120L * min), DriveCadence.INTERVALS_MS.toList())
    }

    @Test
    fun aBackedOffWakeWaitsItsIntervalLessTheSlack() {
        val one = CadenceState(1, 100 * min)
        assertFalse(DriveCadence.due(one, 100 * min + 54 * min))
        assertTrue(DriveCadence.due(one, 100 * min + 55 * min))
        assertTrue(DriveCadence.due(one, 100 * min + 60 * min))
        val two = CadenceState(2, 100 * min)
        assertFalse(DriveCadence.due(two, 100 * min + 114 * min))
        assertTrue(DriveCadence.due(two, 100 * min + 115 * min))
    }

    @Test
    fun aClockThatWentBackwardsIsDueSoNothingStarves() {
        assertTrue(DriveCadence.due(CadenceState(2, 500 * min), 100 * min))
    }

    @Test
    fun aPassThatWroteOrTookSomethingResetsAndStampsTheClock() {
        assertEquals(CadenceState(0, 7 * min), after(CadenceState(2, 1), moved, 7 * min))
    }

    @Test
    fun aQuietPassStampsTheClock() {
        assertEquals(CadenceState(1, 7 * min), after(CadenceState(0, 1), quiet, 7 * min))
    }

    @Test
    fun aPassThatDidNotFinishChangesNothing() {
        val s = CadenceState(1, 42)
        for (state in SyncState.entries.filter { it != SyncState.SYNCED }) {
            assertEquals(state.name, s, after(s, SyncInfo(state, null, error = DriveReason.OFFLINE.takeIf { state == SyncState.ERROR }, changed = true)))
        }
    }

    @Test
    fun aLocalChangeOrTheAppInFrontResetsButKeepsTheClock() {
        assertEquals(CadenceState(0, 42), DriveCadence.reset(CadenceState(2, 42)))
        assertEquals(CadenceState(0, 42), DriveCadence.reset(CadenceState(0, 42)))
    }

    @Test
    fun afterAResetTheNextWakeIsDueAtOnce() {
        val s = DriveCadence.reset(CadenceState(2, 100 * min))
        assertTrue(DriveCadence.due(s, 100 * min + 1))
    }

    @Test
    fun theGateWritesOnlyWhatChanged() {
        val store = MemoryStore()
        val gate = DriveCadenceGate(store)
        gate.reset()
        assertEquals("a reset at the base level writes nothing", 0, store.saves)
        gate.record(quiet, 10 * min)
        assertEquals(CadenceState(1, 10 * min), store.state)
        assertFalse(gate.due(10 * min + 30 * min))
        assertTrue(gate.due(10 * min + 55 * min))
        gate.record(SyncInfo(SyncState.OFFLINE, null), 20 * min)
        assertEquals("an unfinished pass writes nothing", 1, store.saves)
        gate.reset()
        assertEquals(CadenceState(0, 10 * min), store.state)
        assertTrue(gate.due(10 * min + 1))
    }

    @Test
    fun aSavedLevelBeyondTheLadderIsReadAsTheLongestInterval() {
        assertFalse(DriveCadence.due(CadenceState(9, 100 * min), 100 * min + 60 * min))
        assertTrue(DriveCadence.due(CadenceState(9, 100 * min), 100 * min + 115 * min))
    }
}
