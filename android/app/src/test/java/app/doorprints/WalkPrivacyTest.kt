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
import app.doorprints.data.SavedWalkEntity
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.data.SyncBackend
import app.doorprints.data.TrackPointEntity
import app.doorprints.data.create
import app.doorprints.data.localTablesChanged
import app.doorprints.drive.wiring.RoomSyncRows
import app.doorprints.export.ExportBuilder
import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.api.PhotoChangeDto
import app.doorprints.shared.api.PhotoMetaDto
import app.doorprints.shared.api.RecordDto
import app.doorprints.shared.api.VisitDto
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.HtmlWriter
import app.doorprints.shared.export.MarkdownWriter
import app.doorprints.shared.export.XlsxWriter
import app.doorprints.shared.sync.MergeRule
import app.doorprints.shared.sync.SyncRules
import app.doorprints.shared.trace.TracePoint
import app.doorprints.shared.trace.WalkCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.Source
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * **A walk is in no export, backup or sync** (TC-U-151, docs/11 5.27.7a; threat model T-I30, PRV-028, PRV-030), by
 * behaviour: a database holding a trace and saved walks at marker coordinates and marker times, then every path that
 * builds a file or a payload from it: the six readable-copy and backup writers' input (`ExportBundle`, `BackupData`,
 * the HTML, Markdown and XLSX writers), the Drive sync rows (`RoomSyncRows`, which reads four DAOs and not the walk
 * tables), the server push (every call a sync makes) and the invalidation tracker that feeds `localRowsFlow`. The marker
 * is searched in the text; the saved walk's bytes are checked as well (in hex and base64). `WalkPrivacySourceTest` (in
 * `:shared`) is the other half: the source-level guard that no such file mentions the walk tables at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WalkPrivacyTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var repo: AndroidRepository
    private val pushes = mutableListOf<String>()

    private object NoSecrets : SecretStore {
        override fun get(settings: Preferences): String? = null
        override fun put(settings: MutablePreferences, apiKey: String) = Unit
        override fun clear(settings: MutablePreferences) = Unit
    }

    /** A remote that remembers everything a sync sends it. */
    private inner class Remote : SyncBackend {
        override val mergeRule: MergeRule = SyncRules.serverMerge
        override suspend fun isBehind(cursors: List<Long>) = false
        override suspend fun pushHouse(house: HouseDto) = house.also { pushes += it.toString() }
        override suspend fun pushVisit(visit: VisitDto) = visit.also { pushes += it.toString() }
        override suspend fun pushRecord(record: RecordDto) = record.also { pushes += it.toString() }
        override suspend fun deletePhoto(photoId: String) { pushes += photoId }
        override suspend fun uploadPhoto(houseId: String, photoId: String, fileName: String, size: Long, open: () -> Source) { pushes += photoId }
        override suspend fun pushPhotoMeta(photoId: String, meta: PhotoMetaDto): PhotoChangeDto? { pushes += meta.toString(); return null }
        override suspend fun housesSince(cursor: Long) = emptyList<HouseDto>()
        override suspend fun visitsSince(cursor: Long) = emptyList<VisitDto>()
        override suspend fun recordsSince(cursor: Long) = emptyList<RecordDto>()
        override suspend fun photoChangesSince(cursor: Long) = emptyList<PhotoChangeDto>()
        override suspend fun downloadPhoto(photoId: String) = ByteArray(0)
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        context.deleteDatabase(DatabaseFile.NAME)
        db = AppDatabase.create(context)
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "privacy.preferences_pb") }, NoSecrets)
        repo = AndroidRepository(context, db, settings, syncBackendFor = { Remote() })
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
    }

    private val markerLat = 11.314159
    private val markerLon = 77.271828
    private val markerAt = 1_700_123_456_789L
    private val walk = listOf(
        TracePoint(markerLat, markerLon, markerAt), TracePoint(markerLat + 0.001, markerLon, markerAt + 60_000),
        TracePoint(markerLat + 0.002, markerLon + 0.001, markerAt + 120_000),
    )

    private suspend fun seed() {
        db.houses().upsert(HouseEntity(id = "11111111-1111-4111-8111-111111111111", label = "Flat", lat = 12.97, lon = 77.59, createdAt = 1_760_000_000_000, updatedAt = 1_760_000_000_000, dirty = true))
        for (p in walk) db.track().insert(TrackPointEntity(at = p.atMs, lat = p.lat, lon = p.lon, accuracyM = 8f, walkId = markerAt))
        db.savedWalks().insert(
            SavedWalkEntity("saved-walk-marker", "11111111-1111-4111-8111-111111111111", markerAt, markerAt + 120_000, markerAt + 200_000, 3, 222, WalkCodec.encode(walk)),
        )
    }

    private fun markers(): List<String> {
        val bytes = WalkCodec.encode(walk)
        return listOf(
            "11.3141", "77.2718", markerAt.toString(), (markerAt + 60_000).toString(), "saved-walk-marker", "saved_walks", "track_points", "walkId",
            bytes.joinToString("") { "%02x".format(it) }, java.util.Base64.getEncoder().encodeToString(bytes),
        )
    }

    private fun assertClean(what: String, text: String) {
        for (m in markers()) assertFalse("$what contains the walk marker `$m`", text.contains(m))
    }

    @Test
    fun theReadableCopiesAndTheBackupBuiltFromARepositoryWithWalksHoldNoWalk() = runBlocking {
        seed()
        val rows = repo.localRows()
        assertClean("LocalRows", rows.toString())
        val bundle = ExportBuilder.build(rows, ExportBuilder.defaults(context))
        assertEquals("the house itself is in the copy, so the search is not vacuous", 1, bundle.houses.size)
        assertClean("ExportBundle", bundle.toString())
        assertClean("BackupData (the backup's data.json)", Json.encodeToString(BackupData.of(bundle)))
        assertClean("HTML copy", HtmlWriter.write(bundle))
        assertClean("Markdown copy", MarkdownWriter.write(bundle))
        assertClean("XLSX copy", XlsxWriter.parts(bundle).joinToString("\n") { it.path + it.xml })
    }

    @Test
    fun theDriveSyncRowsReadNoWalkTable() = runBlocking {
        seed()
        val sync = RoomSyncRows(db, { "0123456789abcdef0123456789abcdef" }) { null }.all()
        assertEquals("only the house: no walk row of any kind", 1, sync.size)
        assertClean("Drive sync rows", sync.joinToString("\n") { it.json.toString() + it.key })
    }

    @Test
    fun aServerSyncPushesNoWalk() = runBlocking {
        seed()
        repo.sync()
        assertTrue("the house was pushed, so the capture is not vacuous", pushes.any { it.contains("Flat") })
        assertClean("every push of a sync", pushes.joinToString("\n"))
    }

    @Test
    fun theInvalidationTrackerBehindLocalRowsFlowIgnoresTheWalkTables() = runBlocking {
        db.houses().upsert(HouseEntity(id = "h", label = "H", lat = 1.0, lon = 1.0, createdAt = 1, updatedAt = 1))
        val seen = mutableListOf<Set<String>>()
        val job = scope.launch { db.localTablesChanged().collect { seen += it } }
        delay(500)
        val before = seen.size
        db.track().insert(TrackPointEntity(at = 5, lat = 1.0, lon = 1.0, accuracyM = 5f, walkId = 5))
        db.savedWalks().insert(SavedWalkEntity("s", "h", 5, 6, 7, 0, 0, ByteArray(1)))
        delay(800)
        assertEquals("a walk write never re-reads the export's tables", before, seen.size)
        db.houses().upsert(HouseEntity(id = "h", label = "H2", lat = 1.0, lon = 1.0, createdAt = 1, updatedAt = 2))
        delay(800)
        assertTrue("(a house write does: the flow is alive)", seen.size > before)
        job.cancel()
    }
}
