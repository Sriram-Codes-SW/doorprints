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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaCooldown
import app.doorprints.shared.model.AreaRegions
import app.doorprints.shared.records.RecordRules
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.notif_area_wakeup
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import platform.CoreLocation.CLCircularRegion
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLRegion
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.Foundation.NSError
import platform.Foundation.NSUserDefaults
import platform.darwin.NSObject
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

// The area wake-up on iPhone (S4b-BL-96; docs/11 "Design of slice 4b", 5.17, 5.18), what Android's
// AreaGeofenceManager and AreaGeofenceReceiver are there: Core Location's region monitoring for the enabled areas, the
// "Always" permission behind the common rationale, and "You're in Adyar. Start Hunt mode?" on arriving.

/**
 * What the registration needs from Core Location: the real manager ([CoreLocationRegions]) or a fake (the launch
 * self-check's [RecordingRegionMonitor]). Main thread.
 */
internal interface RegionMonitor {
    /** False where this iPhone cannot monitor circular regions: the setting is hidden and nothing is registered. */
    val available: Boolean

    /** The largest radius Core Location takes (`maximumRegionMonitoringDistance`); not positive when unknown. */
    val maxRadiusM: Double

    /** Every region the app monitors now, the wake-up's and any other (non-circular ones with a zero radius). */
    fun monitored(): List<AreaRegions.Region>

    fun start(region: AreaRegions.Region)

    fun stop(identifier: String)

    /** Core Location's last fix, if it has one (never a new request), for the nearest 20. */
    fun lastPosition(): Pair<Double, Double>?
}

/**
 * Brings the monitored regions to [AreaRegions.wanted] with the fewest calls ([AreaRegions.plan]): an unchanged area
 * keeps its region, so a registration at every resume costs nothing and loses no entry Core Location is working out.
 * Main thread, like the monitor.
 */
internal class AreaRegionSync(private val monitor: RegionMonitor) {
    fun apply(areas: List<Area>, wakeupOn: Boolean, alwaysGranted: Boolean): AreaRegions.Plan {
        val on = wakeupOn && monitor.available
        val wanted = AreaRegions.wanted(areas, on, alwaysGranted, if (on) monitor.lastPosition() else null, monitor.maxRadiusM)
        val plan = AreaRegions.plan(monitor.monitored(), wanted)
        plan.stop.forEach(monitor::stop)
        plan.start.forEach(monitor::start)
        return plan
    }
}

/**
 * [RegionMonitor] on a `CLLocationManager`: circular regions, entering only (`notifyOnEntry`, never `notifyOnExit`).
 * Core Location does not report being inside a region when it starts monitoring it, only arriving, as Android's initial
 * trigger of 0 (5.17 *On entering*). The regions outlive the app: iOS relaunches it in the background for an entry.
 */
@OptIn(ExperimentalForeignApi::class)
internal class CoreLocationRegions(private val manager: CLLocationManager) : RegionMonitor {
    override val available: Boolean get() = canMonitorCircles()

    override val maxRadiusM: Double get() = manager.maximumRegionMonitoringDistance

    override fun monitored(): List<AreaRegions.Region> = regions().map { r ->
        val circle = r as? CLCircularRegion
        if (circle == null) {
            AreaRegions.Region(r.identifier, 0.0, 0.0, 0.0)
        } else {
            circle.center.useContents { AreaRegions.Region(r.identifier, latitude, longitude, circle.radius) }
        }
    }

    override fun start(region: AreaRegions.Region) {
        val circle = CLCircularRegion(
            center = CLLocationCoordinate2DMake(region.lat, region.lon), radius = region.radiusM, identifier = region.identifier,
        )
        circle.notifyOnEntry = true
        circle.notifyOnExit = false
        manager.startMonitoringForRegion(circle)
    }

    override fun stop(identifier: String) {
        regions().filter { it.identifier == identifier }.forEach(manager::stopMonitoringForRegion)
    }

    override fun lastPosition(): Pair<Double, Double>? =
        manager.location?.takeIf { it.horizontalAccuracy >= 0.0 }?.coordinate?.useContents { latitude to longitude }

    private fun regions(): List<CLRegion> = manager.monitoredRegions.filterIsInstance<CLRegion>()
}

/** Whether this iPhone can monitor circular regions (`isMonitoringAvailableForClass`). */
@OptIn(BetaInteropApi::class)
internal fun canMonitorCircles(): Boolean = CLLocationManager.isMonitoringAvailableForClass(CLCircularRegion)

