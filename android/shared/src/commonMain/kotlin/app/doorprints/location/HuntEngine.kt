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

import app.doorprints.data.HouseEntity
import app.doorprints.data.Repository
import app.doorprints.data.TrackPointEntity
import app.doorprints.data.VisitEntity
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.location.Geo
import app.doorprints.shared.location.StayDetector
import app.doorprints.shared.location.StreetAlerts
import app.doorprints.shared.model.LocationSource
import app.doorprints.shared.model.VisitSource
import app.doorprints.shared.trace.RepeatAlert
import app.doorprints.shared.trace.TraceConstants
import app.doorprints.shared.trace.TracePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Hunt mode's rules, in common code (docs/03 ADR-23; Sprint 4b, 2026-09-29, so the iPhone gets Hunt mode from the
 * same engine): what a GPS fix means. Given fixes by a platform adapter (Android's `HuntService`, later an iOS
 * `CLLocationManager` adapter), it
 *  - keeps [HuntState] for the Map's Hunt card,
 *  - alerts when you pass a saved house ([HuntEffects.alertHouse]; once per house per 30 minutes, within the alert
 *    radius from Settings),
 *  - tells you when you enter a street you have houses or visits on ([HuntEffects.alertStreet]; the street from the
 *    platform's [StreetLookup], asked at most every 45 s or 80 m; `StreetAlerts` decides),
 *  - notices when you stop somewhere for the minimum stay ([StayDetector]) and saves a visit, offering to save the
 *    place as a house when none is within 40 m ([HuntEffects.alertStay]),
 *  - asks for fewer fixes while you stay ([HuntEffects.requestUpdates]), and
 *  - stops itself when the battery is low ([HuntEffects.lowBattery], [HuntEffects.stop]; [LOW_BATTERY_PERCENT], read
 *    at most every two minutes from [BatteryReader]),
 *  - with *Trace my path* on (docs/11 5.27), keeps where the phone was ([TrackRecorder] thins the fixes; the points
 *    older than [Repository.TRACK_KEPT_DAYS] go when Hunt mode starts).
 * Fixes worse than [HuntState.MAX_ACCURACY_M] are shown but never trigger alerts or visits. The wording of alerts and
 * the notifications themselves stay with the platform: the engine hands over the facts.
 *
 * Not thread-safe: the adapter calls it from one thread, and [scope] must run on that same thread (Android: the
 * service's `lifecycleScope` on the main looper; iOS: a `Dispatchers.Main` scope), because the collectors and the
 * street lookups write the engine's fields from [scope]. A `Default` scope would race. [scope] is the adapter's
 * lifecycle scope, cancelled when Hunt mode ends.
 */
class HuntEngine(
    private val data: HuntData,
    private val streets: StreetLookup,
    private val battery: BatteryReader,
    private val effects: HuntEffects,
    private val scope: CoroutineScope,
    private val now: () -> Long = { IsoTime.nowMillis() },
    private val newId: () -> String = { randomId() },
) {
    private val stays = StayDetector()
    private val track = TrackRecorder()
    private var houses: List<HouseEntity> = emptyList()

    /** The last fix accurate enough to use: the houses are looked at again from it when the list changes. */
    private var lastFix: Pair<Double, Double>? = null
    private var alertRadiusM = 30
    private var pathTrace = false
    private var repeatAlertOn = false

    // The repeat alert of the live walk (docs/11 5.27.5): the others are loaded once per walk id, and the points that
    // arrive meanwhile wait and are replayed in order. The engine writes no setting and posts no notification.
    private var alertWalkId = 0L
    private var alert: RepeatAlert? = null
    private var alertPending = mutableListOf<TracePoint>()
    private val houseAlertedAt = mutableMapOf<String, Long>()
    private val streetAlertedAt = mutableMapOf<String, Long>()
    private var lastGeocodeAt = 0L
    private var lastGeocodeLat = 0.0
    private var lastGeocodeLon = 0.0
    private var currentStreet: String? = null
    private var stayPromptVisitId: String? = null
    private var stationaryMode: Boolean? = null
    private var lastBatteryCheckAt = 0L
    private var collectors: List<Job> = emptyList()

    /** Hunt mode is on: the houses and settings are followed, walking-rate fixes are asked for, the state says so. */
    fun start() {
        collectors.forEach { it.cancel() }
        lastFix = null
        collectors = listOf(
            scope.launch {
                data.houses.collect {
                    houses = it
                    // A house saved or changed after the fix (or a list that arrives after it: the platform's first fix
                    // can beat the database's first answer) is named without waiting for the next fix, which a phone
                    // standing still may not send for a minute. The iOS launch check hung on this (PR 78).
                    lastFix?.let { (lat, lon) -> checkNearbyHouses(lat, lon) }
                }
            },
            scope.launch {
                data.tracking.collect {
                    alertRadiusM = it.alertRadiusM
                    stays.minStayMs = it.minStayMinutes * 60_000L
                    pathTrace = it.pathTrace
                    repeatAlertOn = it.pathTrace && it.repeatAlert
                    if (!repeatAlertOn) dropAlert()
                }
            },
            // The retention limit, once per start: the trace never outlives 30 days, on or off.
            scope.launch { data.pruneTrack(before = now() - Repository.TRACK_KEPT_MS) },
            // A saved walk never outlives its house: Hunt start is one of the sweep's triggers (docs/11 5.27.6).
            scope.launch { data.sweepWalksOfDeletedHouses() },
        )
        track.reset()
        dropAlert()
        stationaryMode = null
        requestUpdates(stationary = false)
        HuntState.update { it.copy(active = true, startedAt = now(), stopReason = null, walkId = 0) }
    }

    /**
     * Hunt mode ended, by the user (a null [reason]) or by itself: everything resets except why it stopped, which
     * the Map shows until it is closed.
     */
    fun stopped(reason: HuntState.StopReason?) {
        collectors.forEach { it.cancel() }
        collectors = emptyList()
        dropAlert()
        HuntState.update { HuntState.State(stopReason = reason) }
    }

    /**
     * *Finish walk* (docs/11 5.27.6): the walk ends and the next kept point begins a new walk id. Hunt mode keeps
     * running. The app then asks about the walk through [walkToAskAbout].
     */
    fun finishWalk() {
        track.finishWalk()
        dropAlert()
        HuntState.update { it.copy(walkId = 0) }
    }

    /** The id of the walk being recorded now; 0 before its first kept point. */
    val liveWalkId: Long get() = track.walkId

    /**
     * The walk to ask *Save this walk?* about (the newest ended walk above the watermark with 5 points and 100 m), or
     * null. Computed, not stored, so a walk cut by process death is asked about too (docs/11 5.27.6).
     */
    suspend fun walkToAskAbout(): Long? = data.lastEndedWalk(track.walkId)

    private fun dropAlert() {
        alertWalkId = 0
        alert = null
        alertPending = mutableListOf()
    }

    /** Feeds a kept point of walk [walkId] to the alert; the others of a new walk id are loaded first. */
    private fun feedAlert(walkId: Long, point: TracePoint) {
        if (walkId != alertWalkId) {
            dropAlert()
            alertWalkId = walkId
            alertPending.add(point)
            scope.launch {
                val loaded = RepeatAlert(data.walksOtherThan(walkId))
                if (alertWalkId != walkId) return@launch // the walk ended while the others were loading
                alert = loaded
                val waiting = alertPending
                alertPending = mutableListOf()
                for (p in waiting) ring(loaded.onPoint(p))
            }
            return
        }
        val a = alert
        if (a == null) alertPending.add(point) else ring(a.onPoint(point))
    }

    private fun ring(runM: Double?) {
        if (runM != null) effects.alertRepeat(runM.roundToInt())
    }

    /** A GPS fix: [accuracyM] as the platform reports it, [time] the fix's own time (epoch milliseconds). */
    fun onFix(lat: Double, lon: Double, accuracyM: Float, time: Long) {
        HuntState.update { it.copy(lat = lat, lon = lon, accuracyM = accuracyM, lastFixAt = now()) }
        if (stopIfBatteryLow()) return
        // Readings this rough can't tell one house from the next.
        if (accuracyM > HuntState.MAX_ACCURACY_M) return
        if (pathTrace && track.accept(lat, lon, time)) {
            val walkId = track.walkId
            HuntState.update { it.copy(walkId = walkId) }
            scope.launch {
                data.saveTrackPoint(TrackPointEntity(at = time, lat = lat, lon = lon, accuracyM = accuracyM, walkId = walkId))
            }
            if (repeatAlertOn) feedAlert(walkId, TracePoint(lat, lon, time, walkId))
        }
        lastFix = lat to lon
        checkNearbyHouses(lat, lon)
        checkStreet(lat, lon)
        checkStay(lat, lon, time)
        requestUpdates(stationary = stays.isStaying)
    }

    /** Same request replaced, so the platform asks for fewer fixes while staying and more while walking. */
    private fun requestUpdates(stationary: Boolean) {
        if (stationaryMode == stationary) return
        stationaryMode = stationary
        effects.requestUpdates(stationary)
    }

    private fun stopIfBatteryLow(): Boolean {
        val at = now()
        if (at - lastBatteryCheckAt < BATTERY_CHECK_MS) return false
        lastBatteryCheckAt = at
        val level = battery.read() ?: return false
        if (level.percent in 1..LOW_BATTERY_PERCENT && !level.charging) {
            effects.lowBattery(level.percent)
            effects.stop(HuntState.StopReason.LOW_BATTERY)
            return true
        }
        return false
    }

    private fun checkNearbyHouses(lat: Double, lon: Double) {
        val at = now()
        // A house whose spot is only approximate (FR-068) is never the nearest: its marker is a ring, not a place.
        val nearest = houses
            .filter { it.locationSource != LocationSource.APPROX }
            .map { it to Geo.distanceM(lat, lon, it.lat, it.lon) }
            .minByOrNull { it.second }
        val close = nearest?.takeIf { it.second <= NEAREST_SHOWN_M }
        HuntState.update { it.copy(nearestHouse = close?.first, nearestDistanceM = close?.second) }
        val (house, distance) = nearest ?: return
        if (distance > alertRadiusM) return
        val last = houseAlertedAt[house.id] ?: 0
        if (at - last < HOUSE_ALERT_AGAIN_MS) return
        houseAlertedAt[house.id] = at
        effects.alertHouse(house, distance.toInt())
    }

    private fun checkStreet(lat: Double, lon: Double) {
        val at = now()
        val moved = Geo.distanceM(lastGeocodeLat, lastGeocodeLon, lat, lon)
        if (at - lastGeocodeAt < GEOCODE_AGAIN_MS && moved < GEOCODE_AGAIN_M) return
        lastGeocodeAt = at
        lastGeocodeLat = lat
        lastGeocodeLon = lon
        scope.launch {
            // Offline or DNS failure: the lookup returns null and street alerts simply pause (docs/09 L3).
            val street = streets.streetAt(lat, lon) ?: return@launch
            if (StreetAlerts.sameStreet(street, currentStreet)) return@launch
            currentStreet = street
            val info = data.streetInfo(street)
            HuntState.update { it.copy(street = street, streetHouses = info.houses, streetVisits = info.visits) }
            val key = StreetAlerts.key(street)
            if (!StreetAlerts.shouldAlert(info.houses, info.visits, streetAlertedAt[key], at)) return@launch
            streetAlertedAt[key] = at
            effects.alertStreet(street, info.houses, info.visits, info.firstVisit)
        }
    }

    private fun checkStay(lat: Double, lon: Double, time: Long) {
        when (val event = stays.onLocation(lat, lon, time)) {
            is StayDetector.Event.Started -> onStayStarted(event)
            is StayDetector.Event.Ended -> onStayEnded(event)
            null -> Unit
        }
        HuntState.update { it.copy(staying = stays.isStaying) }
    }

    private fun houseAt(lat: Double, lon: Double) = houses
        .map { it to Geo.distanceM(lat, lon, it.lat, it.lon) }
        .filter { it.second <= HOUSE_AT_M }
        .minByOrNull { it.second }?.first

    private fun onStayStarted(e: StayDetector.Event.Started) {
        val visitId = newId()
        stayPromptVisitId = visitId
        val house = houseAt(e.lat, e.lon)
        scope.launch {
            val street = streets.streetAt(e.lat, e.lon)
            data.saveVisit(
                VisitEntity(
                    id = visitId, houseId = house?.id, lat = e.lat, lon = e.lon, street = street,
                    arrivedAt = e.since, source = VisitSource.AUTO, updatedAt = now(),
                ),
            )
        }
        if (house == null) effects.alertStay(visitId, e.lat, e.lon)
    }

    private fun onStayEnded(e: StayDetector.Event.Ended) {
        val visitId = stayPromptVisitId ?: return
        stayPromptVisitId = null
        scope.launch {
            val visit = data.getVisit(visitId) ?: return@launch
            data.saveVisit(visit.copy(leftAt = e.leftAt, lat = e.lat, lon = e.lon))
        }
    }

    companion object {
        /** Below this, not charging, Hunt mode stops itself (docs/09 L1). */
        const val LOW_BATTERY_PERCENT = 15
        const val BATTERY_CHECK_MS = 120_000L
        /** The Hunt card names the nearest house within this. */
        const val NEAREST_SHOWN_M = 150.0
        const val HOUSE_ALERT_AGAIN_MS = 30 * 60_000L
        const val GEOCODE_AGAIN_MS = 45_000L
        const val GEOCODE_AGAIN_M = 80.0
        /** A stay this close to a saved house is a visit to it. */
        const val HOUSE_AT_M = 40.0

        @OptIn(ExperimentalUuidApi::class)
        private fun randomId(): String = Uuid.random().toString()
    }
}

/**
 * Thins the path trace (docs/11 5.27): a fix is kept when it is at least [minDistanceM] from the last kept one or
 * [minGapMs] after it, so a walk keeps its shape and a stay keeps one point every few minutes, not one every 15 s.
 */
class TrackRecorder(private val minDistanceM: Double = 20.0, private val minGapMs: Long = 5 * 60_000L) {
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastAt = Long.MIN_VALUE

    /**
     * The id of the walk being recorded (docs/11 5.27.2): the `at` of its first kept point, assigned at that point (not
     * at Hunt start, so a walk that never gets a fix has no id and no row); 0 before it. A gap of
     * [TraceConstants.WALK_GAP_MS] or more before a kept point starts a new walk, as the splitter reads it.
     */
    var walkId: Long = 0
        private set

    fun reset() {
        lastAt = Long.MIN_VALUE
        walkId = 0
    }

    /** *Finish walk*: the next kept point begins a new walk id. */
    fun finishWalk() = reset()

    /** True when the fix at [time] is kept (and becomes the last kept one). */
    fun accept(lat: Double, lon: Double, time: Long): Boolean {
        val keep = lastAt == Long.MIN_VALUE ||
            time - lastAt >= minGapMs ||
            Geo.distanceM(lastLat, lastLon, lat, lon) >= minDistanceM
        if (keep) {
            if (lastAt == Long.MIN_VALUE || time - lastAt >= TraceConstants.WALK_GAP_MS) walkId = maxOf(time, 1L)
            lastLat = lat; lastLon = lon; lastAt = time
        }
        return keep
    }
}

/** What the engine reads and writes: the part of [Repository] Hunt mode uses ([HuntData.of] adapts it). */
interface HuntData {
    val houses: Flow<List<HouseEntity>>
    val tracking: Flow<HuntTracking>
    suspend fun streetInfo(street: String): Repository.StreetInfo
    suspend fun saveVisit(visit: VisitEntity)
    suspend fun getVisit(id: String): VisitEntity?
    suspend fun saveTrackPoint(point: TrackPointEntity)
    suspend fun pruneTrack(before: Long)

    /** The walks the alert compares the live walk with (the others, saved walks included); none by default. */
    suspend fun walksOtherThan(liveWalkId: Long): List<List<TracePoint>> = emptyList()

    /** The walk to ask about (docs/11 5.27.6), or null. */
    suspend fun lastEndedWalk(liveWalkId: Long): Long? = null

    /** Saved walks of deleted houses go (Hunt start is one trigger). */
    suspend fun sweepWalksOfDeletedHouses() {}

    companion object {
        fun of(repo: Repository): HuntData = object : HuntData {
            override val houses: Flow<List<HouseEntity>> get() = repo.houses
            override val tracking: Flow<HuntTracking> =
                repo.settings.settings.map { HuntTracking(it.alertRadiusM, it.minStayMinutes, it.pathTrace, it.repeatAlert) }
            override suspend fun streetInfo(street: String) = repo.streetInfo(street)
            override suspend fun saveVisit(visit: VisitEntity) = repo.saveVisit(visit)
            override suspend fun getVisit(id: String) = repo.getVisit(id)
            override suspend fun saveTrackPoint(point: TrackPointEntity) = repo.saveTrackPoint(point)
            override suspend fun pruneTrack(before: Long) = repo.pruneTrack(before)
            override suspend fun walksOtherThan(liveWalkId: Long) = repo.walksOtherThan(liveWalkId)
            override suspend fun lastEndedWalk(liveWalkId: Long) = repo.lastEndedWalk(liveWalkId)
            override suspend fun sweepWalksOfDeletedHouses() = repo.sweepWalksOfDeletedHouses()
        }
    }
}

/** The Hunt mode settings (Settings > Hunt mode): the alert radius, the minimum stay, and *Trace my path*. */
data class HuntTracking(
    val alertRadiusM: Int,
    val minStayMinutes: Int,
    val pathTrace: Boolean = false,
    /** *Warn me when I walk a path again* (docs/11 5.27.5); counts only while [pathTrace] is on. */
    val repeatAlert: Boolean = false,
)

/** The platform's reverse geocoder: the street at a point, or null when unknown or offline. */
fun interface StreetLookup {
    suspend fun streetAt(lat: Double, lon: Double): String?
}

/** The battery, when the platform can read it. */
data class BatteryLevel(val percent: Int, val charging: Boolean)

fun interface BatteryReader {
    fun read(): BatteryLevel?
}

/** What the platform does for the engine: the alerts (worded and posted there) and the location request rate. */
interface HuntEffects {
    /** You are within the alert radius of [house], [distanceM] away. */
    fun alertHouse(house: HouseEntity, distanceM: Int)

    /** You entered [street], which has [houses] saved houses and [visits] visits, the first on [firstVisit]. */
    fun alertStreet(street: String, houses: Int, visits: Int, firstVisit: Long?)

    /** You stayed at ([lat], [lon]) with no saved house near: the visit [visitId] is saved; offer to add a house. */
    fun alertStay(visitId: String, lat: Double, lon: Double)

    /** Ask for fixes at the staying rate (fewer) or the walking rate. */
    fun requestUpdates(stationary: Boolean)

    /** The battery is at [percent] and not charging: say so, then [stop] follows. */
    fun lowBattery(percent: Int)

    /**
     * The live walk has followed paths of earlier walks for [runM] metres (docs/11 5.27.5): the platform posts the
     * notification (Android channel `repeat_path`; iPhone category `repeat-path`). The engine decides when; the
     * words, sound and permission are the platform's. Nothing by default, so an adapter that has not learned it yet
     * stays silent.
     */
    fun alertRepeat(runM: Int) {}

    /** Hunt mode must end for [reason]; the platform stops its service and then calls [HuntEngine.stopped]. */
    fun stop(reason: HuntState.StopReason)
}
