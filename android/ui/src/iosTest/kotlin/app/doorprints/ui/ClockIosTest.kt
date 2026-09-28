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