/**
 * The wake-up's one `CLLocationManager` and what it hears. [install] makes it (from the Swift app's `init`, so a
 * background relaunch for an entry finds the delegate in place); [reregisterAll] sets the regions from the stored
 * areas, the setting and the permission, at start and after every change of the areas or the setting ([install]'s
 * debounced collector), on every resume and when the authorization changes ([resumed], which first switches the
 * setting off when "Always" is gone). One run at a time.
 */
internal object IosAreaWakeup {
    /** A tapped wake-up notification's `userInfo` key, the area's id: the Map, with Hunt mode ([DeepLink.StartHunt]). */
    const val KEY_START_HUNT_AREA = "startHuntArea"

    private const val WATCH_DEBOUNCE_MS = 1_000L

    private val lock = Mutex()
    private var manager: CLLocationManager? = null
    private var delegate: RegionDelegate? = null
    private var sync: AreaRegionSync? = null

    /** Once per process, on the main thread. */
    @OptIn(FlowPreview::class)
    fun install() {
        if (manager != null) return
        // The data opens on the main thread, as everywhere else ([IosAppContainer]).
        val repository = IosAppContainer.repository
        val newManager = CLLocationManager()
        val newDelegate = RegionDelegate()
        manager = newManager
        delegate = newDelegate
        sync = AreaRegionSync(CoreLocationRegions(newManager))
        // Also answers once at once with the current authorization, which registers (or switches off) at start.
        newManager.delegate = newDelegate
        IosAppContainer.appScope.launch {
            combine(repository.observeAreas().distinctUntilChanged(), repository.settings.areaWakeup()) { a, on -> a to on }
                .debounce(WATCH_DEBOUNCE_MS)
                .collect { catchFailures { reregisterAll() } }
        }
    }

    /** Sets the regions again; also forgets the cooldown stamps of areas that are gone. */
    suspend fun reregisterAll(): AreaRegions.Plan = lock.withLock {
        val repository = IosAppContainer.repository
        val settings = repository.settings
        val areas = repository.areas()
        catchFailures { settings.pruneAreaLastNotified(areas.mapTo(HashSet()) { it.id }) }
        val on = settings.areaWakeup().first()
        withContext(Dispatchers.Main) {
            install()
            val plan = checkNotNull(sync).apply(areas, on, iosAlwaysGranted())
            breadcrumb("regions stop=${plan.stop.size} start=${plan.start.size}")
            plan
        }
    }

    /**
     * The app came to the front, or the authorization changed (5.18 *Every app resume*): with the setting on and
     * "Always" with precise location gone, the setting is switched off with its one-time card and the regions stop;
     * otherwise they are registered again. Returns whether it switched the setting off.
     */
    suspend fun resumed(): Boolean {
        val settings = IosAppContainer.repository.settings
        val lost = settings.areaWakeup().first() && !withContext(Dispatchers.Main) { iosAlwaysGranted() }
        val switched = lost && settings.switchAreaWakeupOffForPermission()
        reregisterAll()
        return switched
    }

    /**
     * The person arrived in the areas [ids] (from region identifiers, untrusted: only valid record ids are read) at
     * [nowMs]. For each area still live and enabled, with the setting on, Hunt mode not running and its last
     * notification at least 6 hours old ([AreaCooldown]), posts "You're in <area>. Start Hunt mode?" and stamps the
     * area; nothing without the notification permission. Returns the ids posted. It never starts Hunt mode: the tap
     * opens the Map, which does (an iPhone app cannot start tracking from a notification action).
     */
    suspend fun enter(ids: List<String>, nowMs: Long): List<String> {
        val repository = IosAppContainer.repository
        val settings = repository.settings
        val on = settings.areaWakeup().first()
        val wanted = ids.filter(RecordRules::isValidId).distinct()
        if (!on || wanted.isEmpty()) return emptyList()
        val live = repository.areas().associateBy { it.id }
        val huntRunning = withContext(Dispatchers.Main) { IosHunt.running }
        val posted = ArrayList<String>()
        for (id in wanted) {
            val area = live[id]?.takeIf { it.enabled } ?: continue
            if (!AreaCooldown.shouldNotify(settings.areaLastNotified(id), nowMs, huntRunning, on)) continue
            if (!IosNotifications.authorized()) continue
            val body = getString(Res.string.notif_area_wakeup, area.name)
            withContext(Dispatchers.Main) { IosNotifications.post("area:$id", "", body, mapOf<Any?, Any?>(KEY_START_HUNT_AREA to id)) }
            settings.setAreaLastNotified(id, nowMs)
            posted += id
        }
        breadcrumb("enter posted=${posted.size}")
        return posted
    }

