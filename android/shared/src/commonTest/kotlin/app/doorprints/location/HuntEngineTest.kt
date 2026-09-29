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
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.VisitSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hunt mode's rules in common code ([HuntEngine], Sprint 4b): the same behaviour `HuntService` had on Android, now
 * testable on the JVM and the iOS simulator with fakes for the platform (docs/06 TC-U-92).
 */
class HuntEngineTest {

    private class Data : HuntData {
        override val houses = MutableStateFlow<List<HouseEntity>>(emptyList())
        override val tracking = MutableStateFlow(HuntTracking(alertRadiusM = 30, minStayMinutes = 4))
        val streets = mutableMapOf<String, Repository.StreetInfo>()
        val visits = mutableMapOf<String, VisitEntity>()
        override suspend fun streetInfo(street: String) =
            streets[street] ?: Repository.StreetInfo(street, 0, 0, null)
        override suspend fun saveVisit(visit: VisitEntity) { visits[visit.id] = visit }
        override suspend fun getVisit(id: String) = visits[id]
        val track = mutableListOf<TrackPointEntity>()
        var prunedBefore: Long? = null
        override suspend fun saveTrackPoint(point: TrackPointEntity) { track += point }
        override suspend fun pruneTrack(before: Long) { prunedBefore = before }
    }

    private class Effects : HuntEffects {
        val houseAlerts = mutableListOf<Pair<String, Int>>()
        val streetAlerts = mutableListOf<String>()
        val stayAlerts = mutableListOf<String>()
        val requests = mutableListOf<Boolean>()
        val lowBattery = mutableListOf<Int>()
        val stops = mutableListOf<HuntState.StopReason>()
        override fun alertHouse(house: HouseEntity, distanceM: Int) { houseAlerts += house.id to distanceM }
        override fun alertStreet(street: String, houses: Int, visits: Int, firstVisit: Long?) { streetAlerts += street }
        override fun alertStay(visitId: String, lat: Double, lon: Double) { stayAlerts += visitId }
        override fun requestUpdates(stationary: Boolean) { requests += stationary }
        override fun lowBattery(percent: Int) { lowBattery += percent }
        override fun stop(reason: HuntState.StopReason) { stops += reason }
    }

    private val data = Data()
    private val effects = Effects()
    private var clock = 1_000_000_000L
    private var street: String? = null
    private var battery: BatteryLevel? = BatteryLevel(80, charging = false)
    private var ids = 0

    private fun house(id: String, lat: Double, lon: Double) = HouseEntity(
        id = id, label = id, lat = lat, lon = lon, status = HouseStatus.NEW, createdAt = 1, updatedAt = 1,
    )

    // Every test ends with stopped(): the engine's collectors never complete on their own, as in the app, where the
    // adapter's scope ends with Hunt mode.
    private fun TestScope.engine() = HuntEngine(
        data, { _, _ -> street }, { battery }, effects, this, now = { clock }, newId = { "visit-${++ids}" },
    )

    @BeforeTest fun reset() = HuntState.update { HuntState.State() }
    @AfterTest fun clear() = HuntState.update { HuntState.State() }

    @Test
    fun startingFollowsTheSettingsAsksForWalkingFixesAndSaysSo() = runTest(StandardTestDispatcher()) {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(listOf(false), effects.requests)
        assertTrue(HuntState.state.value.active)
        assertEquals(clock, HuntState.state.value.startedAt)
        e.stopped(null)
        assertFalse(HuntState.state.value.active)
        assertNull(HuntState.state.value.stopReason)
        e.stopped(null)
    }

    @Test
    fun aWeakFixIsShownButNeverAlerts() = runTest(StandardTestDispatcher()) {
        data.houses.value = listOf(house("a", 12.9716, 77.5946))
        val e = engine(); e.start(); advanceUntilIdle()
        e.onFix(12.9716, 77.5946, accuracyM = 51f, time = clock)
        assertEquals(12.9716, HuntState.state.value.lat)
        assertEquals(51f, HuntState.state.value.accuracyM)
        assertTrue(effects.houseAlerts.isEmpty())
        assertNull(HuntState.state.value.nearestHouse)
        e.stopped(null)
    }

