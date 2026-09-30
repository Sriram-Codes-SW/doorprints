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

import app.doorprints.data.HouseEntity
import app.doorprints.location.BatteryLevel
import app.doorprints.location.HuntData
import app.doorprints.location.HuntEffects
import app.doorprints.location.HuntEngine
import app.doorprints.location.HuntState
import app.doorprints.location.Place
import app.doorprints.shared.location.Geo
import app.doorprints.shared.location.StreetAlerts
import app.doorprints.shared.model.HouseStatus
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.common_score_value
import app.doorprints.ui.res.hunt_battery_text
import app.doorprints.ui.res.notif_battery_title
import app.doorprints.ui.res.notif_distance
import app.doorprints.ui.res.notif_seen_house
import app.doorprints.ui.res.notif_stay_text
import app.doorprints.ui.res.notif_stay_title
import app.doorprints.ui.res.notif_street_text
import app.doorprints.ui.res.notif_street_text_since
import app.doorprints.ui.res.notif_street_title
import app.doorprints.ui.res.notif_visited_before
import app.doorprints.ui.res.price_per_month
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import platform.CoreLocation.CLActivityTypeFitness
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLPlacemark
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.experimental.ExperimentalNativeApi
import kotlin.math.roundToInt
import kotlin.native.Platform

