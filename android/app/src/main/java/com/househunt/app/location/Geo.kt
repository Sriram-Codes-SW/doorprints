package com.househunt.app.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

// Great-circle distance (Geo.distanceM) moved to :shared (com.househunt.shared.location.Geo) in Sprint 3.5;
// the platform geocoder stays here.

data class Place(val street: String?, val locality: String?, val address: String?)

/**
 * Reverse geocoding with Android's built-in (free, no key) Geocoder. Returns null when unavailable/offline.
 *
 * Devices without Google Play services (many custom ROMs, some Huawei phones) have no geocoder backend:
 * [Geocoder.isPresent] is false and every lookup is null. On API 33+ a backend that never calls the listener would
 * otherwise suspend the caller forever (the new-house screen waits for the first lookup), so it is time-limited.
 */
class ReverseGeocoder(context: Context) {
    private val geocoder = runCatching {
        if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null
    }.getOrNull()

    suspend fun lookup(lat: Double, lon: Double): Place? {
        val g = geocoder ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null // getFromLocation throws on these
        val address: Address? = try {
            if (Build.VERSION.SDK_INT >= 33) {
                withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Address?> { cont ->
                        g.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (cont.isActive) cont.resume(addresses.firstOrNull())
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        })
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    g.getFromLocation(lat, lon, 1)?.firstOrNull()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return address?.let {
            Place(
                street = it.thoroughfare,
                locality = it.subLocality ?: it.locality,
                address = if (it.maxAddressLineIndex >= 0) it.getAddressLine(0) else null,
            )
        }
    }

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 10_000L
    }
}
