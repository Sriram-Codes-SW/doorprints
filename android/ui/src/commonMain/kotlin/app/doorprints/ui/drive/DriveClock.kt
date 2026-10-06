package app.doorprints.ui.drive

import kotlin.time.TimeSource

private val origin = TimeSource.Monotonic.markNow()

/**
 * Milliseconds on a monotonic clock (it never jumps when the wall clock is set or the time zone changes, so a delete's
 * countdown cannot be shortened by changing the phone's time). Only differences mean anything: it is not a date.
 */
fun monotonicMillis(): Long = origin.elapsedNow().inWholeMilliseconds
