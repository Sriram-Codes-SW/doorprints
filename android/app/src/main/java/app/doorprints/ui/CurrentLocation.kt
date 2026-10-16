/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import app.doorprints.location.HuntState
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * A current location fix, or null. The phone's last known location stands in only when it is under two minutes old
 * and accurate to [HuntState.MAX_ACCURACY_M] ([lastFixUsable]; UX review, whole-app audit): a fix of any age put a
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

/**
 * Play services' fresh high-accuracy fix, else a recent accurate last known one, else null; null too without the
 * location permission.
 */
@SuppressLint("MissingPermission")
private suspend fun lookUpLocation(context: Context): Pair<Double, Double>? {
    if (!hasLocationPermission(context)) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val fresh = runCatching { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }.getOrNull()
    if (fresh != null) return fresh.latitude to fresh.longitude
    val last = runCatching { client.lastLocation.await() }.getOrNull() ?: return null
    val ageMs = (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000L
    val accuracy = if (last.hasAccuracy()) last.accuracy else null
    return if (lastFixUsable(ageMs, accuracy, HuntState.MAX_ACCURACY_M)) last.latitude to last.longitude else null
}

/**
 * The best fix over up to [maxWaitMs] for *Have I been here?* (docs/11 5.27.13): Play services' updates every second,
 * `bestOf` the stream; the first reading of [goodAccuracyM] or better ends it, and the updates are always removed. Never
 * the last known location. Returns on the main thread.
 */
@SuppressLint("MissingPermission")
suspend fun bestLocation(context: Context, maxWaitMs: Long, goodAccuracyM: Double): PlaceFix? =
    withContext(Dispatchers.Main.immediate) {
        if (!hasLocationPermission(context)) return@withContext null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val fixes = callbackFlow {
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val l = result.lastLocation ?: return
                    if (l.hasAccuracy()) trySend(PlaceFix(l.latitude, l.longitude, l.accuracy.toDouble()))
                }
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).setMinUpdateIntervalMillis(500L).build()
            runCatching { client.requestLocationUpdates(request, callback, Looper.getMainLooper()) }.onFailure { close() }
            awaitClose { client.removeLocationUpdates(callback) }
        }
        bestOf(fixes, maxWaitMs, goodAccuracyM)
    }
