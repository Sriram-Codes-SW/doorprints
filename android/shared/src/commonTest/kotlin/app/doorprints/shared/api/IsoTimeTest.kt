package app.doorprints.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * IsoTime replaced java.time.Instant in the DTO mappers. The expected strings below are what
 * java.time.Instant.ofEpochMilli(x).toString() printed, so the JSON the app sends is unchanged.
 */
class IsoTimeTest {

    @Test
    fun formatsLikeJavaTimeInstant() {
        assertEquals("1970-01-01T00:00:00Z", IsoTime.format(0))
        assertEquals("2026-09-22T10:15:30Z", IsoTime.format(1_790_072_130_000))
        assertEquals("2026-09-22T10:15:30.120Z", IsoTime.format(1_790_072_130_120))
        assertEquals("2026-09-22T10:15:30.001Z", IsoTime.format(1_790_072_130_001))
        assertEquals("1969-12-31T23:59:59.999Z", IsoTime.format(-1))
    }

    @Test
    fun parsesWhatTheBackendSends() {
        // Postgres timestamptz has microseconds; Jackson writes them all. Sub-millisecond digits are truncated.
        assertEquals(1_790_072_130_123, IsoTime.parseMillis("2026-09-22T10:15:30.123456Z"))
        assertEquals(1_790_072_130_000, IsoTime.parseMillis("2026-09-22T10:15:30Z"))
        assertEquals(1_790_072_130_500, IsoTime.parseMillis("2026-09-22T10:15:30.5Z"))
        assertEquals(1_790_072_130_000, IsoTime.parseMillis("2026-09-22T15:45:30+05:30"))
    }

    @Test
    fun roundTrips() {
        for (millis in listOf(0L, 1L, 999L, 1_000L, 1_790_072_130_120L, 4_102_444_800_000L)) {
            assertEquals(millis, IsoTime.parseMillis(IsoTime.format(millis)))
        }
    }

    @Test
    fun rejectsJunk() {
        assertFailsWith<IllegalArgumentException> { IsoTime.parseMillis("yesterday") }
        assertFailsWith<IllegalArgumentException> { IsoTime.parseMillis("2026-09-22 10:15:30") }
    }
}