/**
 * Hunt mode on iPhone (S4b-BL-69): the adapter around the common [HuntEngine], what Android's `HuntService` is there.
 * This object keeps what only iOS can do: Core Location (`CLLocationManager` with background updates under the
 * *When in use* permission, so a walk with the phone in the pocket keeps its fixes; the blue location indicator shows
 * it), the thinning of its stream to the engine's rate ([HuntFixThrottle]), the battery from `UIDevice`, Apple's
 * geocoder ([IosGeocoder]) and the alerts as local notifications ([IosNotifications]), worded here from the Compose
 * resources. What a fix means is the engine's, the same as on Android.
 *
 * One per process, main thread only: Core Location delivers on the thread that made the manager, the engine is not
 * thread-safe, and the Map starts and stops it from the main thread. The engine's [scope] runs on the main
 * dispatcher and ends with Hunt mode; the alerts post from a scope of their own, so a "battery is low" alert is not
 * cancelled by the stop that follows it.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosHunt : HuntEffects {
    /** The `userInfo` keys of a tapped alert, read back in `MainViewController.kt` as deep links; values are strings. */
    const val KEY_OPEN_HOUSE = "openHouse"
    const val KEY_NEW_LAT = "newLat"
    const val KEY_NEW_LON = "newLon"
    const val KEY_VISIT_ID = "visitId"
    /** A Hunt mode reminder's viewing (slice 3c, [IosViewingReminders]); its tap opens the viewing. */
    const val KEY_OPEN_VIEWING = "openViewing"

    private val alerts = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val throttle = HuntFixThrottle()
    private var manager: CLLocationManager? = null
    private var delegate: FixDelegate? = null
    private var engine: HuntEngine? = null
    private var scope: CoroutineScope? = null

    /** Set before a stop the user did not ask for; the Map says why ([HuntState.State.stopReason]). */
    private var stopReason: HuntState.StopReason? = null

    val running: Boolean get() = engine != null

    /**
     * Starts Hunt mode; true when it runs (already or from now). False without starting when the location permission
     * is missing, as Android's `HuntService.start`: the Map then shows its location note.
     */
    fun start(): Boolean {
        if (engine != null) return true
        if (iosLocationAccess() == LocationAccess.NONE) return false
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val newManager = CLLocationManager().apply {
            desiredAccuracy = kCLLocationAccuracyBest
            // Every fix; the throttle below keeps the engine at Android's rate (the GPS runs either way).
            distanceFilter = kCLDistanceFilterNone
            activityType = CLActivityTypeFitness
            // iOS would otherwise stop the updates for good after a long stay and not resume them on its own.
            pausesLocationUpdatesAutomatically = false
            // Needs UIBackgroundModes "location" in Info.plist; "When in use" is enough ("Always" is the area wake-up's).
            allowsBackgroundLocationUpdates = true
            showsBackgroundLocationIndicator = true
        }
        val newDelegate = FixDelegate()
        newManager.delegate = newDelegate
        manager = newManager
        delegate = newDelegate
        scope = engineScope
        stopReason = null
        throttle.reset()
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        val newEngine = HuntEngine(
            data = HuntData.of(IosAppContainer.repository),
            streets = { lat, lon -> IosGeocoder.place(lat, lon)?.street },
            battery = ::readBattery,
            effects = this,
            scope = engineScope,
        )
        engine = newEngine
        // start() asks for walking-rate fixes (requestUpdates), which starts Core Location.
        newEngine.start()
        breadcrumb("start access=${iosLocationAccess()}")
        return true
    }

    /** The user turned Hunt mode off: no reason to show. */
    fun stop() {
        stopReason = null
        end()
    }

    /** Closes the "Hunt mode stopped because…" card on the Map. */
    fun clearStopReason() = HuntState.update { it.copy(stopReason = null) }

    private fun stopFor(reason: HuntState.StopReason) {
        stopReason = reason
        end()
    }

    private fun end() {
        val current = engine ?: return
        manager?.stopUpdatingLocation()
        manager?.delegate = null
        manager = null
        delegate = null
        UIDevice.currentDevice.batteryMonitoringEnabled = false
        engine = null
        current.stopped(stopReason)
        scope?.cancel()
        scope = null
        breadcrumb("stop reason=$stopReason")
    }

    /** Core Location has no interval: the manager runs, and [throttle] applies the walking or staying rate. */
    override fun requestUpdates(stationary: Boolean) {
        throttle.stationary = stationary
        manager?.startUpdatingLocation()
    }

    private fun onLocations(locations: List<CLLocation>) {
        for (location in locations) {
            val current = engine ?: return // stopped itself on an earlier fix of this batch
            // A negative accuracy is Core Location's "no valid fix".
            val accuracy = location.horizontalAccuracy
            if (accuracy < 0.0) continue
            val (lat, lon) = location.coordinate.useContents { latitude to longitude }
            val at = (location.timestamp.timeIntervalSince1970 * 1000).toLong()
            if (!throttle.accept(lat, lon, at)) continue
            breadcrumb("fix accuracy=${accuracy.toInt()}")
            current.onFix(lat, lon, accuracy.toFloat(), at)
        }
    }

    /** The permission went away while running (the Settings app): Hunt mode ends and the Map says so. */
    private fun onAuthorizationChanged() {
        if (engine != null && iosLocationAccess() == LocationAccess.NONE) stopFor(HuntState.StopReason.NO_PERMISSION)
    }

    /** Errors other than a denial (no fix yet, a lost signal) are transient: Core Location keeps trying. */
    private fun onError(error: NSError) {
        breadcrumb("error code=${error.code}")
        onAuthorizationChanged()
    }

    /**
     * One `DOORPRINTS-HUNT …` line in the unified log, in debug binaries only (the launch smoke's artifact reads them):
     * the start, each fix that reaches the engine (its accuracy, never where), an error's code and the stop.
     */
    @OptIn(ExperimentalNativeApi::class)
    private fun breadcrumb(text: String) {
        if (Platform.isDebugBinary) logLine("DOORPRINTS-HUNT $text")
    }

    override fun stop(reason: HuntState.StopReason) = stopFor(reason)

    override fun lowBattery(percent: Int) = alert(ID_BATTERY) {
        getString(Res.string.notif_battery_title) to getString(Res.string.hunt_battery_text, percent)
    }

    override fun alertHouse(house: HouseEntity, distanceM: Int) =
        alert("house:${house.id}", mapOf<Any?, Any?>(KEY_OPEN_HOUSE to house.id)) {
            getString(Res.string.notif_seen_house, house.label) to
                getString(Res.string.notif_distance, describe(house), distanceM)
        }

    override fun alertStreet(street: String, houses: Int, visits: Int, firstVisit: Long?) =
        alert("street:${StreetAlerts.key(street)}") {
            val text = firstVisit?.let { getString(Res.string.notif_street_text_since, houses, visits, Formats.date(it)) }
                ?: getString(Res.string.notif_street_text, houses, visits)
            getString(Res.string.notif_street_title, street) to text
        }

    override fun alertStay(visitId: String, lat: Double, lon: Double) = alert(
        stayAlertId(visitId),
        mapOf<Any?, Any?>(KEY_NEW_LAT to lat.toString(), KEY_NEW_LON to lon.toString(), KEY_VISIT_ID to visitId),
    ) { getString(Res.string.notif_stay_title) to getString(Res.string.notif_stay_text) }

    /** The id of the "are you at a house?" alert for [visitId]; the house form takes it down once saved. */
    fun stayAlertId(visitId: String): String = "stay:$visitId"

    /** Words the alert from the resources (suspending) and posts it; [words] gives the title and the body. */
    private fun alert(id: String, userInfo: Map<Any?, *> = emptyMap<Any?, Any?>(), words: suspend () -> Pair<String, String>) {
        alerts.launch {
            val (title, body) = words()
            IosNotifications.post(id, title, body, userInfo)
        }
    }

    /** As `HuntService.describe`: the status, the price, the stars and the score, or "Visited before". */
    private suspend fun describe(h: HouseEntity): String {
        val parts = mutableListOf<String>()
        if (h.status != HouseStatus.NEW) parts += getString(h.status.labelResource)
        // price_per_month is "%1$s/month": filled with a marker here, since Formats.price takes a plain function.
        val perMonth = getString(Res.string.price_per_month, AMOUNT_MARK)
        Formats.price(h.price, h.priceType) { perMonth.replace(AMOUNT_MARK, it) }?.let { parts += it }
        h.rating?.let { parts += "★".repeat(it) }
        h.score(IosAppContainer.repository.scoring())?.let { parts += getString(Res.string.common_score_value, Formats.score(it)) }
        return parts.joinToString(" · ").ifEmpty { getString(Res.string.notif_visited_before) }
    }

    /** `UIDevice`'s battery while monitoring is on; null where iOS does not know it (the simulator). */
    private fun readBattery(): BatteryLevel? {
        val device = UIDevice.currentDevice
        val level = device.batteryLevel
        if (level < 0f) return null
        val state = device.batteryState
        val charging = state == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
            state == UIDeviceBatteryState.UIDeviceBatteryStateFull
        return BatteryLevel((level * 100).roundToInt(), charging)
    }

    private const val ID_BATTERY = "battery"
    private const val AMOUNT_MARK = "\u0001"

    private class FixDelegate : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) =
            onLocations(didUpdateLocations.filterIsInstance<CLLocation>())

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) = onError(didFailWithError)

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) = onAuthorizationChanged()
    }
}

