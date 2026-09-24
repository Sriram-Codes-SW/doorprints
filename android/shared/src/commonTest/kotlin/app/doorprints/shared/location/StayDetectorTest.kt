package app.doorprints.shared.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StayDetectorTest {

    private val min = 60_000L

    // ~0.00018 deg latitude ≈ 20 m
    private val lat = 12.9716
    private val lon = 77.5946

    @Test
    fun staysStartAfterMinimumTimeAndEndWhenYouLeave() {
        val d = StayDetector(radiusM = 40.0, minStayMs = 4 * min)
        assertNull(d.onLocation(lat, lon, 0))
        assertNull(d.onLocation(lat + 0.0001, lon, 2 * min))
        val started = d.onLocation(lat, lon + 0.0001, 4 * min)
        assertIs<StayDetector.Event.Started>(started)
        assertEquals(0L, started.since)
        assertTrue(d.isStaying)
        assertNull(d.onLocation(lat, lon, 6 * min))
        val ended = d.onLocation(lat + 0.002, lon, 7 * min) // ~220 m away
        assertIs<StayDetector.Event.Ended>(ended)
        assertEquals(0L, ended.arrivedAt)
        assertEquals(6 * min, ended.leftAt)
        assertFalse(d.isStaying)
    }

    @Test
    fun walkingPastDoesNotCountAsAStay() {
        val d = StayDetector(radiusM = 40.0, minStayMs = 4 * min)
        for (i in 0..20) {
            // Moving ~30 m every 30 s
            assertNull(d.onLocation(lat + i * 0.00027, lon, i * 30_000L))
        }
    }

    @Test
    fun minStayCanBeChangedWhileRunning() {
        val d = StayDetector(radiusM = 40.0, minStayMs = 10 * min)
        assertNull(d.onLocation(lat, lon, 0))
        d.minStayMs = 2 * min
        assertIs<StayDetector.Event.Started>(d.onLocation(lat, lon, 2 * min))
    }
}

class GeoTest {

    @Test
    fun distanceIsAccurate() {
        assertEquals(111.2, Geo.distanceM(12.9716, 77.5946, 12.9726, 77.5946), 0.5)
        // One degree of longitude at the equator ≈ 111.19 km.
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 0.0, 1.0), 1.0)
        assertEquals(0.0, Geo.distanceM(12.0, 77.0, 12.0, 77.0), 0.0)
    }

    @Test
    fun distanceIsSymmetric() {
        val a = Geo.distanceM(12.9716, 77.5946, 13.0827, 80.2707)
        val b = Geo.distanceM(13.0827, 80.2707, 12.9716, 77.5946)
        assertEquals(a, b, 1e-6)
        assertEquals(290_000.0, a, 10_000.0) // Bengaluru to Chennai, roughly 290 km as the crow flies
    }
}
