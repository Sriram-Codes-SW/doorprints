package app.doorprints.shared.api

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Epoch milliseconds <-> the API's ISO-8601 instants, replacing java.time.Instant (JVM-only).
 *
 * Uses kotlin.time.Instant from the Kotlin standard library (stable since Kotlin 2.3), so no kotlinx-datetime
 * dependency is needed. Output format matches java.time.Instant.toString() for millisecond values: UTC with a "Z",
 * no fraction for whole seconds, otherwise 3 digits ("2026-09-22T10:15:30.120Z"). Parsing accepts everything the
 * backend sends (fractions of any length, "Z" or an offset); sub-millisecond digits are truncated like
 * Instant.toEpochMilli() did.
 */
object IsoTime {
    fun format(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString()

    /** @throws IllegalArgumentException when [iso] is not an ISO-8601 instant. */
    fun parseMillis(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

    fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
