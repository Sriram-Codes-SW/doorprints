package app.doorprints.shared.location

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure geometry. Platform geocoders (Android Geocoder, later CLGeocoder) stay in the apps. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Same constant as java.lang.Math.toRadians uses (PI / 180), so results match the old Android code. */
    private const val DEGREES_TO_RADIANS = PI / 180.0

    private fun rad(degrees: Double): Double = degrees * DEGREES_TO_RADIANS

    /** Great-circle (haversine) distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }
}