/**
 * Thins Core Location's stream (a fix a second while moving) to the rate Android's fused client gives `HuntService`:
 * walking, a fix every 15 s, or sooner (5 s) once 5 m away; staying, every 60 s, or sooner (30 s) once 10 m away
 * (docs/09 L1). The first fix after [reset] always passes. Plain Kotlin, so `HuntFixThrottleTest` runs it.
 */
internal class HuntFixThrottle {
    var stationary = false
    private var lastAt = Long.MIN_VALUE
    private var lastLat = 0.0
    private var lastLon = 0.0

    fun reset() {
        lastAt = Long.MIN_VALUE
    }

    /** True when the fix at [at] (epoch ms) goes to the engine and becomes the last one passed. */
    fun accept(lat: Double, lon: Double, at: Long): Boolean {
        val interval = if (stationary) STAYING_MS else WALKING_MS
        val soonest = if (stationary) STAYING_SOONEST_MS else WALKING_SOONEST_MS
        val distance = if (stationary) STAYING_DISTANCE_M else WALKING_DISTANCE_M
        val since = at - lastAt
        val keep = lastAt == Long.MIN_VALUE || since >= interval ||
            (since >= soonest && Geo.distanceM(lastLat, lastLon, lat, lon) >= distance)
        if (keep) {
            lastLat = lat
            lastLon = lon
            lastAt = at
        }
        return keep
    }

    companion object {
        const val WALKING_MS = 15_000L
        const val WALKING_SOONEST_MS = 5_000L
        const val WALKING_DISTANCE_M = 5.0
        const val STAYING_MS = 60_000L
        const val STAYING_SOONEST_MS = 30_000L
        const val STAYING_DISTANCE_M = 10.0
    }
}

/**
 * Apple's reverse geocoder (`CLGeocoder`, free with the platform, no key), what Android's `ReverseGeocoder` is there:
 * the street for Hunt mode's street alerts and the [Place] the house form fills a new house from. Null when offline,
 * when Apple has no answer, or after [LOOKUP_TIMEOUT_MS]. Talks to Core Location on the main thread.
 */
internal object IosGeocoder {
    /** How long a lookup may take before it counts as "no answer" (as Android's). */
    const val LOOKUP_TIMEOUT_MS = 10_000L

    suspend fun place(lat: Double, lon: Double): Place? {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { lookup(lat, lon) }
        }
    }

    private suspend fun lookup(lat: Double, lon: Double): Place? = suspendCancellableCoroutine { continuation ->
        val geocoder = CLGeocoder()
        geocoder.reverseGeocodeLocation(CLLocation(latitude = lat, longitude = lon)) { placemarks, _ ->
            val mark = placemarks?.firstOrNull() as? CLPlacemark
            if (continuation.isActive) continuation.resume(mark?.toPlace())
        }
        continuation.invokeOnCancellation { geocoder.cancelGeocode() }
    }

    /** The street, the area (or the city) and one address line from the placemark's parts. */
    private fun CLPlacemark.toPlace(): Place {
        val streetLine = listOfNotNull(subThoroughfare, thoroughfare).joinToString(" ").ifBlank { null }
        val line = listOfNotNull(streetLine, subLocality, locality, administrativeArea, postalCode)
            .joinToString(", ").ifBlank { null }
        return Place(street = thoroughfare, locality = subLocality ?: locality, address = line)
    }
}
