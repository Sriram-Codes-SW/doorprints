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

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import platform.posix.usleep

/**
 * S4b-BL-40: iOS's [elapsedRealtimeMillis] is `CLOCK_MONOTONIC` in milliseconds. It never goes back and it moves with
 * the time that passes. That it keeps counting while the device sleeps cannot be tested on a simulator, and its value
 * is not comparable with `NSProcessInfo.systemUptime`: each counts from its own starting point (on CI's macOS VM the
 * monotonic clock read 0.4 s behind the uptime).
 */
class ClockIosTest {
    @Test
    fun itNeverGoesBack() {
        var last = elapsedRealtimeMillis()
        repeat(10_000) {
            val now = elapsedRealtimeMillis()
            assertTrue(now >= last, "$now after $last")
            last = now
        }
    }

    @Test
    fun itCountsMilliseconds() {
        val mark = TimeSource.Monotonic.markNow()
        val start = elapsedRealtimeMillis()
        usleep(200_000u)
        val clock = elapsedRealtimeMillis() - start
        val passed = mark.elapsedNow().inWholeMilliseconds
        // Milliseconds, not seconds or microseconds: within a few ms of what passed.
        assertTrue(clock in 190..passed + 5, "clock $clock ms, passed $passed ms")
    }
}
