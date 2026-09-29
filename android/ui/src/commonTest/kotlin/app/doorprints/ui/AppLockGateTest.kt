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

package app.doorprints.ui

import app.doorprints.data.AppLockSetting
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app lock's rules (docs/11 5.19, S4b-FR-5): when [AppLockGate] covers the app. */
class AppLockGateTest {

    private fun gate(on: Boolean, afterSeconds: Int = 60) =
        AppLockGate().apply { settingRead(AppLockSetting(on, afterSeconds)) }

    @Test
    fun theAppIsCoveredUntilTheSettingIsRead() {
        val gate = AppLockGate()
        assertTrue(gate.covered)
        gate.settingRead(AppLockSetting(on = false, afterSeconds = 60))
        assertFalse(gate.covered)
    }

    @Test
    fun withTheLockOnAStartIsLockedUntilThePhoneCredentialPasses() {
        val gate = gate(on = true)
        assertTrue(gate.covered)
        assertTrue(gate.startCheck())
        gate.checkEnded(CredentialCheck.CANCELLED)
        assertTrue(gate.covered)
        assertTrue(gate.startCheck())
        gate.checkEnded(CredentialCheck.PASSED)
        assertFalse(gate.covered)
    }

    @Test
    fun onlyOnePromptAtATime() {
        val gate = gate(on = true)
        assertTrue(gate.startCheck())
        assertFalse(gate.startCheck())
        gate.checkEnded(CredentialCheck.CANCELLED)
        assertTrue(gate.startCheck())
    }

    @Test
    fun itLocksAgainOnlyAfterTheChosenTimeInTheBackground() {
        val gate = gate(on = true, afterSeconds = 60).apply { startCheck(); checkEnded(CredentialCheck.PASSED) }
        gate.left(now = 1_000)
        gate.returned(now = 60_999)
        assertFalse(gate.covered, "59.999 s away: still open")
        gate.left(now = 100_000)
        gate.returned(now = 160_000)
        assertTrue(gate.covered, "60 s away: locked")
    }

    @Test
    fun rightAwayLocksOnEveryReturn() {
        val gate = gate(on = true, afterSeconds = 0).apply { startCheck(); checkEnded(CredentialCheck.PASSED) }
        gate.left(now = 5_000)
        gate.returned(now = 5_001)
        assertTrue(gate.covered)
    }

    @Test
    fun thePromptsOwnScreenIsNotLeavingTheApp() {
        // API 26-28: the keyguard's confirm screen is an activity of its own, so the app stops while it is up.
        val gate = gate(on = true, afterSeconds = 0)
        gate.startCheck()
        gate.left(now = 1_000)
        gate.checkEnded(CredentialCheck.PASSED)
        gate.returned(now = 30_000)
        assertFalse(gate.covered)
    }

    @Test
    fun aReturnWithoutALeaveChangesNothing() {
        val gate = gate(on = true, afterSeconds = 0).apply { startCheck(); checkEnded(CredentialCheck.PASSED) }
        gate.returned(now = 10_000)
        assertFalse(gate.covered)
    }

    @Test
    fun turningTheLockOnWhileInUseKeepsTheAppOpenAndTurningItOffOpensIt() {
        val gate = gate(on = false)
        gate.settingRead(AppLockSetting(on = true, afterSeconds = 60))
        assertFalse(gate.covered, "the person just confirmed it is them in Settings")
        gate.left(now = 0)
        gate.returned(now = 120_000)
        assertTrue(gate.covered)
        gate.settingRead(AppLockSetting(on = false, afterSeconds = 60))
        assertFalse(gate.covered)
    }

    @Test
    fun withTheLockOffNothingLocks() {
        val gate = gate(on = false, afterSeconds = 0)
        gate.left(now = 0)
        gate.returned(now = 10_000_000)
        assertFalse(gate.covered)
    }
}
