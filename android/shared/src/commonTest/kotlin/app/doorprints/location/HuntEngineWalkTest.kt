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
import app.doorprints.shared.trace.TraceConstants
import app.doorprints.shared.trace.TracePoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * TC-U-148, the shared half (S4b-FR-14, docs/11 5.27.2, 5.27.5, 5.27.6): the recorder's walk ids, the engine's alert
 * hook through a fake [HuntEffects.alertRepeat], and the walk to ask about. The vector file's alert cases run in
 * `TraceVectorsTest` against `RepeatAlert` itself; here the engine is shown to feed it the kept points of one walk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HuntEngineWalkTest {
    private val m = 0.000008993216

    private class Data : HuntData {
        override val houses = MutableStateFlow<List<HouseEntity>>(emptyList())
        override val tracking = MutableStateFlow(HuntTracking(alertRadiusM = 30, minStayMinutes = 4, pathTrace = true, repeatAlert = true))
        val track = mutableListOf<TrackPointEntity>()
        var others: List<List<TracePoint>> = emptyList()
        var othersAskedFor = mutableListOf<Long>()
        var asked = mutableListOf<Long>()
        var swept = 0
        override suspend fun streetInfo(street: String) = Repository.StreetInfo(street, 0, 0, null)
        override suspend fun saveVisit(visit: VisitEntity) = Unit
        override suspend fun getVisit(id: String): VisitEntity? = null
        override suspend fun saveTrackPoint(point: TrackPointEntity) { track += point }
        override suspend fun pruneTrack(before: Long) = Unit
        override suspend fun walksOtherThan(liveWalkId: Long): List<List<TracePoint>> { othersAskedFor += liveWalkId; return others }
        override suspend fun lastEndedWalk(liveWalkId: Long): Long? { asked += liveWalkId; return 4242 }
        override suspend fun sweepWalksOfDeletedHouses() { swept++ }
    }

    private class Effects : HuntEffects {
        val repeats = mutableListOf<Int>()
        override fun alertHouse(house: HouseEntity, distanceM: Int) = Unit
        override fun alertStreet(street: String, houses: Int, visits: Int, firstVisit: Long?) = Unit
        override fun alertStay(visitId: String, lat: Double, lon: Double) = Unit
        override fun requestUpdates(stationary: Boolean) = Unit
        override fun lowBattery(percent: Int) = Unit
        override fun stop(reason: HuntState.StopReason) = Unit
        override fun alertRepeat(runM: Int) { repeats += runM }
    }

    private val data = Data()
    private val effects = Effects()
    private val clock = 1_760_000_000_000L

    private fun TestScope.engine() = HuntEngine(data, { _, _ -> null }, { null }, effects, this, now = { clock })

    @BeforeTest fun reset() = HuntState.update { HuntState.State() }
    @AfterTest fun clear() = HuntState.update { HuntState.State() }

    private fun street(northFromM: Double, count: Int, stepM: Double, east: Double, start: Long): List<Triple<Double, Double, Long>> =
        List(count) { Triple((northFromM + it * stepM) * m, east * m, start + it * 20_000L) }

    private fun yesterdaysStreet() = listOf(
        listOf(TracePoint(0.0, 0.0, clock - 86_400_000), TracePoint(1000 * m, 0.0, clock - 86_340_000)),
    )

    // --- the walk the Map must leave alone ----------------------------------------------------------------

    @Test
    fun theStateNamesTheLiveWalkSoTheMapDoesNotAskAboutIt() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(0L, HuntState.state.value.walkId, "no id before the first kept point")
        e.onFix(12.97, 77.59, 5f, 1_000)
        advanceUntilIdle()
        assertEquals(1_000L, HuntState.state.value.walkId)
        e.finishWalk()
        assertEquals(0L, HuntState.state.value.walkId, "Finish walk: the next kept point starts a new walk")
        e.onFix(12.9704, 77.59, 5f, 40_000)
        advanceUntilIdle()
        assertEquals(40_000L, HuntState.state.value.walkId)
        e.stopped(null)
        assertEquals(0L, HuntState.state.value.walkId)
    }

    // --- the recorder -------------------------------------------------------------------------------------

    @Test
    fun aWalkIdIsTheTimeOfTheFirstKeptPointAndStaysForTheWalk() {
        val r = TrackRecorder()
        assertEquals(0L, r.walkId, "no id before the first kept point")
        assertTrue(r.accept(12.97, 77.59, 1_000))
        assertEquals(1_000L, r.walkId)
        assertTrue(r.accept(12.9703, 77.59, 31_000))
        assertEquals(1_000L, r.walkId, "the same id within a walk")
    }

    @Test
    fun resetAndFinishWalkStartANewIdAtTheNextKeptPointAndAThirtyMinuteGapDoesToo() {
        val r = TrackRecorder()
        r.accept(12.97, 77.59, 1_000)
        r.finishWalk()
        assertEquals(0L, r.walkId)
        r.accept(12.97, 77.59, 5_000)
        assertEquals(5_000L, r.walkId)
        r.reset()
        r.accept(12.97, 77.59, 9_000)
        assertEquals(9_000L, r.walkId)
        // five-minute points on the spot keep one id; a gap of exactly 30 minutes is a new walk, 29:59.999 is not
        r.accept(12.97, 77.59, 9_000 + 5 * 60_000)
        assertEquals(9_000L, r.walkId)
        r.accept(12.97, 77.59, 9_000 + 5 * 60_000 + TraceConstants.WALK_GAP_MS - 1)
        assertEquals(9_000L, r.walkId)
        val t = 9_000 + 5 * 60_000 + TraceConstants.WALK_GAP_MS - 1 + TraceConstants.WALK_GAP_MS
        r.accept(12.97, 77.59, t)
        assertEquals(t, r.walkId)
    }

    @Test
    fun aDroppedFixDoesNotGiveAnId() {
        val r = TrackRecorder()
        r.accept(12.97, 77.59, 1_000)
        assertTrue(!r.accept(12.97001, 77.59, 2_000)) // thinned
        assertEquals(1_000L, r.walkId)
    }

    // --- the engine ---------------------------------------------------------------------------------------

    @Test
    fun storedPointsCarryTheWalkIdAndFinishWalkStartsANewOne() = runTest(StandardTestDispatcher()) {
        data.tracking.value = HuntTracking(30, 4, pathTrace = true)
        val e = engine(); e.start(); advanceUntilIdle()
        e.onFix(12.9700, 77.59, 10f, clock)
        e.onFix(12.9703, 77.59, 10f, clock + 30_000)
        e.finishWalk()
        e.onFix(12.9706, 77.59, 10f, clock + 60_000)
        advanceUntilIdle()
        assertEquals(listOf(clock, clock, clock + 60_000), data.track.map { it.walkId })
        assertEquals(clock + 60_000, e.liveWalkId)
        e.stopped(null)
    }

    @Test
    fun theAlertRingsOncePerRunThroughTheEffectAtTheFirstPointOver100Metres() = runTest(StandardTestDispatcher()) {
        data.others = yesterdaysStreet()
        val e = engine(); e.start(); advanceUntilIdle()
        for ((lat, lon, t) in street(0.0, 14, 24.0, 4.0, clock)) e.onFix(lat, lon, 10f, t)
        advanceUntilIdle()
        assertEquals(1, effects.repeats.size, "one run, one alert: ${effects.repeats}")
        assertTrue(effects.repeats.single() in 100..130)
        assertEquals(listOf(clock), data.othersAskedFor, "the others are loaded once per walk, without the live walk")
        e.stopped(null)
    }

    @Test
    fun theAlertWaitsForTheOthersAndThenReplaysTheKeptPointsInOrder() = runTest(StandardTestDispatcher()) {
        data.others = yesterdaysStreet()
        val e = engine(); e.start(); advanceUntilIdle()
        // all fixes arrive before the (suspending) load of the others has run
        for ((lat, lon, t) in street(0.0, 8, 24.0, 4.0, clock)) e.onFix(lat, lon, 10f, t)
        assertTrue(effects.repeats.isEmpty())
        advanceUntilIdle()
        assertEquals(1, effects.repeats.size)
        e.stopped(null)
    }

    @Test
    fun nothingRingsWithTheAlertOffTheTraceOffOrAWeakFix() = runTest(StandardTestDispatcher()) {
        data.others = yesterdaysStreet()
        val pts = street(0.0, 14, 24.0, 4.0, clock)
        data.tracking.value = HuntTracking(30, 4, pathTrace = true, repeatAlert = false)
        var e = engine(); e.start(); advanceUntilIdle()
        for ((lat, lon, t) in pts) e.onFix(lat, lon, 10f, t)
        advanceUntilIdle(); e.stopped(null)
        data.tracking.value = HuntTracking(30, 4, pathTrace = false, repeatAlert = true)
        e = engine(); e.start(); advanceUntilIdle()
        for ((lat, lon, t) in pts) e.onFix(lat, lon, 10f, t)
        advanceUntilIdle(); e.stopped(null)
        data.tracking.value = HuntTracking(30, 4, pathTrace = true, repeatAlert = true)
        e = engine(); e.start(); advanceUntilIdle()
        for ((lat, lon, t) in pts) e.onFix(lat, lon, 60f, t) // worse than the 50 m gate
        advanceUntilIdle(); e.stopped(null)
        assertTrue(effects.repeats.isEmpty(), "${effects.repeats}")
        assertTrue(data.track.isEmpty() || data.track.all { it.accuracyM <= 50f })
    }

    @Test
    fun theAlertStartsOverForANewWalkAfterFinishWalk() = runTest(StandardTestDispatcher()) {
        data.others = yesterdaysStreet()
        val e = engine(); e.start(); advanceUntilIdle()
        for ((lat, lon, t) in street(0.0, 8, 24.0, 4.0, clock)) e.onFix(lat, lon, 10f, t)
        advanceUntilIdle()
        e.finishWalk()
        for ((lat, lon, t) in street(0.0, 8, 24.0, 4.0, clock + 3_600_000)) e.onFix(lat, lon, 10f, t)
        advanceUntilIdle()
        assertEquals(2, effects.repeats.size, "a second walk is a second run")
        assertEquals(2, data.othersAskedFor.size)
        assertNotEquals(data.othersAskedFor[0], data.othersAskedFor[1])
        e.stopped(null)
    }

    @Test
    fun theWalkToAskAboutIsAskedForWithTheLiveWalkAndHuntStartSweeps() = runTest(StandardTestDispatcher()) {
        val e = engine(); e.start(); advanceUntilIdle()
        assertEquals(1, data.swept, "Hunt start is a trigger of the sweep")
        e.onFix(12.97, 77.59, 10f, clock)
        advanceUntilIdle()
        assertEquals(4242L, e.walkToAskAbout())
        assertEquals(listOf(clock), data.asked, "the live walk is never the one to ask about")
        e.stopped(null)
    }
}
