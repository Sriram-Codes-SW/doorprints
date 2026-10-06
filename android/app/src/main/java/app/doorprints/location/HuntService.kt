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

package app.doorprints.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import app.doorprints.DoorprintsApp
import app.doorprints.Notifications
import app.doorprints.shared.records.RecordRules
import app.doorprints.R
import app.doorprints.data.HouseEntity
import app.doorprints.data.labelRes
import app.doorprints.shared.location.StreetAlerts
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.Scoring
import app.doorprints.i18n.AppLocale
import app.doorprints.ui.Formats
import app.doorprints.ui.RepeatAlerts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * "Hunt mode" on Android: a foreground service around the common [HuntEngine] (Sprint 4b, 2026-09-29; the rules were
 * in this class before). This class keeps what only Android can do: the foreground service and its notification, the
 * fused location client (GPS fixes every 15 s while walking, every 60 s while standing still, docs/09 L1), the battery
 * reading, the reverse geocoder, and the wording and posting of the alerts the engine asks for ([HuntEffects]).
 * Everything else, what a fix means, is the engine's, and the same on iPhone.
 */
class HuntService : LifecycleService(), HuntEffects {

    private val repo by lazy { (application as DoorprintsApp).container.repository }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val geocoder by lazy { ReverseGeocoder(this) }
    private val engine by lazy {
        HuntEngine(
            data = HuntData.of(repo),
            streets = { lat, lon -> geocoder.lookup(lat, lon)?.street },
            battery = {
                getSystemService(BatteryManager::class.java)?.let {
                    BatteryLevel(it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), it.isCharging)
                }
            },
            effects = this,
            scope = lifecycleScope,
        )
    }

    /** Set just before a stopSelf() the user did not ask for; the Map says why (see [HuntState.State.stopReason]). */
    private var stopReason: HuntState.StopReason? = null

    private fun stopFor(reason: HuntState.StopReason) {
        stopReason = reason
        stopSelf()
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { engine.onFix(it.latitude, it.longitude, it.accuracy, it.time) }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            // The user's own Stop (the notification action): no reason to show.
            stopReason = null
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_FINISH_WALK) {
            // *Finish walk* from the Map (docs/11 5.27.6): the walk ends, Hunt mode goes on (already in the foreground).
            if (HuntState.state.value.active) engine.finishWalk()
            return START_STICKY
        }
        val stopIntent = android.app.PendingIntent.getService(
            this, 0, Intent(this, HuntService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_HUNT)
            .setSmallIcon(R.drawable.ic_stat_doorprints)
            .setContentTitle(getString(R.string.notif_hunt_title))
            .setContentText(getString(R.string.notif_hunt_text))
            .setOngoing(true)
            .setContentIntent(Notifications.openAppIntent(this, 0))
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        // startForeground comes first: a service started with startForegroundService must call it within a few
        // seconds. It throws SecurityException on API 34+ when the location permission is missing (type "location"),
        // and ForegroundServiceStartNotAllowedException on API 31+ when a START_STICKY restart happens while the
        // app is in the background. Either way Hunt mode cannot run, so stop quietly instead of crashing.
        val inForeground = try {
            ServiceCompat.startForeground(this, Notifications.ONGOING_ID, notification, type)
            true
        } catch (e: RuntimeException) {
            false
        }
        if (!inForeground) {
            stopFor(HuntState.StopReason.NOT_ALLOWED)
            return START_NOT_STICKY
        }
        if (!hasLocationPermission(this)) {
            stopFor(HuntState.StopReason.NO_PERMISSION)
            return START_NOT_STICKY
        }

        stopReason = null
        engine.start()
        // Started from a Hunt mode reminder's *Start Hunt mode* (slice 3c): that reminder has done its job.
        intent?.getStringExtra(Notifications.EXTRA_START_HUNT)?.let {
            NotificationManagerCompat.from(this).cancel(Notifications.huntTag(it), Notifications.HUNT_REMINDER_ID)
        }
        // Or from an area wake-up's (slice 4b).
        intent?.getStringExtra(Notifications.EXTRA_START_HUNT_AREA)?.takeIf(RecordRules::isValidId)?.let {
            NotificationManagerCompat.from(this).cancel(Notifications.areaTag(it), Notifications.AREA_WAKEUP_ID)
        }
        return START_STICKY
    }

    /** Same callback, so a new request replaces the previous one. */
    @SuppressLint("MissingPermission")
    override fun requestUpdates(stationary: Boolean) {
        val request = if (stationary) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60_000L)
                .setMinUpdateIntervalMillis(30_000L)
                .setMinUpdateDistanceMeters(10f)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
                .setMinUpdateIntervalMillis(5_000L)
                .setMinUpdateDistanceMeters(5f)
                .build()
        }
        runCatching { fused.requestLocationUpdates(request, callback, Looper.getMainLooper()) }
            .onFailure { stopFor(HuntState.StopReason.NO_PERMISSION) } // permission revoked while running
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        engine.stopped(stopReason)
        super.onDestroy()
    }

    override fun lowBattery(percent: Int) {
        Notifications.alert(this, Notifications.LOW_BATTERY_ID, getString(R.string.notif_battery_title),
            getString(R.string.notif_battery_text, percent), Notifications.openAppIntent(this, 0))
    }

    override fun stop(reason: HuntState.StopReason) = stopFor(reason)

    override fun alertHouse(house: HouseEntity, distanceM: Int) {
        // The score under this phone's criteria (slice 2): one small read of the records before the alert is worded.
        lifecycleScope.launch {
            val scoring = repo.scoring()
            Notifications.alert(
                this@HuntService, house.id.hashCode(),
                getString(R.string.notif_seen_house, house.label),
                getString(R.string.notif_distance, describe(house, scoring), distanceM),
                Notifications.openAppIntent(this@HuntService, house.id.hashCode()) {
                    putExtra(Notifications.EXTRA_OPEN_HOUSE, house.id)
                },
                hideOnLockScreen = appLockOn(),
            )
        }
    }

    /** Whether the app lock is on (S4b-BL-68): its alerts then name nothing on a locked screen. Unreadable: on. */
    private suspend fun appLockOn(): Boolean =
        runCatching { repo.settings.appLockSetting.first().on }.getOrDefault(true)

    override fun alertRepeat(runM: Int) {
        // The notification holds no place, distance or count (docs/11 5.27.5); [runM] is not used or logged.
        lifecycleScope.launch {
            Notifications.alertRepeatPath(this@HuntService, hideOnLockScreen = appLockOn())
            // With the app in front the Map also says it in a snackbar (it collects this).
            RepeatAlerts.signal()
        }
    }

    override fun alertStreet(street: String, houses: Int, visits: Int, firstVisit: Long?) {
        val key = StreetAlerts.key(street)
        val text = firstVisit?.let {
            getString(R.string.notif_street_text_since, houses, visits, Formats.date(it))
        } ?: getString(R.string.notif_street_text, houses, visits)
        // The street's name is where the person is, as private as a house's (S4b-BL-68).
        lifecycleScope.launch {
            Notifications.alert(
                this@HuntService, key.hashCode(),
                getString(R.string.notif_street_title, street),
                text,
                Notifications.openAppIntent(this@HuntService, key.hashCode()),
                hideOnLockScreen = appLockOn(),
            )
        }
    }

    override fun alertStay(visitId: String, lat: Double, lon: Double) {
        Notifications.alert(
            this, visitId.hashCode(),
            getString(R.string.notif_stay_title),
            getString(R.string.notif_stay_text),
            Notifications.openAppIntent(this, visitId.hashCode()) {
                putExtra(Notifications.EXTRA_NEW_LAT, lat)
                putExtra(Notifications.EXTRA_NEW_LON, lon)
                putExtra(Notifications.EXTRA_VISIT_ID, visitId)
            },
        )
    }

    private fun describe(h: HouseEntity, scoring: Scoring): String {
        val parts = mutableListOf<String>()
        if (h.status != HouseStatus.NEW) parts += getString(h.status.labelRes)
        Formats.price(h.price, h.priceType) { getString(R.string.price_per_month, it) }?.let { parts += it }
        h.rating?.let { parts += "★".repeat(it) }
        h.score(scoring)?.let { parts += getString(R.string.common_score_value, Formats.score(it)) }
        return parts.joinToString(" · ").ifEmpty { getString(R.string.notif_visited_before) }
    }

    companion object {
        private const val ACTION_STOP = "stop"
        private const val ACTION_FINISH_WALK = "finish-walk"

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Starts Hunt mode. Returns false without starting when location permission is missing (a location
         * foreground service cannot start without it on API 34+) or when the system refuses a foreground-service
         * start because the app is not in the foreground (API 31+).
         */
        fun start(context: Context): Boolean {
            if (!hasLocationPermission(context)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, HuntService::class.java))
                true
            } catch (e: IllegalStateException) {
                false
            }
        }

        /** The user turned Hunt mode off: onDestroy runs with no stop reason. */
        fun stop(context: Context) = context.stopService(Intent(context, HuntService::class.java))

        /** *Finish walk*: asks the running service to end the walk (nothing while Hunt mode is off). */
        fun finishWalk(context: Context) {
            if (!HuntState.state.value.active) return
            runCatching { context.startService(Intent(context, HuntService::class.java).setAction(ACTION_FINISH_WALK)) }
        }

        /** Closes the "Hunt mode stopped because…" card on the Map. */
        fun clearStopReason() = HuntState.update { it.copy(stopReason = null) }
    }
}
