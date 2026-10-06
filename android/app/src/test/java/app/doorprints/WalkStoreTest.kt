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

import android.app.Application
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.doorprints.data.AndroidRepository
import app.doorprints.data.AppDatabase
import app.doorprints.data.DatabaseFile
import app.doorprints.data.HouseEntity
import app.doorprints.data.SaveWalkResult
import app.doorprints.data.SavedWalkEntity
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.data.TrackPointEntity
import app.doorprints.data.WalkStore
import app.doorprints.data.create
import app.doorprints.data.localTablesChanged
import app.doorprints.shared.export.ImportActions
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.trace.TraceConstants
import app.doorprints.shared.trace.TracePoint
import app.doorprints.shared.trace.WalkCodec
import app.doorprints.shared.trace.WalkSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Saved walks and the walks read from the two stores, on the app's own Room database (TC-U-150, S4b-FR-14; docs/11
 * 5.27.6): the save moves a walk in one transaction (rolled back whole), the limits, the 30-day prune leaves saved walks,
 * a deleted house's walks are hidden at once and swept when final (every trigger named), the walk to ask about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WalkStoreTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var repo: AndroidRepository
    private var clock = 1_760_000_000_000L
    private var ids = 0
    private lateinit var store: WalkStore

    private object NoSecrets : SecretStore {
        override fun get(settings: Preferences): String? = null
        override fun put(settings: MutablePreferences, apiKey: String) = Unit
        override fun clear(settings: MutablePreferences) = Unit
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        context.deleteDatabase(DatabaseFile.NAME)
        db = AppDatabase.create(context)
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "walks.preferences_pb") }, NoSecrets)
        repo = AndroidRepository(context, db, settings)
        store = WalkStore(db, { clock }, { "w${++ids}" })
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
    }

    private fun house(id: String, deleted: Boolean = false) =
        HouseEntity(id = id, label = "House $id", lat = 12.97, lon = 77.59, createdAt = 1, updatedAt = 1, deleted = deleted)

    /** [count] points a step apart (about 22 m north each), the walk id and first time [id]. */
    private suspend fun trace(id: Long, count: Int, step: Double = 0.0002, startLat: Double = 12.9) {
        for (i in 0 until count) db.track().insert(TrackPointEntity(at = id + i * 20_000L, lat = startLat + i * step, lon = 77.5, accuracyM = 8f, walkId = id))
    }

    private suspend fun savedRow(id: String, houseId: String, startedAt: Long = clock) = db.savedWalks().insert(
        SavedWalkEntity(id, houseId, startedAt, startedAt + 1000, clock, 2, 100, WalkCodec.encode(listOf(TracePoint(12.9, 77.5, startedAt), TracePoint(12.901, 77.5, startedAt + 1000)))),
    )

    @Test
    fun savingMovesTheWalkIntoOneRowAndTheTraceKeepsNothingOfIt() = runBlocking {
        db.houses().upsert(house("h1"))
        trace(1000, 6); trace(900_000_000, 3) // another walk stays
        val saved = store.saveWalk("h1", 1000) as SaveWalkResult.Saved
        val row = db.savedWalks().get(saved.id)!!
        assertEquals(listOf(6, 1000L, 1000L + 5 * 20_000L, "h1"), listOf(row.pointCount, row.startedAt, row.endedAt, row.houseId))
        assertTrue("length is rounded metres of the polyline", row.lengthM in 100..130)
        assertEquals(0, db.track().ofWalk(1000).size)
        assertEquals(3, db.track().ofWalk(900_000_000).size)
        assertEquals(6, WalkCodec.decode(row.points, row.startedAt, row.pointCount)!!.size)
    }

    @Test
    fun aSaveThatFailsInTheMiddleLeavesTheWalkInTheTraceAndNoRow() = runBlocking {
        db.houses().upsert(house("h1"))
        trace(1000, 6)
        val failing = WalkStore(db, { clock }, { error("disk full") })
        try { failing.saveWalk("h1", 1000); throw AssertionError("should fail") } catch (e: IllegalStateException) { }
        assertEquals("no walk is in neither table", 6, db.track().ofWalk(1000).size)
        assertEquals(0, db.savedWalks().count())
    }

    @Test
    fun theLimitsRefuseAndChangeNothing() = runBlocking {
        db.houses().upsert(house("h1")); db.houses().upsert(house("h2"))
        trace(5000, TraceConstants.MAX_WALK_POINTS + 1, step = 0.00001)
        assertEquals(SaveWalkResult.TooLong, store.saveWalk("h1", 5000))
        assertEquals(TraceConstants.MAX_WALK_POINTS + 1, db.track().ofWalk(5000).size)
        trace(7000, TraceConstants.MAX_WALK_POINTS, step = 0.00001)
        assertTrue("exactly 5 000 points are saved", store.saveWalk("h1", 7000) is SaveWalkResult.Saved)

        repeat(TraceConstants.MAX_WALKS_PER_HOUSE - 1) { savedRow("f$it", "h1", 1_000L + it) }
        trace(9000, 3)
        assertEquals(SaveWalkResult.HouseFull, store.saveWalk("h1", 9000))
        assertEquals(3, db.track().ofWalk(9000).size)
        assertTrue("another house is not full", store.saveWalk("h2", 9000) is SaveWalkResult.Saved)

        repeat(TraceConstants.MAX_SAVED_WALKS - db.savedWalks().count()) { savedRow("d$it", "h2", 2_000L + it) }
        assertEquals(TraceConstants.MAX_SAVED_WALKS, db.savedWalks().count())
        trace(9500, 3)
        db.houses().upsert(house("h3"))
        assertEquals(SaveWalkResult.DeviceFull, store.saveWalk("h3", 9500))
        assertEquals(3, db.track().ofWalk(9500).size)
    }

    @Test
    fun noWalkNoHouseAndWalkIdZeroAreRefused() = runBlocking {
        db.houses().upsert(house("h1")); db.houses().upsert(house("gone", deleted = true))
        trace(1000, 3)
        db.track().insert(TrackPointEntity(at = 5, lat = 1.0, lon = 2.0, accuracyM = 5f)) // a row from before: walk id 0
        assertEquals(SaveWalkResult.NoSuchWalk, store.saveWalk("h1", 424242))
        assertEquals("0 is no id: never the whole old trace", SaveWalkResult.NoSuchWalk, store.saveWalk("h1", 0))
        assertEquals(SaveWalkResult.NoSuchHouse, store.saveWalk("nope", 1000))
        assertEquals(SaveWalkResult.NoSuchHouse, store.saveWalk("gone", 1000))
        assertEquals(1, db.track().ofWalk(0).size)
    }

    @Test
    fun theHouseListIsNewestFirstAndDeleteRemovesOneOrAll() = runBlocking {
        db.houses().upsert(house("h1"))
        savedRow("a", "h1", 100); savedRow("b", "h1", 300); savedRow("c", "h1", 200)
        assertEquals(listOf("b", "c", "a"), store.savedWalksOf("h1").first().map { it.id })
        assertEquals(3, store.savedWalkCount().first())
        store.deleteSavedWalk("c")
        assertEquals(listOf("b", "a"), store.savedWalksOf("h1").first().map { it.id })
        store.deleteAllSavedWalks()
        assertEquals(0, store.savedWalkCount().first())
    }

    @Test
    fun theThirtyDayPruneLeavesSavedWalksAndRemovesOldTrace() = runBlocking {
        db.houses().upsert(house("h1"))
        trace(1_000, 3); trace(clock, 3)
        savedRow("old", "h1", 1_000)
        repo.pruneTrack(clock - 30L * 24 * 3_600_000)
        assertEquals(0, db.track().ofWalk(1_000).size)
        assertEquals(3, db.track().ofWalk(clock).size)
        assertNotNull(db.savedWalks().get("old"))
        repo.clearTrack()
        assertNotNull("Clear the path clears the 30-day trace only", db.savedWalks().get("old"))
    }

    @Test
    fun aDeletedHousesWalksAreHiddenAtOnceComeBackWithUndoAndAreSweptWhenFinal() = runBlocking {
        db.houses().upsert(house("h1")); db.houses().upsert(house("h2"))
        savedRow("w1", "h1"); savedRow("w2", "h2", clock + 5)
        repo.deleteHouse("h1")
        assertEquals("hidden at once on the house page, in the count and on the Map", 0, store.savedWalksOf("h1").first().size)
        assertEquals(1, store.savedWalkCount().first())
        assertEquals("the Map and the detection skip it too", listOf(WalkSource.SAVED), store.walks(0).map { it.source })
        assertEquals(1, store.walks(0).size)
        assertNotNull("not deleted yet: Undo inside the window brings it back", db.savedWalks().get("w1"))
        repo.saveHouse(db.houses().get("h1")!!.copy(deleted = false))
        assertEquals(1, store.savedWalksOf("h1").first().size)
        repo.deleteHouse("h1")
        store.sweep()
        assertNull(db.savedWalks().get("w1"))
        assertNotNull(db.savedWalks().get("w2"))
    }

    @Test
    fun aSavedWalkWhoseHouseIsMissingIsSweptToo() = runBlocking {
        savedRow("orphan", "never-existed")
        store.sweep()
        assertNull(db.savedWalks().get("orphan"))
    }

    @Test
    fun theSweepFinishesEvenWhenTheScreenThatStartedItIsLeftAtOnce() = runBlocking {
        db.houses().upsert(house("h1", deleted = true)); savedRow("w1", "h1")
        val job = scope.launch {
            coroutineContext.job.cancel() // the screen is left right now, as offerDeletedHouseUndo's coroutine is
            store.sweep()
        }
        job.join()
        assertNull("NonCancellable: the sweep still ran", db.savedWalks().get("w1"))
    }

    @Test
    fun everyTriggerSweeps_syncEndImportEndAndCopyUndo() = runBlocking {
        db.houses().upsert(house("gone", deleted = true))
        suspend fun fresh() { db.savedWalks().deleteAll(); savedRow("w", "gone") }
        fresh(); repo.sync() // not configured: the pass ends at once, the sweep still runs (sync end)
        assertNull("sync end", db.savedWalks().get("w"))
        fresh()
        repo.applyImport(ImportActions(ImportMode.MERGE, emptyList(), emptyList(), emptyList(), emptyMap()), photoBytes = { null })
        assertNull("import end (an import that deletes a house)", db.savedWalks().get("w"))
        fresh(); repo.undoCopyImport(emptyMap(), emptyMap(), emptyList())
        assertNull("CopyUndo", db.savedWalks().get("w"))
        fresh(); repo.sweepWalksOfDeletedHouses()
        assertNull("the repository's own sweep (app start, the Undo snackbar)", db.savedWalks().get("w"))
    }

    @Test
    fun theWalkToAskAboutIsTheNewestEndedWalkOverTheWatermarkWithFivePointsAnd100Metres() = runBlocking {
        trace(1_000, 6)                       // 5 steps of about 22 m: 111 m: qualifies
        trace(2_000, 4, step = 0.001)         // 333 m but too few points
        trace(3_000, 6, step = 0.00002)       // 6 points but about 11 m: too short
        trace(4_000, 6)                       // qualifies, newer
        trace(5_000, 8)                       // the live walk
        db.track().insert(TrackPointEntity(at = 7, lat = 1.0, lon = 1.0, accuracyM = 5f)) // walk id 0: never asked
        assertEquals("the live walk is never the one to ask about", 4_000L, store.lastEndedWalk(askedUpTo = 0, liveWalkId = 5_000))
        assertNull("the watermark skips what was handled", store.lastEndedWalk(askedUpTo = 4_000, liveWalkId = 5_000))
        assertEquals("an unanswered older walk is skipped by a newer answer, not asked twice", 5_000L, store.lastEndedWalk(askedUpTo = 4_000, liveWalkId = 0))
        db.track().deleteWalk(4_000)
        assertEquals("short walks are only kept 30 days, never asked about", 1_000L, store.lastEndedWalk(askedUpTo = 0, liveWalkId = 5_000))
        repo.settings.saveWalkAskedUpTo(1_000)
        assertNull("through the repository the watermark is the setting", repo.lastEndedWalk(liveWalkId = 5_000))
    }

    @Test
    fun theWalksAreTheTraceSplitAndEverySavedWalkWhateverItsAgeWithoutADoubleCount() = runBlocking {
        db.houses().upsert(house("h1"))
        val since = clock - THIRTY_DAYS
        trace(clock - 10_000_000, 4)                     // a trace walk
        trace(clock - 5_000_000_000, 4)                  // older than 30 days: not in the 30-day read
        db.track().insert(TrackPointEntity(at = clock - 3_000_000, lat = 12.0, lon = 77.0, accuracyM = 5f, walkId = clock - 9_000_000)) // a lone point: no walk
        savedRow("old", "h1", 5_000)                     // saved long ago: still counts
        val cut = clock - 20_000_000
        trace(cut, 3); savedRow("cut", "h1", cut)        // a save cut between its two writes: counted once, as saved
        val walks = store.walks(since)
        assertEquals(1, walks.count { it.source == WalkSource.TRACE })
        assertEquals(setOf(5_000L, cut), walks.filter { it.source == WalkSource.SAVED }.map { it.points.first().atMs }.toSet())
        val live = clock - 1_000_000
        trace(live, 3)
        assertEquals(4, store.walksOtherThan(0, since).size)
        assertEquals("the live walk is left out of the alert's others", 3, store.walksOtherThan(live, since).size)
    }

    @Test
    fun walksChangedEmitsOnTheTraceAndTheSavedWalksButLocalTablesDoNot() = runBlocking {
        db.houses().upsert(house("h1"))
        val local = mutableListOf<Set<String>>()
        val job = scope.launch { db.localTablesChanged().collect { local += it } }
        store.changes().first() // the first emission, at once
        val changed = scope.launch { store.changes().collect { } }
        kotlinx.coroutines.delay(300)
        val before = local.size
        trace(1_000, 3); savedRow("x", "h1")
        val seen = withTimeoutOrNull(3_000) { store.changes().first(); true }
        kotlinx.coroutines.delay(500)
        assertEquals("the walk tables never wake the export's invalidation tracker", before, local.size)
        assertNotNull(seen)
        job.cancel(); changed.cancel()
    }

    private companion object {
        const val THIRTY_DAYS = 30L * 24 * 3_600_000
    }
}
