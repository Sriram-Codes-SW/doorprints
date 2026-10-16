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

import app.doorprints.drive.connect.SyncInfo
import app.doorprints.drive.connect.SyncState
import app.doorprints.drive.store.MemoryStateFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [FileCadenceStore] and the cadence over it, as the iPhone's foreground loop uses them. */
class FileCadenceStoreTest {
    private val file = MemoryStateFile("cadence")
    private val gate = DriveCadenceGate(FileCadenceStore(file))
    private val synced = SyncInfo(SyncState.SYNCED, null, changed = false)
    private val moved = SyncInfo(SyncState.SYNCED, null, changed = true)

    @Test
    fun noFileMeansLevelZeroAndAnyWakeRuns() {
        assertEquals(CadenceState(), FileCadenceStore(file).load())
        assertTrue(gate.due(1_000L))
    }

    @Test
    fun aPassThatMovedNothingBacksOffAndOneThatMovedSomethingDoesNot() {
        gate.record(synced, 10_000L)
        assertEquals(CadenceState(1, 10_000L), FileCadenceStore(file).load())
        assertFalse(gate.due(10_000L + 30 * 60_000L), "level 1 waits an hour, less the slack")
        assertTrue(gate.due(10_000L + 60 * 60_000L - DriveCadence.SLACK_MS))
        gate.record(moved, 20_000L)
        assertEquals(CadenceState(0, 20_000L), FileCadenceStore(file).load())
    }

    @Test
    fun theLevelStopsAtTheLongestInterval() {
        repeat(6) { gate.record(synced, 1_000L) }
        assertEquals(DriveCadence.INTERVALS_MS.size - 1, FileCadenceStore(file).load().level)
    }

    @Test
    fun returningToTheAppEndsTheBackOffButKeepsTheClock() {
        gate.record(synced, 5_000L)
        gate.record(synced, 6_000L)
        gate.reset()
        assertEquals(CadenceState(0, 6_000L), FileCadenceStore(file).load())
    }

    @Test
    fun aDamagedOrNegativeFileReadsAsLevelZero() {
        file.text = "{oops"
        assertEquals(CadenceState(), FileCadenceStore(file).load())
        file.text = "{\"level\":-4,\"lastPassAt\":7}"
        assertEquals(CadenceState(0, 7L), FileCadenceStore(file).load())
    }

    @Test
    fun aWriteThatFailsIsDropped() {
        file.failWrites = true
        gate.record(synced, 1_000L)
        assertEquals(CadenceState(), FileCadenceStore(file).load())
    }
}
