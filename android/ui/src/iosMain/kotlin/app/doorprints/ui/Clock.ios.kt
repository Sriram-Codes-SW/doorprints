package app.doorprints.ui

import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime_nsec_np

// Darwin's CLOCK_MONOTONIC keeps counting while the device sleeps, like Android's elapsedRealtime (S4b-BL-40); the
// system uptime (NSProcessInfo.systemUptime, CLOCK_UPTIME_RAW) stops, so a screen come back to after a night's sleep
// would not refresh.
internal actual fun elapsedRealtimeMillis(): Long = (clock_gettime_nsec_np(CLOCK_MONOTONIC.toUInt()) / 1_000_000u).toLong()
