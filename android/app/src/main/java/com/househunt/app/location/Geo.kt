package com.househunt.app.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.*

object Geo {
    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}

data class Place(val street: String?, val locality: String?, val address: String?)

/** Reverse geocoding with Android's built-in (free, no key) Geocoder. Returns null when unavailable/offline. */
class ReverseGeocoder(context: Context) {
    private val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null

    suspend fun lookup(lat: Double, lon: Double): Place? {
        val g = geocoder ?: return null
        val address: Address? = try {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    g.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    g.getFromLocation(lat, lon, 1)?.firstOrNull()
                }
            }
        } catch (e: Exception) {
            null
        }
        return address?.let {
            Place(
                street = it.thoroughfare,
                locality = it.subLocality ?: it.locality,
                address = it.getAddressLine(0),
            )
        }
    }
}
