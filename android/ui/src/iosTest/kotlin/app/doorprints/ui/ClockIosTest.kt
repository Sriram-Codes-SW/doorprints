package app.doorprints.ui

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import platform.Foundation.NSProcessInfo
import platform.posix.usleep

/**
 * S4b-BL-40: iOS's [elapsedRealtimeMillis] is `CLOCK_MONOTONIC` in milliseconds. It never goes back, it moves with
 * the time that passes, and it is never behind the system uptime, which stops while the device sleeps.
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

    @Test
    fun itIsNeverBehindTheUptime() {
        val uptime = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
        val clock = elapsedRealtimeMillis()
        assertTrue(clock >= uptime - 1, "clock $clock ms, uptime $uptime ms")
    }
}
