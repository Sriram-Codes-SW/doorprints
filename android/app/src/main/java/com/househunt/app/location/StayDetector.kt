package com.househunt.app.location

/**
 * Turns a stream of GPS fixes into "stays": staying within [radiusM] of where you stopped for at
 * least [minStayMs] counts as being at a place (e.g. inside a house you're viewing).
 */
class StayDetector(
    private val radiusM: Double = 40.0,
    var minStayMs: Long = 4 * 60_000L,
) {
    sealed interface Event {
        /** You've been here long enough — fired once, while you're still there. */
        data class Started(val lat: Double, val lon: Double, val since: Long) : Event

        /** You left a place you had stayed at. */
        data class Ended(val lat: Double, val lon: Double, val arrivedAt: Long, val leftAt: Long) : Event
    }

    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorTime = -1L
    private var lastInside = 0L
    private var sumLat = 0.0
    private var sumLon = 0.0
    private var n = 0
    private var started = false

    val isStaying get() = started

    fun onLocation(lat: Double, lon: Double, time: Long): Event? {
        if (anchorTime < 0) {
            reset(lat, lon, time)
            return null
        }
        if (Geo.distanceM(anchorLat, anchorLon, lat, lon) <= radiusM) {
            sumLat += lat; sumLon += lon; n++
            lastInside = time
            if (!started && time - anchorTime >= minStayMs) {
                started = true
                return Event.Started(sumLat / n, sumLon / n, anchorTime)
            }
            return null
        }
        val ended = if (started) Event.Ended(sumLat / n, sumLon / n, anchorTime, lastInside) else null
        reset(lat, lon, time)
        return ended
    }

    private fun reset(lat: Double, lon: Double, time: Long) {
        anchorLat = lat; anchorLon = lon; anchorTime = time; lastInside = time
        sumLat = lat; sumLon = lon; n = 1
        started = false
    }
}