    @Test
    fun passingASavedHouseAlertsOncePerHalfHour() = runTest(StandardTestDispatcher()) {
        data.houses.value = listOf(house("a", 12.9716, 77.5946), house("b", 13.0, 77.6))
        val e = engine(); e.start(); advanceUntilIdle()
        e.onFix(12.97165, 77.5946, 10f, clock)
        assertEquals(listOf("a" to 5), effects.houseAlerts.map { it.first to it.second })
        assertEquals("a", HuntState.state.value.nearestHouse?.id)
        clock += 10 * 60_000
        e.onFix(12.97165, 77.5946, 10f, clock)
        assertEquals(1, effects.houseAlerts.size, "not again within 30 minutes")
        clock += 21 * 60_000
        e.onFix(12.97165, 77.5946, 10f, clock)
        assertEquals(2, effects.houseAlerts.size)
        e.stopped(null)
    }

    @Test
    fun outsideTheAlertRadiusNothingIsSaidButTheNearestHouseIsShownWithin150m() = runTest(StandardTestDispatcher()) {
        data.houses.value = listOf(house("a", 12.9716, 77.5946))
        data.tracking.value = HuntTracking(alertRadiusM = 20, minStayMinutes = 4)
        val e = engine(); e.start(); advanceUntilIdle()
        // About 100 m north.
        e.onFix(12.9725, 77.5946, 10f, clock)
        assertTrue(effects.houseAlerts.isEmpty())
        assertEquals("a", HuntState.state.value.nearestHouse?.id)
        // About 1.1 km north: too far to show.
        e.onFix(12.9816, 77.5946, 10f, clock)
        assertNull(HuntState.state.value.nearestHouse)
        e.stopped(null)
    }

    @Test
    fun enteringAStreetWithHousesAlertsOnceAndTheCardShowsItsCounts() = runTest(StandardTestDispatcher()) {
        street = "MG Road"
        data.streets["MG Road"] = Repository.StreetInfo("MG Road", houses = 2, visits = 3, firstVisit = 5L)
        val e = engine(); e.start(); advanceUntilIdle()
        e.onFix(12.97, 77.59, 10f, clock); advanceUntilIdle()
        assertEquals(listOf("MG Road"), effects.streetAlerts)
        assertEquals(2, HuntState.state.value.streetHouses)
        assertEquals(3, HuntState.state.value.streetVisits)
        // The same street again within 45 s and 80 m: no second lookup, no second alert.
        clock += 10_000
        e.onFix(12.97001, 77.59, 10f, clock); advanceUntilIdle()
        assertEquals(1, effects.streetAlerts.size)
        e.stopped(null)
    }

    @Test
    fun aStreetLookupThatFailsPausesStreetAlerts() = runTest(StandardTestDispatcher()) {
        street = null
        val e = engine(); e.start(); advanceUntilIdle()
        e.onFix(12.97, 77.59, 10f, clock); advanceUntilIdle()
        assertTrue(effects.streetAlerts.isEmpty())
        assertNull(HuntState.state.value.street)
        e.stopped(null)
    }

    @Test
    fun stayingFourMinutesSavesAVisitOffersAHouseAndSlowsTheFixes() = runTest(StandardTestDispatcher()) {
        street = "Lake Road"
        val e = engine(); e.start(); advanceUntilIdle()
        var t = clock
        repeat(6) {
            e.onFix(12.97, 77.59, 10f, t)
            t += 60_000; clock = t
        }
        advanceUntilIdle()
        assertEquals(listOf("visit-1"), effects.stayAlerts, "no saved house within 40 m: offer to add one")
        val visit = data.visits.getValue("visit-1")
        assertEquals(VisitSource.AUTO, visit.source)
        assertEquals("Lake Road", visit.street)
        assertNull(visit.houseId)
        assertTrue(HuntState.state.value.staying)
        assertEquals(listOf(false, true), effects.requests, "walking, then the staying rate")
        // Leaving: the visit gets its end, fixes go back to the walking rate.
        e.onFix(12.99, 77.62, 10f, t); advanceUntilIdle()
        assertEquals(t - 60_000, data.visits.getValue("visit-1").leftAt, "the last fix inside is when you left")
        assertFalse(HuntState.state.value.staying)
        assertEquals(listOf(false, true, false), effects.requests)
        e.stopped(null)
    }

