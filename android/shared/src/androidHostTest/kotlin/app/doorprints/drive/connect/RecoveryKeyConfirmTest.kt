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

package app.doorprints.drive.connect

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The key's last symbol can be *, ~, $, = or U (Crockford): confirming groups must keep it (a bug found by this loop). */
class RecoveryKeyConfirmTest {
    @Test
    fun everyKeyShapeConfirms() = runTest {
        repeat(120) { i ->
            val rig = ConnectRig()
            val c = rig.controller(this)
            c.connect(); testScheduler.advanceUntilIdle()
            c.createFirstFolder(); testScheduler.advanceUntilIdle()
            val step = c.state.value.recoveryKey!!
            val groups = step.display.split('-')
            c.confirmRecoveryKey(step.askGroups.map { groups[it - 1] }); testScheduler.advanceUntilIdle()
            assertEquals(DriveStage.READY, c.state.value.stage, "iteration $i key=${step.display} ask=${step.askGroups} msg=${c.state.value.message}")
        }
    }
}
