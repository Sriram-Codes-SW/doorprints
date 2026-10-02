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
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.doorprints.data.AndroidRepository
import app.doorprints.data.AppDatabase
import app.doorprints.data.DatabaseFile
import app.doorprints.data.HouseEntity
import app.doorprints.data.PhotoEntity
import app.doorprints.data.RecordEntity
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.data.SyncBackend
import app.doorprints.data.VisitEntity
import app.doorprints.data.create
import app.doorprints.shared.api.ApiException
import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.api.PhotoChangeDto
import app.doorprints.shared.api.PhotoMetaDto
import app.doorprints.shared.api.RecordDto
import app.doorprints.shared.api.VisitDto
import app.doorprints.shared.sync.MergeRule
import app.doorprints.shared.sync.SyncOutcome
import app.doorprints.shared.sync.SyncRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * S4b-BL-70: `CommonRepository.sync` drives whatever [SyncBackend] it is given, not only the server. A fake backend
 * (no server configured at all) gets this phone's dirty rows in the push order, is asked for changes since the stored
 * cursors, decides the merge with its own [MergeRule], is asked "is it behind", sees photos only when they are
 * allowed, and its failures reach [SyncOutcome.fromError] with nothing marked clean that it did not take. Run on the
 * app's own database (Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code) and its own WorkManager.
