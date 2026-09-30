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

package app.doorprints

import android.Manifest
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.doorprints.data.Repository
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaWakeup
import app.doorprints.ui.AreaWakeupServices
import app.doorprints.ui.canAskAgain
import app.doorprints.ui.findActivity
import app.doorprints.ui.openAppSettings
import app.doorprints.ui.rememberLocationPermissionRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Where the area wake-up's geofences go (docs/11 "Design of slice 4b"): Google Play services' Geofencing API on a
 * phone ([PlayGeofenceRegistrar]), a fake in the tests. Both calls may fail quietly (no Play services, the permission
 * gone, location switched off): the next trigger registers again.
 */
interface GeofenceRegistrar {
    /** Removes every geofence of this app's wake-up. */
    suspend fun removeAll()

    /** Adds one geofence per area (radius `radiusM`, entering only, no expiry), request id the area's id. */
    suspend fun add(areas: List<Area>)
}

/**
 * [GeofenceRegistrar] with `LocationServices.getGeofencingClient`: one geofence per area, `GEOFENCE_TRANSITION_ENTER`
 * only, `NEVER_EXPIRE`, the default responsiveness, and an initial trigger of 0, so being inside an area when it is
 * registered (at every resume, boot or edit) notifies nothing: only arriving does (5.17 *On entering*). Every geofence
 * points at [pendingIntent] to the non-exported [AreaGeofenceReceiver].
 */
class PlayGeofenceRegistrar(private val context: Context) : GeofenceRegistrar {
    private val client by lazy { LocationServices.getGeofencingClient(context) }

    override suspend fun removeAll() {
        runCatching { client.removeGeofences(pendingIntent(context)).await() }
    }

    override suspend fun add(areas: List<Area>) {
        if (areas.isEmpty() || !hasAreaWakeupPermissions(context)) return
        // Checked just above, and again by Play services: a permission taken away in between fails the task, not the app.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofences(areas.map(::geofence))
            .build()
        runCatching { client.addGeofences(request, pendingIntent(context)).await() }
    }

    companion object {
        /** An area's geofence: its circle, entering only, for as long as it is registered. */
        fun geofence(area: Area): Geofence = Geofence.Builder()
            .setRequestId(area.id)
            .setCircularRegion(area.lat, area.lon, area.radiusM.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        /**
         * The one PendingIntent every geofence fires: explicit, to the non-exported [AreaGeofenceReceiver], and
         * mutable, because Play services fills in the event (the transition and the areas) when it sends it; the
         * Geofencing API requires `FLAG_MUTABLE` from Android 12. `FLAG_UPDATE_CURRENT`, so a remove finds the same one.
         */
        fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, AreaGeofenceReceiver::class.java).setAction(AreaGeofenceReceiver.ACTION_GEOFENCE)
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            // The geofencing PendingIntent must be mutable (Play services adds the event's extras); it is explicit, to
            // this app's non-exported receiver, so no other app can redirect it. Lint's warning is expected here only.
            @Suppress("UnspecifiedImmutableFlag")
            return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
        }

        private const val REQUEST_CODE = 4_000
    }
}

/**
 * The wake-up's permissions (5.18): precise location, and from Android 10 background location (*Allow all the time*);
 * below Android 10 the foreground grant covers the background too. Geofencing needs precise location.
 */
fun hasAreaWakeupPermissions(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
}

/**
 * The area wake-up's geofences (docs/11 "Design of slice 4b", 5.17 *Re-registration*): [reregisterAll] removes them
 * all and adds [AreaWakeup.geofencesFor] the stored areas, the setting and the permission, so the registered set is
 * always the enabled areas (at most 20) while the setting is on and background location is granted, and none
 * otherwise. Called at app start and after every change of the areas or the setting (DoorprintsApp's debounced
 * collector), on every resume ([resumed], which first switches the setting off when the permission is gone), after
 * boot and an app update, on `GEOFENCE_NOT_AVAILABLE` ([AreaGeofenceReceiver]) and when the permission changes (the
 * rationale's grant turns the setting on; a loss is seen on resume). One run at a time.
 */
class AreaGeofenceManager(
    private val repository: Repository,
    private val registrar: GeofenceRegistrar,
    private val permissionsGranted: () -> Boolean,
) {
    private val lock = Mutex()

    /** Sets the geofences again; returns the areas now registered. Also forgets the stamps of areas that are gone. */
    suspend fun reregisterAll(): List<Area> = lock.withLock {
        val settings = repository.settings
        val areas = repository.areas()
        runCatching { settings.pruneAreaLastNotified(areas.mapTo(HashSet()) { it.id }) }
        val set = AreaWakeup.geofencesFor(areas, settings.areaWakeup().first(), permissionsGranted())
        registrar.removeAll()
        if (set.isNotEmpty()) registrar.add(set)
        set
    }

    /**
     * The app came to the front (5.18 *Every app resume*): with the setting on and the permission gone (denied,
     * downgraded to "while using" or approximate, revoked), the setting is switched off with its one-time card and the
     * geofences removed; otherwise they are registered again. Returns whether it switched the setting off.
     */
    suspend fun resumed(): Boolean {
        val settings = repository.settings
        if (settings.areaWakeup().first() && !permissionsGranted()) {
            val switched = settings.switchAreaWakeupOffForPermission()
            lock.withLock { registrar.removeAll() }
            return switched
        }
        reregisterAll()
        return false
    }
}

/** Whether Google Play services are on this phone and usable: without them the wake-up is hidden (5.17 *Offline*). */
fun hasPlayServices(context: Context): Boolean =
    runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }
        .getOrDefault(false)

/**
 * [AreaWakeupServices] on Android: Play services' availability, the permission checks, the resume hook (in the app's
 * scope) and the background location request of 5.18.
 */
class AndroidAreaWakeup(private val app: DoorprintsApp) : AreaWakeupServices {
    override val available: Boolean by lazy { hasPlayServices(app) }

    override fun backgroundGranted(): Boolean = hasAreaWakeupPermissions(app)

    override fun resumed() {
        if (!available) return
        app.appScope.launch { runCatching { app.container.areaWakeup.resumed() } }
    }

    /**
     * Android 10: the system dialog with *Allow all the time*. Android 11 and later: the same request opens the app's
     * location permission page; once Android will no longer open it (refused twice), the app's settings page instead
     * (`ACTION_APPLICATION_DETAILS_SETTINGS`), whose answer the rationale reads on resume. Below Android 10 there is
     * nothing to ask: [onResult] at once.
     */
    @Composable
    override fun rememberBackgroundLocationRequest(onResult: () -> Unit): () -> Unit {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
        return remember(context, launcher) {
            {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    onResult()
                } else {
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val activity = context.findActivity()
                    val rationale = activity != null &&
                        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !canAskAgain(prefs.getBoolean(KEY_ASKED, false), rationale)) {
                        openAppSettings(context)
                    } else {
                        prefs.edit().putBoolean(KEY_ASKED, true).apply()
                        try {
                            launcher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        } catch (_: ActivityNotFoundException) {
                            openAppSettings(context)
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun rememberForegroundLocationRequest(onResult: () -> Unit): () -> Unit = rememberLocationPermissionRequest(onResult)

    private companion object {
        /** The location prompts' flags (LocationPermission.android.kt keeps the foreground one in the same file). */
        const val PREFS = "map_permissions"
        const val KEY_ASKED = "backgroundLocationAsked"
    }
}
