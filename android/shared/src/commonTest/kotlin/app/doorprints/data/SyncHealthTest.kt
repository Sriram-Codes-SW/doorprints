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

package app.doorprints.data

import app.doorprints.shared.sync.SyncOutcome.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When the house list warns that background sync has stopped working (whole-app UX audit, 2026-09-22): three AUTH or
 * SERVER failures in a row, or a day without a sync that worked, while a server is configured. `kotlin.test` in
 * `:shared` commonTest since CMP-4 P4c (S4b-BL-28; was a JUnit test in `:app`).
 */
class SyncHealthTest {

    private val hour = 60 * 60 * 1000L
    private val now = 1_000L * hour

    @Test
    fun failuresAreCountedInARowAndResetByAHealthyOutcome() {
        assertEquals(1, SyncHealth.nextFailures(Kind.AUTH, 0))
        assertEquals(3, SyncHealth.nextFailures(Kind.NETWORK, 2))
        assertEquals(0, SyncHealth.nextFailures(Kind.OK, 5))
        assertEquals(0, SyncHealth.nextFailures(Kind.NOT_CONFIGURED, 5))
    }

    @Test
    fun theRunOfFailuresStartsAtTheFirstOne() {
        assertEquals(now, SyncHealth.nextFailingSince(Kind.SERVER, 0, 0L, now))
        assertEquals(now - hour, SyncHealth.nextFailingSince(Kind.SERVER, 2, now - hour, now))
        assertEquals(0L, SyncHealth.nextFailingSince(Kind.OK, 2, now - hour, now))
    }

    @Test
    fun threeAuthOrServerFailuresWarnAtOnce() {
        assertNull(SyncHealth.warningSince(true, Kind.AUTH, 2, now - hour, now - 2 * hour, now))
        assertEquals(now - 2 * hour, SyncHealth.warningSince(true, Kind.AUTH, 3, now - hour, now - 2 * hour, now))
        assertEquals(now - 2 * hour, SyncHealth.warningSince(true, Kind.SERVER, 4, now - hour, now - 2 * hour, now))
        // A flaky network needs the full day.
        assertNull(SyncHealth.warningSince(true, Kind.NETWORK, 10, now - hour, now - 2 * hour, now))
    }

    @Test
    fun aDayWithoutASuccessfulSyncWarnsWhateverTheFailure() {
        val lastOk = now - SyncHealth.STALE_AFTER_MS
        assertEquals(lastOk, SyncHealth.warningSince(true, Kind.NETWORK, 1, now - hour, lastOk, now))
        // Never worked: counted from the first failure.
        val firstFailure = now - SyncHealth.STALE_AFTER_MS - hour
        assertEquals(firstFailure, SyncHealth.warningSince(true, Kind.CAPTIVE_PORTAL, 2, firstFailure, 0L, now))
    }

    @Test
    fun noWarningWithoutAServerOrAfterASuccess() {
        val old = now - 3 * SyncHealth.STALE_AFTER_MS
        assertNull(SyncHealth.warningSince(false, Kind.AUTH, 5, old, old, now))
        assertNull(SyncHealth.warningSince(true, Kind.OK, 0, 0L, now, now))
        assertNull(SyncHealth.warningSince(true, null, 0, 0L, 0L, now))
    }
}
