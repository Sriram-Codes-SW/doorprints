package app.doorprints.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

// Great-circle distance (Geo.distanceM) moved to :shared (app.doorprints.shared.location.Geo) in Sprint 3.5, and the
// geocoder's result, Place, to :shared in CMP-4 P4c (the house form's rules in :ui fill it in); the platform geocoder
// stays here.

/**
 * Reverse geocoding with Android's built-in (free, no key) Geocoder. Returns null when unavailable/offline.
 *
 * Devices without Google Play services (many custom ROMs, some Huawei phones) have no geocoder backend:
 * [Geocoder.isPresent] is false and every lookup is null. On API 33+ a backend that never calls the listener would
 * otherwise suspend the caller forever, so both branches are time-limited ([LOOKUP_TIMEOUT_MS]). The new-house form no
 * longer waits for a lookup at all: it shows at once and fills the address in when one arrives.
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
                // The blocking call has no timeout of its own and cannot be interrupted, so it runs outside this
                // coroutine and only the wait for it is time-limited (UX review, whole-app audit): a phone offline
                // below API 33 no longer holds the caller for as long as the platform takes to give up. A late
                // answer is simply dropped.
                val pending = CoroutineScope(Dispatchers.IO).async {
                    @Suppress("DEPRECATION")
                    g.getFromLocation(lat, lon, 1)?.firstOrNull()
                }
                withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { pending.await() }
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

    companion object {
        /** How long a lookup may take before it counts as "no answer". */
        const val LOOKUP_TIMEOUT_MS = 10_000L
    }
}