    @Test
    fun stayingAtASavedHouseIsAVisitToItWithoutAnOffer() = runTest(StandardTestDispatcher()) {
        data.houses.value = listOf(house("a", 12.97, 77.59))
        val e = engine(); e.start(); advanceUntilIdle()
        var t = clock
        repeat(6) { e.onFix(12.97, 77.59, 10f, t); t += 60_000; clock = t }
        advanceUntilIdle()
        assertTrue(effects.stayAlerts.isEmpty())
        assertEquals("a", data.visits.getValue("visit-1").houseId)
        e.stopped(null)
    }

    @Test
    fun aLowBatteryStopsHuntModeButOnlyCheckedEveryTwoMinutes() = runTest(StandardTestDispatcher()) {
        battery = BatteryLevel(14, charging = false)
        val e = engine(); e.start(); advanceUntilIdle()
        e.onFix(12.97, 77.59, 10f, clock)
        assertEquals(listOf(14), effects.lowBattery)
        assertEquals(listOf(HuntState.StopReason.LOW_BATTERY), effects.stops)
        // Charging at 14 %: keeps going.
        effects.stops.clear(); effects.lowBattery.clear()
        battery = BatteryLevel(14, charging = true)
        clock += HuntEngine.BATTERY_CHECK_MS
        e.onFix(12.97, 77.59, 10f, clock)
        assertTrue(effects.stops.isEmpty())
        // Not read again within two minutes, even when flat.
        battery = BatteryLevel(5, charging = false)
        clock += 1_000
        e.onFix(12.97, 77.59, 10f, clock)
        assertTrue(effects.stops.isEmpty())
        e.stopped(null)
    }

    @Test
    fun withTheTraceOffNoPointIsKeptButOldPointsStillGoAtStart() = runTest(StandardTestDispatcher()) {
        val e = engine(); e.start(); advanceUntilIdle()
        e.onFix(12.97, 77.59, 10f, clock); advanceUntilIdle()
        assertTrue(data.track.isEmpty())
        assertEquals(clock - Repository.TRACK_KEPT_MS, data.prunedBefore, "the 30-day limit runs at every start")
        e.stopped(null)
    }

    @Test
    fun withTheTraceOnGoodFixesAreKeptThinnedAndWeakOnesAreNot() = runTest(StandardTestDispatcher()) {
        data.tracking.value = HuntTracking(alertRadiusM = 30, minStayMinutes = 4, pathTrace = true)
        val e = engine(); e.start(); advanceUntilIdle()
        var t = clock
        e.onFix(12.9700, 77.5900, 10f, t)                       // kept: the first
        e.onFix(12.97005, 77.5900, 10f, t + 15_000)             // about 5 m on: thinned away
        e.onFix(12.9703, 77.5900, 10f, t + 30_000)              // about 33 m from the last kept: kept
        e.onFix(12.9703, 77.5900, 60f, t + 45_000)              // weak: never kept
        e.onFix(12.9703, 77.5900, 10f, t + 30_000 + 5 * 60_000) // same place, five minutes later: kept
        advanceUntilIdle()
        assertEquals(listOf(t, t + 30_000, t + 30_000 + 5 * 60_000), data.track.map { it.at })
        assertEquals(10f, data.track.first().accuracyM)
        e.stopped(null)
    }

    @Test
    fun stoppingByItselfKeepsTheReasonForTheMap() = runTest(StandardTestDispatcher()) {
        val e = engine(); e.start(); advanceUntilIdle()
        e.onFix(12.97, 77.59, 10f, clock)
        e.stopped(HuntState.StopReason.NO_PERMISSION)
        assertEquals(HuntState.State(stopReason = HuntState.StopReason.NO_PERMISSION), HuntState.state.value)
    }
}