    private fun onEnter(identifier: String) {
        val id = AreaRegions.areaId(identifier) ?: return
        IosAppContainer.appScope.launch { catchFailures { enter(listOf(id), nowMillis()) } }
    }

    private fun onAuthorizationChanged() {
        IosAppContainer.appScope.launch { catchFailures { resumed() } }
    }

    /** `DOORPRINTS-AREAS …` in the unified log, debug binaries only: counts, never an area or a position. */
    @OptIn(ExperimentalNativeApi::class)
    private fun breadcrumb(text: String) {
        if (Platform.isDebugBinary) logLine("DOORPRINTS-AREAS $text")
    }

    private class RegionDelegate : NSObject(), CLLocationManagerDelegateProtocol {
        @ObjCSignatureOverride
        override fun locationManager(manager: CLLocationManager, didEnterRegion: CLRegion) = onEnter(didEnterRegion.identifier)

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) = onAuthorizationChanged()

        /** A region Core Location could not take (too many, or location off): the next resume registers again. */
        @ObjCSignatureOverride
        override fun locationManager(manager: CLLocationManager, monitoringDidFailForRegion: CLRegion?, withError: NSError) =
            breadcrumb("failed code=${withError.code}")
    }
}

/**
 * [AreaWakeupServices] on iPhone: region monitoring ([IosAreaWakeup]) and the two location prompts, *Allow While Using
 * App* first (the common rationale's foreground step), then *Change to Always Allow* ([AlwaysPrompt]); iOS shows the
 * second once only, so after that *Continue* opens the app's settings page, whose answer the rationale reads on resume.
 * Once "Always" is granted, the notification prompt follows if iOS will still show it, as the wake-up says nothing
 * without it.
 */
internal object IosAreaWakeupServices : AreaWakeupServices {
    override val available: Boolean by lazy { canMonitorCircles() }

    override val iphoneWording: Boolean get() = true

    override fun backgroundGranted(): Boolean = iosAlwaysGranted()

    override fun resumed() {
        if (!available) return
        IosAppContainer.appScope.launch { catchFailures { IosAreaWakeup.resumed() } }
    }

    @Composable
    override fun rememberBackgroundLocationRequest(onResult: () -> Unit): () -> Unit {
        val latest by rememberUpdatedState(onResult)
        val platform = LocalPlatformServices.current
        val prompt = remember {
            AlwaysPrompt {
                if (iosAlwaysGranted() && IosNotifications.canAsk()) IosNotifications.request { latest() } else latest()
            }
        }
        DisposableEffect(prompt) { onDispose { prompt.release() } }
        return remember(prompt, platform) { { if (!prompt.show()) platform.openAppSettings() } }
    }

    @Composable
    override fun rememberForegroundLocationRequest(onResult: () -> Unit): () -> Unit = rememberLocationPermissionRequest(onResult)
}

/**
 * iOS's "Change to Always Allow?" (`requestAlwaysAuthorization` from *While Using*). iOS shows it once in the app's
 * life and gives no answer when the person keeps *While Using*, so [show] returns false when it cannot be shown (asked
 * before, a flag in `NSUserDefaults`, or location not allowed while in use) and the caller opens the settings page;
 * a kept *While Using* is read on resume by the rationale. [onAnswered] runs on a change of the authorization. Main
 * thread.
 */
internal class AlwaysPrompt(private val onAnswered: () -> Unit) {
    private var manager: CLLocationManager? = null
    private var delegate: AuthorizationDelegate? = null

    fun show(): Boolean {
        if (manager != null) return true
        val defaults = NSUserDefaults.standardUserDefaults
        val newManager = CLLocationManager()
        if (newManager.authorizationStatus != kCLAuthorizationStatusAuthorizedWhenInUse || defaults.boolForKey(KEY_ASKED)) return false
        defaults.setBool(true, forKey = KEY_ASKED)
        val newDelegate = AuthorizationDelegate { status ->
            // Called once when the delegate is set (still while in use), and again when the answer changes it.
            if (status != kCLAuthorizationStatusAuthorizedWhenInUse) {
                release()
                onAnswered()
            }
        }
        newManager.delegate = newDelegate
        manager = newManager
        delegate = newDelegate
        newManager.requestAlwaysAuthorization()
        return true
    }

    fun release() {
        manager?.delegate = null
        manager = null
        delegate = null
    }

    private companion object {
        const val KEY_ASKED = "areaWakeup.alwaysAsked"
    }
}
