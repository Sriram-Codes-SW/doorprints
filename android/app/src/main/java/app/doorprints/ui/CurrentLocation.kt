package app.doorprints.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import app.doorprints.location.HuntService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * A current location fix, or null. The phone's last known location stands in only when it is under two minutes old
 * and accurate to [HuntService.MAX_ACCURACY_M] ([lastFixUsable]; UX review, whole-app audit): a fix of any age put a
 * house in the wrong place for good. Null makes the caller say it is waiting for GPS. The Map, the house form and the
 * Assistant read it through `AppServices.location` (was in `:app`'s `MapScreen.kt` until the Map moved to `:ui`,
 * CMP-7).
 *
 * Returns on the main thread: Play services completes its tasks on a Binder thread, and callers move the MapLibre
 * camera next, which throws off the main thread (found by the emulator smoke test, docs/06 TC-I-35, under the test's
 * coroutine interceptor, which does not switch back the way the app's main dispatcher does).
 * Callers may touch main-thread-only APIs (MapLibre, snapshot state) right after this returns.
 */
suspend fun currentLocation(context: Context): Pair<Double, Double>? =
    withContext(Dispatchers.Main.immediate) { lookUpLocation(context) }

@SuppressLint("MissingPermission")
private suspend fun lookUpLocation(context: Context): Pair<Double, Double>? {
    if (!hasLocationPermission(context)) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val fresh = runCatching { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }.getOrNull()
    if (fresh != null) return fresh.latitude to fresh.longitude
    val last = runCatching { client.lastLocation.await() }.getOrNull() ?: return null
    val ageMs = (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000L
    val accuracy = if (last.hasAccuracy()) last.accuracy else null
    return if (lastFixUsable(ageMs, accuracy, HuntService.MAX_ACCURACY_M)) last.latitude to last.longitude else null
}
