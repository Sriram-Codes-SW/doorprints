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

package app.doorprints.data

import android.app.Application
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.room.RoomDatabase
import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.api.PhotoChangeDto
import app.doorprints.shared.api.PhotoMetaDto
import app.doorprints.shared.api.RecordDto
import app.doorprints.shared.api.VisitDto
import app.doorprints.shared.sync.MergeRule
import app.doorprints.shared.sync.SyncRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.Source
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

/**
 * S4b-BL-167: the pull reads the local rows of one page of the remote's answer in chunks of
 * [500], not one query per pulled row. Seam: the real [CommonRepository] over a real Room
 * database whose SQL is recorded (Room's query callback), and a scripted [SyncBackend]. The expected counts are
 * written down here from the chunk size (500), not computed with the code under test; the merge expectations are the
 * last-write-wins rule of docs/11 (a dirty local row stays only if strictly newer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncPullBatchingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sql = mutableListOf<String>()
    private val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryCallback(RoomDatabase.QueryCallback { query, _ -> synchronized(sql) { sql += query } }, Executor { it.run() })
        .build()

    private object MemorySecrets : SecretStore {
        private val key = stringPreferencesKey("batchKey")
        override fun get(settings: Preferences): String? = settings[key]
        override fun put(settings: MutablePreferences, apiKey: String) {
            settings[key] = apiKey
        }
        override fun clear(settings: MutablePreferences) {
            settings.remove(key)
        }
    }

    private val settings by lazy {
        SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "batch.preferences_pb") }, MemorySecrets)
    }

    private val at = 1_760_000_000_000
    private val newer = at + 86_400_000

    /** A remote that answers the same page for every cursor and hands out versions above anything seen. */
    private inner class Remote : SyncBackend {
        var houses: List<HouseDto> = emptyList()
        var visits: List<VisitDto> = emptyList()
        var records: List<RecordDto> = emptyList()
        var photos: List<PhotoChangeDto> = emptyList()
        val downloaded = mutableListOf<String>()
        var onPushHouse: (HouseDto) -> Unit = {}
        private var version = 1_000_000L

        override val mergeRule: MergeRule = SyncRules.serverMerge
        override suspend fun isBehind(cursors: List<Long>) = false
        override suspend fun pushHouse(house: HouseDto): HouseDto { onPushHouse(house); return house.copy(syncVersion = ++version) }
        override suspend fun pushVisit(visit: VisitDto) = visit.copy(syncVersion = ++version)
        override suspend fun pushRecord(record: RecordDto) = record.copy(syncVersion = ++version)
        override suspend fun deletePhoto(photoId: String) {}
        override suspend fun uploadPhoto(houseId: String, photoId: String, fileName: String, size: Long, open: () -> Source) {}
        override suspend fun pushPhotoMeta(photoId: String, meta: PhotoMetaDto): PhotoChangeDto? = null
        override suspend fun housesSince(cursor: Long) = houses
        override suspend fun visitsSince(cursor: Long) = visits
        override suspend fun recordsSince(cursor: Long) = records
        override suspend fun photoChangesSince(cursor: Long) = photos
        override suspend fun downloadPhoto(photoId: String): ByteArray { downloaded += photoId; return byteArrayOf(1, 2, 3) }
    }

    private val remote = Remote()
    private val repo by lazy {
        CommonRepository(
            db, settings, photoDir = File(tmp.root, "photos").path, syncSoon = {},
            apiFor = { _, _ -> error("no server in this test") }, syncBackendFor = { remote },
        )
    }

    @After
    fun close() {
        db.close()
        scope.cancel()
    }

    private fun id(kind: Int, n: Int) = "0000000$kind-0000-4000-8000-" + n.toString().padStart(12, '0')
    private fun houseDto(n: Int, label: String, updated: Long = at) =
        HouseDto(id = id(1, n), label = label, lat = 13.0, lon = 80.0, updatedAt = IsoTime.format(updated), syncVersion = n + 1L)
    private fun visitDto(n: Int, houseN: Int) =
        VisitDto(id = id(2, n), houseId = id(1, houseN), lat = 13.0, lon = 80.0, arrivedAt = IsoTime.format(at), updatedAt = IsoTime.format(at), syncVersion = n + 1L)
    private fun recordDto(n: Int, name: String) =
        RecordDto(type = "broker", id = id(3, n), payload = JsonObject(mapOf("name" to JsonPrimitive(name))), updatedAt = IsoTime.format(at), syncVersion = n + 1L)
    private fun photoDto(n: Int, houseN: Int, deleted: Boolean = false) =
        PhotoChangeDto(id = id(4, n), houseId = id(1, houseN), deleted = deleted, syncVersion = n + 1L)

    /** Runs a sync and returns the SELECT statements it made (Room's own invalidation bookkeeping, one per write, left out). */
    private fun selectsOfSync(): List<String> {
        synchronized(sql) { sql.clear() }
        runBlocking { repo.sync(photosAllowed = true) }
        return synchronized(sql) { sql.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) && !it.contains("room_table_modification_log") } }
    }

    private fun List<String>.inQueries(table: String) = count { it.contains("FROM $table WHERE") && it.contains(" IN (") }

    private fun seedClean(count: Int) = runBlocking {
        for (n in 0 until count) {
            db.houses().upsert(HouseEntity(id = id(1, 50_000 + n), label = "bulk", lat = 13.0, lon = 80.0, createdAt = at, updatedAt = at, dirty = false))
        }
    }

    @Test
    fun theChunkIsUnderSqlitesVariableLimit() {
        assertEquals(500, CommonRepository.PULL_READ_CHUNK)
    }

    @Test
    fun aThousandAndOnePulledRowsCostTwoMoreQueriesPerKindThanOneRowAndNeverOnePerRow() {
        seedClean(1500) // local rows no page row touches: they must not be read
        remote.houses = listOf(houseDto(0, "one"))
        remote.visits = listOf(visitDto(0, 0))
        remote.records = listOf(recordDto(0, "one"))
        val one = selectsOfSync()

        val n = 2 * 500 + 1 // 3 chunks
        remote.houses = (10 until 10 + n).map { houseDto(it, "many") }
        remote.visits = (10 until 10 + n).map { visitDto(it, 0) }
        remote.records = (10 until 10 + n).map { recordDto(it, "many") }
        val many = selectsOfSync()

        assertEquals(3 * 2, many.size - one.size)
        assertEquals(3, many.inQueries("houses") - 0) // the page's own houses; the photo phase reads none for an empty page
        assertEquals(3, many.inQueries("visits"))
        assertEquals(3, many.inQueries("records"))
        // No query reads a row by its id alone: a point read per pulled row is what this ticket removes.
        assertTrue(many.none { it.contains("WHERE id = ?") || it.contains("AND id = ?") })
    }

    @Test
    fun splitsAtTheChunkEdge() {
        fun houseQueries(n: Int): Int {
            remote.houses = (20_000 until 20_000 + n).map { houseDto(it, "edge") }
            return selectsOfSync().inQueries("houses")
        }
        assertEquals(1, houseQueries(500))
        assertEquals(2, houseQueries(501))
        assertEquals(2, houseQueries(1000))
        assertEquals(3, houseQueries(1001))
    }

    @Test
    fun photoRowsAndTheHousesTheyNameAreReadInChunksToo() {
        remote.photos = (0 until 501).map { photoDto(it, 9000 + it) } // houses not here: all skipped
        val many = selectsOfSync()
        assertTrue(remote.downloaded.isEmpty())
        assertEquals(2, many.inQueries("photos"))
        assertEquals(2, many.inQueries("houses"))
    }

    @Test
    fun mergesExactlyAsBeforeServerWinsOverCleanAndAbsentRowsANewerDirtyLocalEditStaysAcrossChunkEdges() = runBlocking {
        val n = 2 * 500 + 3
        fun keptLocal(i: Int) = i % 7 == 0
        fun absent(i: Int) = i % 3 == 0 && !keptLocal(i)
        for (i in 0 until n) {
            when {
                absent(i) -> Unit
                keptLocal(i) -> db.houses().upsert(HouseEntity(id = id(1, i), label = "mine", lat = 13.0, lon = 80.0, createdAt = at, updatedAt = at - 1, dirty = true))
                else -> db.houses().upsert(HouseEntity(id = id(1, i), label = "old copy", lat = 13.0, lon = 80.0, createdAt = at, updatedAt = at - 1000, dirty = false))
            }
        }
        // The user edits the house again while its push is in flight: newer than the remote's row, still dirty.
        remote.onPushHouse = { h ->
            runBlocking {
                db.houses().upsert(HouseEntity(id = h.id, label = "mine", lat = 13.0, lon = 80.0, createdAt = at, updatedAt = newer, dirty = true))
            }
        }
        remote.houses = (0 until n).map { houseDto(it, "server") }
        remote.visits = (0 until n).map { visitDto(it, 0) }
        remote.records = (0 until n).map { recordDto(it, "server") }
        db.records().upsert(RecordEntity(type = "broker", id = id(3, 1), payload = "{\"name\":\"old\"}", updatedAt = at - 1000, dirty = false))

        repo.sync(photosAllowed = true)

        for (i in 0 until n) {
            assertEquals("house $i", if (keptLocal(i)) "mine" else "server", db.houses().get(id(1, i))?.label)
        }
        assertEquals(n, (0 until n).count { db.visits().get(id(2, it)) != null })
        assertTrue((0 until n).all { db.records().get("broker", id(3, it))?.payload?.contains("server") == true })
    }

    @Test
    fun photosATombstoneForgetsALocalPhotoAKnownHouseGetsTheDownloadAnUnknownHouseIsSkippedAcrossAChunkEdge() = runBlocking {
        db.houses().upsert(HouseEntity(id = id(1, 1), label = "here", lat = 13.0, lon = 80.0, createdAt = at, updatedAt = at, dirty = false))
        val file = File(tmp.root, "x.jpg").apply { writeBytes(byteArrayOf(9)) }
        db.photos().upsert(PhotoEntity(id(4, 1), id(1, 1), file.path, uploaded = true, createdAt = at))
        remote.photos = listOf(photoDto(1, 1, deleted = true), photoDto(2, 1), photoDto(3, 777)) +
            (0 until 500).map { photoDto(100 + it, 777) } + photoDto(900, 1)

        repo.sync(photosAllowed = true)

        assertNull(db.photos().get(id(4, 1)))
        assertEquals(listOf(id(4, 2), id(4, 900)), remote.downloaded.sorted())
        assertNull(db.photos().get(id(4, 3)))
        assertTrue(db.photos().get(id(4, 900)) != null)
    }
}
