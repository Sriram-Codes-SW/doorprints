package com.househunt.app

import com.househunt.app.data.HouseEntity
import com.househunt.app.location.Geo
import com.househunt.app.location.StayDetector
import org.junit.Assert.*
import org.junit.Test

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
        assertTrue(started is StayDetector.Event.Started)
        assertTrue(d.isStaying)
        assertNull(d.onLocation(lat, lon, 6 * min))
        val ended = d.onLocation(lat + 0.002, lon, 7 * min) // ~220 m away
        assertTrue(ended is StayDetector.Event.Ended)
        ended as StayDetector.Event.Ended
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
    fun distanceIsAccurate() {
        val d = Geo.distanceM(lat, lon, lat + 0.001, lon)
        assertEquals(111.2, d, 0.5)
    }

    @Test
    fun scoreBlendsChecklistAndRating() {
        val h = HouseEntity(id = "1", label = "x", lat = 0.0, lon = 0.0, createdAt = 0, updatedAt = 0,
            rating = 4, checklist = mapOf("water" to 2, "parking" to 4))
        assertEquals(3.5, h.score!!, 0.001)
        assertNull(h.copy(rating = null, checklist = emptyMap()).score)
    }
}
