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
import app.doorprints.data.RecordEntity
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.data.create
import app.doorprints.data.withImmediateTransaction
import app.doorprints.shared.api.ApiClient
import app.doorprints.shared.api.ApiHttp
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.records.RecordLimitException
import app.doorprints.shared.records.RecordRules
import app.doorprints.shared.records.RecordType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * The record envelope on the phone (docs/11 5.30 item 2, ADR-28, slice 0): the `records` table and its DAO, the
 * repository's typed reads and writes with their caps, and the sync's push and pull with the record cursor, on the
 * app's own database (Robolectric) against a fake server (Ktor's MockEngine). No record type ships yet, so a
 * test-only one stands in.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code) and its own WorkManager.
@Config(sdk = [35], application = Application::class)
class RecordsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: AndroidRepository

    @Serializable
    private data class Pin(val name: String, val lat: Double = 0.0)

    private val pins = RecordType("pin", Pin.serializer())

    /** The key, in memory, made at run time (no key-like literal in the source). */
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

    /** The fake server: what `GET /api/records` answers, and every request it saw with each PUT's body. */
    private class Server {
        var records = "[]"
        var putVersion = 300L
        val seen = mutableListOf<String>()
        val putBodies = mutableListOf<String>()

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val query = request.url.encodedQuery
            val method = request.method.value
            seen += if (query.isEmpty()) "$method $path" else "$method $path?$query"
            val body = when {
                path == "/api/stats" -> "{\"houses\":0,\"shortlisted\":0,\"rejected\":0,\"visits\":0,\"streets\":0,\"maxSyncVersion\":1000}"
                method == "PUT" -> {
                    val sent = request.body.toByteArray().decodeToString()
                    putBodies += sent
                    sent.replace(Regex(",?\"syncVersion\":\\d+"), "").dropLast(1) + ",\"syncVersion\":$putVersion}"
                }
                path == "/api/records" -> records
                else -> "[]"
            }
            respond(body, HttpStatusCode.OK, Headers.build { append("Content-Type", "application/json") })
        }
    }

    private val server = Server()
    private val at = 1_760_000_000_000

    @Before
    fun setUp(): Unit = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        context.deleteDatabase(DatabaseFile.NAME)
        db = AppDatabase.create(context)
        settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "records-test.preferences_pb") },
            MemorySecrets,
        )
        settings.saveServer("https://sync.example", "test-" + UUID.randomUUID())
        repo = AndroidRepository(context, db, settings) { url, key ->
            ApiClient(url, key, ApiHttp.client(server.engine), callTimeoutMs = null)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
        File(context.filesDir, "records-test.preferences_pb").delete()
    }

    // ---- The DAO ----

    @Test
    fun theDaoKeysByTypeAndIdListsLiveRowsAndCleansOnlyThePushedVersion(): Unit = runBlocking {
        val dao = db.records()
        dao.upsert(RecordEntity("pin", "a", "{\"name\":\"A\"}", updatedAt = 1))
        dao.upsert(RecordEntity("pin", "b", "{}", updatedAt = 2, deleted = true))
        dao.upsert(RecordEntity("other", "a", "{\"name\":\"O\"}", updatedAt = 3))
        assertEquals(listOf("a"), dao.listByType("pin").map { it.id })
        assertEquals(listOf("a"), dao.byType("pin").first().map { it.id })
        assertEquals(1, dao.countLive("pin"))
        assertEquals("{\"name\":\"O\"}", dao.get("other", "a")!!.payload)
        assertEquals(3, dao.dirty().size)
        assertEquals(listOf("other|a", "pin|a", "pin|b"), dao.all().map { "${it.type}|${it.id}" })

        // Clean only while the row is still the one that was pushed.
        dao.markClean("pin", "a", updatedAt = 9)
        assertTrue(dao.get("pin", "a")!!.dirty)
        dao.markClean("pin", "a", updatedAt = 1)
        assertFalse(dao.get("pin", "a")!!.dirty)
        dao.markAllDirty()
        assertEquals(3, dao.dirty().size)

        dao.deleteAll()
        assertTrue(dao.all().isEmpty())
    }

    // ---- The repository ----

    @Test
    fun savedRecordsAreObservedTypedAndAnUnreadableRowIsSkipped(): Unit = runBlocking {
        repo.saveRecord(pins, "p1", Pin("Home", 12.97))
        repo.saveRecord(pins, "p2", Pin("Office"))
        // A row from a newer app whose shape this one cannot read: left out of the list, no crash.
        db.records().upsert(RecordEntity("pin", "p3", "{\"label\":\"x\"}", updatedAt = 1))
        assertEquals(listOf("p1" to Pin("Home", 12.97), "p2" to Pin("Office")), repo.observeRecords(pins).first())
        val row = db.records().get("pin", "p1")!!
        assertTrue(row.dirty)
        assertTrue(row.updatedAt >= at)

        repo.deleteRecord(pins, "p1")
        assertEquals(listOf("p2" to Pin("Office")), repo.observeRecords(pins).first())
        val tombstone = db.records().get("pin", "p1")!!
        assertTrue(tombstone.deleted)
        assertEquals("{}", tombstone.payload)
        assertTrue(tombstone.updatedAt >= row.updatedAt)
        // Deleting again, or a record that never was, changes nothing.
        repo.deleteRecord(pins, "p1")
        repo.deleteRecord(pins, "nothing")
        assertEquals(tombstone, db.records().get("pin", "p1"))
        assertNull(db.records().get("pin", "nothing"))
    }

    @Test
    fun aBadIdOrAnOversizedPayloadIsRefusedBeforeAnythingIsWritten(): Unit = runBlocking {
        try {
            repo.saveRecord(pins, "a/b", Pin("x")); throw AssertionError("an id with a slash was written")
        } catch (_: IllegalArgumentException) {
        }
        try {
            repo.saveRecord(pins, "p1", Pin("x".repeat(RecordRules.MAX_PAYLOAD_BYTES))); throw AssertionError("an oversized payload was written")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(db.records().all().isEmpty())
    }

    @Test
    fun theTypeCapRefusesANewRowButNotAnEdit(): Unit = runBlocking {
        db.withImmediateTransaction {
            repeat(RecordRules.MAX_ROWS_PER_TYPE) { db.records().upsert(RecordEntity("pin", "r$it", "{\"name\":\"$it\"}", updatedAt = 1, dirty = false)) }
        }
        try {
            repo.saveRecord(pins, "one-more", Pin("x")); throw AssertionError("the cap did not hold")
        } catch (e: RecordLimitException) {
            assertEquals("pin", e.type)
            assertEquals(RecordRules.MAX_ROWS_PER_TYPE, e.max)
        }
        // An edit of a live row, and a row of another type, are not new rows of this type.
        repo.saveRecord(pins, "r7", Pin("edited"))
        repo.saveRecord(RecordType("other", Pin.serializer()), "x", Pin("x"))
        assertEquals(RecordRules.MAX_ROWS_PER_TYPE, db.records().countLive("pin"))
        assertEquals("edited", repo.observeRecords(pins).first().first { it.first == "r7" }.second.name)
    }

    // ---- The sync ----

    @Test
    fun dirtyRecordsArePushedAndMarkedCleanAndTombstonesGoAsEmptyObjects(): Unit = runBlocking {
        repo.saveRecord(pins, "p1", Pin("Home", 12.97))
        repo.saveRecord(pins, "p2", Pin("Gone"))
        repo.deleteRecord(pins, "p2")
        val outcome = repo.sync(photosAllowed = true)
        assertEquals(2, outcome.pushed)
        assertTrue(server.seen.contains("PUT /api/records/pin/p1"))
        assertTrue(server.seen.contains("PUT /api/records/pin/p2"))
        val sent = server.putBodies.first { it.contains("\"id\":\"p1\"") }
        assertTrue(sent, sent.contains("\"payload\":{\"name\":\"Home\",\"lat\":12.97}"))
        assertFalse(sent, sent.contains("\"deleted\":true"))
        // The wire leaves defaults out, as for houses: a tombstone carries no name, and `deleted` says so.
        val tombstone = server.putBodies.first { it.contains("\"id\":\"p2\"") }
        assertTrue(tombstone, tombstone.contains("\"deleted\":true") && !tombstone.contains("\"name\""))
        assertTrue(db.records().dirty().isEmpty())
        assertEquals(listOf("GET /api/houses?since=0", "GET /api/visits?since=0", "GET /api/records?since=0", "GET /api/photos?since=0"),
            server.seen.filter { it.contains("since=") })
    }

    @Test
    fun pulledRecordsLandWithTheCursorAndARowThisPhoneCannotUseStillMovesIt(): Unit = runBlocking {
        settings.saveCursors(house = 0, visit = 0, record = 10)
        // A clean local row is the server's to replace: only a dirty one edited later is kept (SyncRulesTest).
        db.records().upsert(RecordEntity("pin", "mine", "{\"name\":\"Mine\"}", updatedAt = at + 60_000, dirty = false))
        val t = IsoTime.format(at)
        server.records = """[
            {"type":"pin","id":"p1","payload":{"name":"Server","lat":1.5},"updatedAt":"$t","deleted":false,"syncVersion":11},
            {"type":"pin","id":"mine","payload":{"name":"Older"},"updatedAt":"$t","deleted":false,"syncVersion":12},
            {"type":"Bad Type","id":"p3","payload":{},"updatedAt":"$t","deleted":false,"syncVersion":13},
            {"type":"pin","id":"p4","payload":{},"updatedAt":"$t","deleted":true,"syncVersion":14}
        ]"""
        val outcome = repo.sync(photosAllowed = true)
        assertTrue(server.seen.contains("GET /api/records?since=10"))
        assertEquals(3, outcome.pulled)
        assertEquals(14L, settings.cursors().record)
        val p1 = db.records().get("pin", "p1")!!
        assertEquals("{\"name\":\"Server\",\"lat\":1.5}", p1.payload)
        assertEquals(at, p1.updatedAt)
        assertFalse(p1.dirty)
        assertEquals("{\"name\":\"Older\"}", db.records().get("pin", "mine")!!.payload)
        assertNull(db.records().get("Bad Type", "p3"))
        assertTrue(db.records().get("pin", "p4")!!.deleted)
        assertEquals(listOf("mine", "p1"), repo.observeRecords(pins).first().map { it.first })
        assertTrue(server.seen.none { it.startsWith("PUT ") })
    }

    @Test
    fun aServerFoundBehindThisPhoneGetsEveryRecordAgain(): Unit = runBlocking {
        db.records().upsert(RecordEntity("pin", "p1", "{\"name\":\"Synced\"}", updatedAt = at, dirty = false))
        settings.saveCursors(house = 0, visit = 0, record = 2000) // Above the server's maxSyncVersion of 1000.
        val outcome = repo.sync(photosAllowed = true)
        assertTrue(outcome.remoteReset)
        assertTrue(server.seen.contains("PUT /api/records/pin/p1"))
        assertTrue(server.seen.contains("GET /api/records?since=0"))
        assertEquals(0L, settings.cursors().record)
    }
}
