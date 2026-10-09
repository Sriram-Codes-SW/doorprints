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
import app.doorprints.data.RecordEntity
import app.doorprints.data.toExport
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.data.create
import app.doorprints.data.toBundle
import app.doorprints.data.toDto
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.BackupFormat
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.export.ImportPlan
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaNote
import app.doorprints.shared.model.AreaNoteType
import app.doorprints.shared.model.AreaType
import app.doorprints.shared.model.Place
import app.doorprints.shared.model.PlaceType
import app.doorprints.shared.records.RecordLimitException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Hunting areas, my places and area notes on the phone (docs/11 slice 4a) on the app's own database (Robolectric):
 * saving (validated, trimmed, dirty, written only when changed), fresh ids that never clash with a tombstone, deleting,
 * the caps of 20 areas, 10 places and 200 notes, the lists' orders, the sync's mapping, and a `/2` backup's three lists
 * merging by id with the last write winning (a newer row brings back a tombstone).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AreasRepositoryTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: AndroidRepository
    private val at = 1_790_501_400_000

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

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        context.deleteDatabase(DatabaseFile.NAME)
        db = AppDatabase.create(context)
        settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "areas-test.preferences_pb") },
            MemorySecrets,
        )
        repo = AndroidRepository(context, db, settings)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
        File(context.filesDir, "areas-test.preferences_pb").delete()
    }

    private val adyar = Area("a_1f2e3d4c", "Adyar", 13.0067, 80.2574, 500)

    @Test
    fun savingAnAreaTrimsMarksDirtyAndWritesOnlyWhatChanged(): Unit = runBlocking {
        val id = repo.newAreaId()
        assertTrue(id, Regex("a_[0-9a-f]{8}").matches(id))
        repo.saveArea(adyar.copy(id = id, name = "  Adyar  ", enabled = false))
        val row = db.records().get(AreaType.name, id)!!
        assertTrue("a saved area is pushed on the next sync", row.dirty)
        assertEquals("""{"name":"Adyar","lat":13.0067,"lon":80.2574,"radiusM":500,"enabled":false}""", row.payload)
        Thread.sleep(2)
        repo.saveArea(adyar.copy(id = id, enabled = false))
        assertEquals(row.updatedAt, db.records().get(AreaType.name, id)!!.updatedAt)
        // The sync sends it as a record of type area.
        assertEquals("area", row.toDto().type)
        for (bad in listOf(adyar.copy(name = " "), adyar.copy(lat = 91.0), adyar.copy(radiusM = 150), adyar.copy(radiusM = 2100), adyar.copy(id = "a/b"))) {
            assertThrows(bad.toString(), IllegalArgumentException::class.java) { runBlocking { repo.saveArea(bad) } }
        }
        // A stored radius out of range reads as 500.
        db.records().upsert(RecordEntity(AreaType.name, "a_00000009", """{"name":"Old","lat":1.0,"lon":2.0,"radiusM":9000}""", at))
        assertEquals(500, repo.areas().first { it.id == "a_00000009" }.radiusM)
    }

    @Test
    fun theListsAreByNameAndNotesNewestFirstWithTheirStamp(): Unit = runBlocking {
        repo.saveArea(adyar.copy(id = "a_00000002", name = "velachery"))
        repo.saveArea(adyar.copy(id = "a_00000001", name = "Adyar"))
        assertEquals(listOf("Adyar", "velachery"), repo.areas().map { it.name })
        repo.savePlace(Place("p_00000002", "School", 13.0, 80.2))
        repo.savePlace(Place("p_00000001", "Office", 13.0827, 80.2707))
        assertEquals(listOf("Office", "School"), repo.places().map { it.name })
        repo.saveAreaNote(AreaNote("n_00000001", street = "  MG Road ", text = "  Floods  "))
        Thread.sleep(2)
        repo.saveAreaNote(AreaNote("n_00000002", areaId = "a_00000001", text = "Tanker"))
        val notes = repo.areaNotes()
        assertEquals(listOf("n_00000002", "n_00000001"), notes.map { it.id })
        assertEquals(listOf("MG Road", "Floods"), listOf(notes[1].street, notes[1].text))
        assertTrue(notes.all { it.updatedAt > 0 })
        assertEquals("""{"street":"MG Road","text":"Floods"}""", db.records().get(AreaNoteType.name, "n_00000001")!!.payload)
        assertEquals(notes, repo.observeAreaNotes().first())
        assertEquals(repo.places(), repo.observePlaces().first())
        assertEquals(repo.areas(), repo.observeAreas().first())
        // Neither or both targets, or a blank text, is refused.
        for (bad in listOf(AreaNote("n_1", text = "x"), AreaNote("n_1", areaId = "a_1", street = "MG Road", text = "x"), AreaNote("n_1", street = "MG", text = " "))) {
            assertThrows(bad.toString(), IllegalArgumentException::class.java) { runBlocking { repo.saveAreaNote(bad) } }
        }
    }

    @Test
    fun aNewIdNeverClashesWithATombstoneAndDeletingLeavesOne(): Unit = runBlocking {
        repo.saveArea(adyar)
        repo.savePlace(Place("p_00000001", "Office", 13.0, 80.0))
        repo.saveAreaNote(AreaNote("n_00000001", areaId = adyar.id, text = "Tanker"))
        repo.deleteArea(adyar.id)
        repo.deletePlace("p_00000001")
        repo.deleteAreaNote("n_00000001")
        assertEquals(listOf(emptyList<Any>(), emptyList(), emptyList()), listOf(repo.areas(), repo.places(), repo.areaNotes()))
        for ((type, id) in listOf(AreaType.name to adyar.id, PlaceType.name to "p_00000001", AreaNoteType.name to "n_00000001")) {
            val tomb = db.records().get(type, id)!!
            assertTrue(tomb.deleted && tomb.dirty)
        }
        repeat(20) {
            assertFalse(repo.newAreaId() == adyar.id)
            assertFalse(repo.newPlaceId() == "p_00000001")
            assertFalse(repo.newAreaNoteId() == "n_00000001")
        }
    }

    @Test
    fun theCapsAre20Areas10PlacesAnd200Notes(): Unit = runBlocking {
        for (i in 0 until Area.MAX_AREAS) repo.saveArea(adyar.copy(id = "a_" + i.toString(16).padStart(8, '0')))
        assertThrows(RecordLimitException::class.java) { runBlocking { repo.saveArea(adyar.copy(id = "a_ffffffff")) } }
        // Changing one that is there is not refused.
        repo.saveArea(adyar.copy(id = "a_00000000", name = "Changed"))
        assertEquals("Changed", repo.areas().first { it.id == "a_00000000" }.name)
        for (i in 0 until Place.MAX_PLACES) repo.savePlace(Place("p_" + i.toString(16).padStart(8, '0'), "P$i", 1.0, 1.0))
        assertThrows(RecordLimitException::class.java) { runBlocking { repo.savePlace(Place("p_ffffffff", "One more", 1.0, 1.0)) } }
        for (i in 0 until AreaNote.MAX_NOTES) {
            db.records().upsert(RecordEntity(AreaNoteType.name, "n_" + i.toString(16).padStart(8, '0'), """{"street":"MG Road","text":"x"}""", at))
        }
        assertThrows(RecordLimitException::class.java) { runBlocking { repo.saveAreaNote(AreaNote("n_ffffffff", street = "MG Road", text = "y")) } }
        // A deleted one frees a place.
        repo.deleteArea("a_00000001")
        repo.saveArea(adyar.copy(id = "a_ffffffff"))
        assertEquals(Area.MAX_AREAS, repo.areas().size)
    }

    @Test
    fun aBackupCarriesTheThreeListsAndAnImportMergesThemByIdWithLastWriteWins(): Unit = runBlocking {
        repo.saveHouse(HouseEntity(id = "h1", label = "Green View", street = "MG Road", lat = 13.006, lon = 80.2574, createdAt = at, updatedAt = at))
        val none = BackupData.of(repo.localRows().toBundle(ExportOptions(exportedAtMillis = at)))
        assertEquals(BackupFormat.ID, none.format)
        repo.saveArea(adyar)
        repo.savePlace(Place("p_0a1b2c3d", "Office", 13.0827, 80.2707))
        repo.saveAreaNote(AreaNote("n_11223344", areaId = adyar.id, text = "Tanker"))
        repo.saveAreaNote(AreaNote("n_55667788", street = "MG Road", text = "Noisy"))
        // Kept in a copy without contact details, and the house page of the copy lists the notes and the distance.
        val bundle = repo.localRows().toBundle(ExportOptions(exportedAtMillis = at, includeContacts = false))
        val data = BackupData.of(bundle)
        assertEquals(BackupFormat.ID_2, data.format)
        assertEquals(listOf(1, 1, 2), listOf(data.areaRows.size, data.placeRows.size, data.areaNoteRows.size))
        val house = repo.getHouse("h1")!!.toExport()
        assertEquals(setOf("n_11223344", "n_55667788"), bundle.areaNotesOf(house).map { it.id }.toSet())
        assertEquals(listOf("8.6"), bundle.distancesOf(house).map { it.km })
        repo.deletePlace("p_0a1b2c3d")

        // A newer Adyar in the file wins; the deleted place (the tombstone is newer) stays deleted; a new note is added.
        val file = data.copy(
            areas = listOf(data.areaRows.single().copy(name = "Adyar (file)", updatedAt = Long.MAX_VALUE / 2)),
            areaNotes = data.areaNoteRows + data.areaNoteRows.first().copy(id = "n_0000abcd", updatedAt = 5),
        )
        val local = repo.localVersions()
        val actions = ImportPlan.plan(
            file, local.houses, local.visits, local.photoIds, emptySet(), ImportMode.MERGE, newId = { "x" },
            localAreas = local.areas, localPlaces = local.places, localAreaNotes = local.areaNotes,
        )
        val result = repo.applyImport(actions) { null }
        assertEquals(listOf(1, 0, 1), listOf(result.areas, result.places, result.areaNotes))
        assertEquals("Adyar (file)", repo.areas().single().name)
        assertEquals(emptyList<Place>(), repo.places())
        assertTrue(db.records().get(AreaNoteType.name, "n_0000abcd")!!.dirty)
        // A newer row in a file brings the deleted place back.
        val revive = file.copy(places = listOf(data.placeRows.single().copy(updatedAt = Long.MAX_VALUE / 2)))
        val again = repo.localVersions()
        repo.applyImport(
            ImportPlan.plan(revive, again.houses, again.visits, again.photoIds, emptySet(), ImportMode.MERGE, newId = { "y" }, localPlaces = again.places),
        ) { null }
        assertEquals(listOf("Office"), repo.places().map { it.name })
        // A copy import keeps the ids and merges the same way.
        val copy = ImportPlan.plan(revive, again.houses, again.visits, again.photoIds, emptySet(), ImportMode.COPY, newId = { java.util.UUID.randomUUID().toString() })
        assertEquals(listOf("p_0a1b2c3d"), copy.places.map { it.id })
    }

    @Test
    fun savingAPlaceTrimsMarksDirtyAndWritesOnlyWhatChanged(): Unit = runBlocking {
        repo.savePlace(Place("p_0a0a0a0a", "  Office  ", 13.0827, 80.2707))
        val row = db.records().get(PlaceType.name, "p_0a0a0a0a")!!
        assertTrue("a saved place is pushed on the next sync", row.dirty)
        assertEquals("""{"name":"Office","lat":13.0827,"lon":80.2707}""", row.payload)
        Thread.sleep(2)
        repo.savePlace(Place("p_0a0a0a0a", "Office", 13.0827, 80.2707))
        assertEquals(row.updatedAt, db.records().get(PlaceType.name, "p_0a0a0a0a")!!.updatedAt)
        Thread.sleep(2)
        repo.savePlace(Place("p_0a0a0a0a", "Office 2", 13.0827, 80.2707))
        assertTrue(db.records().get(PlaceType.name, "p_0a0a0a0a")!!.updatedAt > row.updatedAt)
        val longName = "x".repeat(Place.MAX_NAME + 1)
        for (bad in listOf(Place("p_1", " ", 1.0, 1.0), Place("p_1", "A", 91.0, 1.0), Place("p_1", "A", 1.0, 181.0), Place("p_1", longName, 1.0, 1.0), Place("p/1", "A", 1.0, 1.0))) {
            assertThrows(bad.toString(), IllegalArgumentException::class.java) { runBlocking { repo.savePlace(bad) } }
        }
    }

    @Test
    fun anUnchangedNoteKeepsItsStampAndABlankTargetCountsAsAbsent(): Unit = runBlocking {
        repo.saveAreaNote(AreaNote("n_00000001", street = "MG Road", text = "Noisy"))
        val row = db.records().get(AreaNoteType.name, "n_00000001")!!
        Thread.sleep(2)
        repo.saveAreaNote(AreaNote("n_00000001", street = "MG Road", text = "Noisy", updatedAt = 99))
        assertEquals(row.updatedAt, db.records().get(AreaNoteType.name, "n_00000001")!!.updatedAt)
        assertEquals(row.updatedAt, repo.areaNotes().single().updatedAt)
        Thread.sleep(2)
        repo.saveAreaNote(AreaNote("n_00000001", street = "MG Road", text = "Very noisy"))
        assertTrue(db.records().get(AreaNoteType.name, "n_00000001")!!.updatedAt > row.updatedAt)
        // A blank area id is no target, so the street stands alone; a padded one is trimmed.
        repo.saveAreaNote(AreaNote("n_00000002", areaId = "   ", street = "Lake Road", text = "Quiet"))
        assertEquals("""{"street":"Lake Road","text":"Quiet"}""", db.records().get(AreaNoteType.name, "n_00000002")!!.payload)
        repo.saveAreaNote(AreaNote("n_00000003", areaId = " a_00000001 ", street = " ", text = "Tanker"))
        assertEquals("""{"areaId":"a_00000001","text":"Tanker"}""", db.records().get(AreaNoteType.name, "n_00000003")!!.payload)
        val long = "x".repeat(AreaNote.MAX_TEXT + 1)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveAreaNote(AreaNote("n_00000004", street = "MG", text = long)) } }
    }

    @Test
    fun savingWhatIsAlreadyThereAtTheCapIsNotRefused(): Unit = runBlocking {
        for (i in 0 until Area.MAX_AREAS) repo.saveArea(adyar.copy(id = "a_" + i.toString(16).padStart(8, '0')))
        repo.saveArea(adyar.copy(id = "a_00000003"))
        for (i in 0 until Place.MAX_PLACES) repo.savePlace(Place("p_" + i.toString(16).padStart(8, '0'), "P$i", 1.0, 1.0))
        repo.savePlace(Place("p_00000003", "P3", 1.0, 1.0))
        for (i in 0 until AreaNote.MAX_NOTES) {
            db.records().upsert(RecordEntity(AreaNoteType.name, "n_" + i.toString(16).padStart(8, '0'), """{"street":"MG Road","text":"x"}""", at))
        }
        repo.saveAreaNote(AreaNote("n_00000003", street = "MG Road", text = "x"))
        repo.saveAreaNote(AreaNote("n_00000003", street = "MG Road", text = "changed"))
        assertEquals("changed", repo.areaNotes().first { it.id == "n_00000003" }.text)
        assertEquals(listOf(Area.MAX_AREAS, Place.MAX_PLACES, AreaNote.MAX_NOTES), listOf(repo.areas().size, repo.places().size, repo.areaNotes().size))
        // A deleted record's id coming back once the type is full again counts as a new live record.
        repo.deletePlace("p_00000003")
        repo.savePlace(Place("p_ffffffff", "New", 1.0, 1.0))
        assertThrows(RecordLimitException::class.java) { runBlocking { repo.savePlace(Place("p_00000003", "P3", 1.0, 1.0)) } }
    }

    @Test
    fun aRowThatCannotBeTrustedIsSkippedFromTheListsAndTheBackup(): Unit = runBlocking {
        repo.saveArea(adyar)
        repo.savePlace(Place("p_0a1b2c3d", "Office", 13.0827, 80.2707))
        repo.saveAreaNote(AreaNote("n_11223344", street = "MG Road", text = "Noisy"))
        db.records().upsert(RecordEntity(AreaType.name, "a_0000bad1", """{"name":"Far","lat":999.0,"lon":2.0}""", at))
        db.records().upsert(RecordEntity(AreaType.name, "a_0000bad2", "not json", at))
        db.records().upsert(RecordEntity(PlaceType.name, "p_0000bad1", """{"name":"Far","lat":1.0,"lon":999.0}""", at))
        db.records().upsert(RecordEntity(AreaNoteType.name, "n_0000bad1", """{"areaId":"a_1","street":"MG","text":"both"}""", at))
        assertEquals(listOf(adyar.id), repo.areas().map { it.id })
        assertEquals(listOf("p_0a1b2c3d"), repo.places().map { it.id })
        assertEquals(listOf("n_11223344"), repo.areaNotes().map { it.id })
        assertEquals(listOf(adyar.id), repo.observeAreas().first().map { it.id })
        val data = BackupData.of(repo.localRows().toBundle(ExportOptions(exportedAtMillis = at)))
        assertEquals(listOf(1, 1, 1), listOf(data.areaRows.size, data.placeRows.size, data.areaNoteRows.size))
        // A note's row carries its edit stamp into the list and the backup.
        assertEquals(db.records().get(AreaNoteType.name, "n_11223344")!!.updatedAt, data.areaNoteRows.single().updatedAt)
    }
}