@Config(sdk = [35], application = Application::class)
class SyncBackendSeamTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore

    private object MemorySecrets : SecretStore {
        private val key = stringPreferencesKey("testKey")
        override fun get(settings: Preferences): String? = settings[key]
        override fun put(settings: MutablePreferences, apiKey: String) {
            settings[key] = apiKey
        }
        override fun clear(settings: MutablePreferences) {
            settings.remove(key)
        }
    }

    /** An in-memory remote: records every call in order and answers with what a test set up. */
    private class FakeSyncBackend(override val mergeRule: MergeRule = SyncRules.serverMerge) : SyncBackend {
        val calls = mutableListOf<String>()
        var behind = false
        var houses: List<HouseDto> = emptyList()
        var visits: List<VisitDto> = emptyList()
        var records: List<RecordDto> = emptyList()
        var photoChanges: List<PhotoChangeDto> = emptyList()
        val uploaded = mutableMapOf<String, ByteArray>()

        /** Thrown by the call whose name starts with this (e.g. "pushHouse"), once [failure] is set. */
        var failOn: String? = null
        var failure: Throwable? = null

        /** The position a pushed row is kept at: above every cursor, as a healthy remote's is. */
        private var next = 1000L

        private fun call(name: String) {
            calls += name
            val f = failure
            if (f != null && failOn != null && name.startsWith(failOn!!)) throw f
        }

        override suspend fun isBehind(cursors: List<Long>): Boolean = behind.also { call("isBehind $cursors") }
        override suspend fun pushHouse(house: HouseDto) = house.copy(syncVersion = next++).also { call("pushHouse ${house.id}") }
        override suspend fun pushVisit(visit: VisitDto) = visit.copy(syncVersion = next++).also { call("pushVisit ${visit.id}") }
        override suspend fun pushRecord(record: RecordDto) = record.copy(syncVersion = next++).also { call("pushRecord ${record.id}") }
        override suspend fun deletePhoto(photoId: String) = call("deletePhoto $photoId")
        override suspend fun uploadPhoto(houseId: String, photoId: String, fileName: String, size: Long, open: () -> Source) {
            call("uploadPhoto $photoId")
            uploaded[photoId] = open().use { it.readByteArray() }
        }
        override suspend fun pushPhotoMeta(photoId: String, meta: PhotoMetaDto): PhotoChangeDto? {
            call("pushPhotoMeta $photoId")
            return null
        }
        override suspend fun housesSince(cursor: Long) = houses.also { call("housesSince $cursor") }
        override suspend fun visitsSince(cursor: Long) = visits.also { call("visitsSince $cursor") }
        override suspend fun recordsSince(cursor: Long) = records.also { call("recordsSince $cursor") }
        override suspend fun photoChangesSince(cursor: Long) = photoChanges.also { call("photoChangesSince $cursor") }
        override suspend fun downloadPhoto(photoId: String): ByteArray {
            call("downloadPhoto $photoId")
            return byteArrayOf(7, 8, 9)
        }
    }

    private val at = 1_760_000_000_000
    private val h1 = "11111111-1111-4111-8111-111111111111"
    private val h2 = "22222222-2222-4222-8222-222222222222"
    private val v1 = "33333333-3333-4333-8333-333333333333"
    private val v2 = "44444444-4444-4444-8444-444444444444"
    private val b1 = "55555555-5555-4555-8555-555555555555"
    private val b2 = "66666666-6666-4666-8666-666666666666"
    private val p1 = "77777777-7777-4777-8777-777777777777"
    private val p2 = "88888888-8888-4888-8888-888888888888"

    @Before
    fun setUp(): Unit = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        context.deleteDatabase(DatabaseFile.NAME)
        db = AppDatabase.create(context)
        settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "seam-test.preferences_pb") },
            MemorySecrets,
        )
        // No server is configured: the backend comes from the repository's backend choice alone.
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
        File(context.filesDir, "seam-test.preferences_pb").delete()
    }

    private fun repoWith(backend: SyncBackend?) = AndroidRepository(context, db, settings, syncBackendFor = { backend })

    private fun house(id: String, updatedAt: Long, dirty: Boolean, label: String = "Here") =
        HouseEntity(id = id, label = label, lat = 12.97, lon = 77.59, createdAt = at, updatedAt = updatedAt, dirty = dirty)

    @Test
    fun noBackendIsNotConfigured() = runBlocking {
        assertEquals(SyncOutcome.Kind.NOT_CONFIGURED, repoWith(null).sync(photosAllowed = true).kind)
    }

    @Test
    fun anyBackendGetsTheDirtyRowsThenIsPulledSinceTheStoredCursors() = runBlocking {
        db.houses().upsert(house(h1, at, dirty = true))
        db.houses().upsert(house(h2, at, dirty = false))
        db.visits().upsert(VisitEntity(id = v1, houseId = h1, lat = 12.97, lon = 77.59, arrivedAt = at, updatedAt = at, dirty = true))
        db.records().upsert(RecordEntity("broker", b1, """{"name":"Anita"}""", updatedAt = at, dirty = true))
        settings.saveCursors(house = 10, visit = 20, record = 30)
        settings.savePhotoCursor(40)
        val backend = FakeSyncBackend().apply {
            houses = listOf(HouseDto(id = "99999999-9999-4999-8999-999999999999", label = "From elsewhere", lat = 1.0, lon = 2.0,
                updatedAt = IsoTime.format(at), syncVersion = 15))
            visits = listOf(VisitDto(id = v2, lat = 1.0, lon = 2.0, arrivedAt = IsoTime.format(at), updatedAt = IsoTime.format(at), syncVersion = 25))
            records = listOf(RecordDto(type = "broker", id = b2, updatedAt = IsoTime.format(at), deleted = true, syncVersion = 35))
        }
        val outcome = repoWith(backend).sync(photosAllowed = true)

        assertEquals(
            listOf(
                "isBehind [10, 20, 40, 30]", "pushHouse $h1", "pushVisit $v1", "pushRecord $b1",
                "housesSince 10", "visitsSince 20", "recordsSince 30", "photoChangesSince 40",
            ),
            backend.calls,
        )
        assertEquals(SyncOutcome(SyncOutcome.Kind.OK, pushed = 3, pulled = 3), outcome)
        assertTrue(db.houses().dirty().isEmpty())
        assertTrue(db.visits().dirty().isEmpty())
        assertTrue(db.records().dirty().isEmpty())
        assertEquals("From elsewhere", db.houses().get("99999999-9999-4999-8999-999999999999")?.label)
        assertTrue(db.records().get("broker", b2)!!.deleted) // the tombstone is stored, not dropped
        assertEquals(SettingsStore.Cursors(house = 15, visit = 25, photo = 40, record = 35), settings.cursors())
    }

    @Test
    fun aFirstSyncDoesNotAskWhetherTheRemoteIsBehind() = runBlocking {
        val backend = FakeSyncBackend()
        repoWith(backend).sync(photosAllowed = true)
        assertFalse(backend.calls.any { it.startsWith("isBehind") })
        assertEquals("housesSince 0", backend.calls.first())
    }

    /** A pulled row older than a clean local one: the backend's merge rule decides, not a rule the loop hard-wires. */
    private fun pullOlderRowOverCleanLocal(rule: MergeRule): HouseEntity = runBlocking {
        db.houses().upsert(house(h1, updatedAt = at + 5_000, dirty = false, label = "Newer here"))
        val backend = FakeSyncBackend(rule).apply {
            houses = listOf(HouseDto(id = h1, label = "Older snapshot", lat = 1.0, lon = 2.0, updatedAt = IsoTime.format(at), syncVersion = 1))
        }
        repoWith(backend).sync(photosAllowed = true)
        db.houses().get(h1)!!
    }

    @Test
    fun theServersRuleLetsTheRemoteOverwriteACleanRow() {
        assertEquals("Older snapshot", pullOlderRowOverCleanLocal(SyncRules.serverMerge).label)
    }

    @Test
    fun aBackendCanSupplyItsOwnMergeRule() {
        // Last write wins whether or not the local row is dirty (the shape of Drive's rule, docs/15 §5.1).
        val lastWriteWins = MergeRule { local, incoming -> local != null && local.updatedAt >= incoming.updatedAt }
        assertEquals("Newer here", pullOlderRowOverCleanLocal(lastWriteWins).label)
    }

    @Test
    fun aRemoteBehindGetsEverythingAgainAndIsPulledFromZero() = runBlocking {
        db.houses().upsert(house(h1, at, dirty = false))
        settings.saveCursors(house = 250, visit = 240, record = 235)
        settings.savePhotoCursor(230)
        val backend = FakeSyncBackend().apply { behind = true }
        val outcome = repoWith(backend).sync(photosAllowed = true)
        assertTrue(outcome.serverReset)
        assertTrue(backend.calls.contains("pushHouse $h1"))
        assertEquals(
            listOf("housesSince 0", "visitsSince 0", "recordsSince 0", "photoChangesSince 0"),
            backend.calls.filter { it.contains("Since") },
        )
    }

    @Test
    fun aBackendFailureIsClassifiedAndLeavesTheRowDirty() = runBlocking {
        db.houses().upsert(house(h1, at, dirty = true))
        val backend = FakeSyncBackend().apply {
            failOn = "pushHouse"; failure = ApiException(ApiException.Kind.AUTH, 401)
        }
        val error = runCatching { repoWith(backend).sync(photosAllowed = true) }.exceptionOrNull()
        assertNotNull(error)
        assertEquals(SyncOutcome.Kind.AUTH, SyncOutcome.fromError(error!!).kind)
        assertEquals(listOf(h1), db.houses().dirty().map { it.id })
        assertFalse(backend.calls.any { it.contains("Since") })
    }

    @Test
    fun aLostConnectionWhilePullingIsANetworkFailureAndKeepsTheCursors() = runBlocking {
        db.houses().upsert(house(h1, at, dirty = true))
        settings.saveCursors(house = 10, visit = 20, record = 30)
        val backend = FakeSyncBackend().apply { failOn = "housesSince"; failure = IOException("offline") }
        try {
            repoWith(backend).sync(photosAllowed = true)
            fail("the sync should have thrown")
        } catch (e: IOException) {
            assertEquals(SyncOutcome.Kind.NETWORK, SyncOutcome.fromError(e).kind)
        }
        assertTrue(db.houses().dirty().isEmpty()) // the push went through before the pull failed
        assertEquals(SettingsStore.Cursors(house = 10, visit = 20, photo = 0, record = 30), settings.cursors())
    }

    @Test
    fun photosMoveOnlyWhenAllowed() = runBlocking {
        db.houses().upsert(house(h1, at, dirty = false))
        val repo = repoWith(null)
        val file = repo.photoFile(p1).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        db.photos().upsert(PhotoEntity(p1, h1, file.absolutePath, uploaded = false, createdAt = at))
        val backend = FakeSyncBackend().apply {
            photoChanges = listOf(PhotoChangeDto(id = p2, houseId = h1, syncVersion = 50))
        }
        val held = repoWith(backend).sync(photosAllowed = false)
        assertEquals(2, held.photosWaiting)
        assertFalse(backend.calls.any { it.startsWith("uploadPhoto") || it.startsWith("downloadPhoto") })
        assertEquals(0L, settings.cursors().photo)

        val sent = repoWith(backend).sync(photosAllowed = true)
        assertEquals(0, sent.photosWaiting)
        assertArrayEquals(byteArrayOf(1, 2, 3), backend.uploaded[p1])
        assertTrue(backend.calls.contains("downloadPhoto $p2"))
        assertTrue(db.photos().get(p1)!!.uploaded)
        assertNotNull(db.photos().get(p2))
        assertEquals(50L, settings.cursors().photo)
    }
}
