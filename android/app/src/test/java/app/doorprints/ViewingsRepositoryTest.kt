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
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.data.create
import app.doorprints.data.toBundle
import app.doorprints.data.toDto
import app.doorprints.data.toEntity
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.BackupFormat
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.export.ImportPlan
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.ViewingType
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Viewings on the phone (docs/11 5.8, slice 3b-1) on the app's own database (Robolectric): saving (coerced, dirty,
 * written only when changed), a fresh `v_` id that never clashes with a record here (a tombstone included), deleting,
 * the next viewing, marking one done with its visit, the cap of 5,000, a house delete that leaves its viewings, the
 * sync's mapping, the AI's text, and a `/2` backup's viewings with and without contact details merging by id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ViewingsRepositoryTest {
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
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "viewings-test.preferences_pb") },
            MemorySecrets,
        )
        repo = AndroidRepository(context, db, settings)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
        File(context.filesDir, "viewings-test.preferences_pb").delete()
    }

    private suspend fun house(id: String = "h1", label: String = "Green View 2BHK") =
        repo.saveHouse(HouseEntity(id = id, label = label, address = "12, MG Road", lat = 1.0, lon = 1.0, createdAt = at, updatedAt = at))

    private fun viewing(id: String, startsAt: Long = at, houseId: String = "h1") = Viewing(id = id, houseId = houseId, startsAt = startsAt)

    @Test
    fun savingCoercesMarksDirtyAndWritesOnlyWhatChanged(): Unit = runBlocking {
        house()
        val id = repo.newViewingId()
        assertTrue(id, Viewing.isAppId(id))
        repo.saveViewing(viewing(id).copy(durationMin = 999, withWhom = "  Ravi  ", notes = " "))
        val saved = repo.getViewing(id)!!
        assertEquals(listOf("30", "Ravi"), listOf(saved.durationMin.toString(), saved.withWhom))
        assertNull(saved.notes)
        val row = db.records().get(ViewingType.name, id)!!
        assertTrue("a saved viewing is pushed on the next sync", row.dirty)
        assertEquals(
            """{"houseId":"h1","startsAt":$at,"durationMin":30,"kind":"FIRST","status":"PLANNED","remindMin":60,"withWhom":"Ravi"}""",
            row.payload,
        )
        // The same values again: no write, the stamp stays.
        Thread.sleep(2)
        repo.saveViewing(saved)
        assertEquals(row.updatedAt, db.records().get(ViewingType.name, id)!!.updatedAt)
        // The sync sends it as a record of type viewing, and a pulled one reads back the same.
        val dto = row.toDto()
        assertEquals("viewing", dto.type)
        val pulled = dto.toEntity()!!
        assertEquals(listOf(ViewingType.name, id, row.payload), listOf(pulled.type, pulled.id, pulled.payload))
        // A blank house or no start is refused.
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveViewing(viewing(id, houseId = " ")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveViewing(viewing(id, startsAt = 0)) } }
    }

    @Test
    fun aNewIdNeverClashesWithATombstoneAndDeletingLeavesOne(): Unit = runBlocking {
        house()
        repo.saveViewing(viewing("v_00000001"))
        repo.deleteViewing("v_00000001")
        assertNull(repo.getViewing("v_00000001"))
        val tomb = db.records().get(ViewingType.name, "v_00000001")!!
        assertTrue(tomb.deleted && tomb.dirty)
        assertEquals(emptyList<Viewing>(), repo.viewings())
        repeat(20) { assertFalse(repo.newViewingId() == "v_00000001") }
    }

    @Test
    fun theListIsByStartTheNextIsTheEarliestPlannedAndMarkingDoneKeepsTheVisit(): Unit = runBlocking {
        house()
        house("h2", "Other")
        repo.saveViewing(viewing("v_00000003", at + 3_600_000))
        repo.saveViewing(viewing("v_00000002", at))
        repo.saveViewing(viewing("v_00000001", at - 3_600_000))
        repo.saveViewing(viewing("v_00000009", at, houseId = "h2"))
        assertEquals(listOf("v_00000001", "v_00000002", "v_00000009", "v_00000003"), repo.viewings().map { it.id })
        assertEquals(listOf("v_00000001", "v_00000002", "v_00000003"), repo.viewingsOf("h1").map { it.id })
        assertEquals("v_00000002", repo.nextViewing("h1", at)?.id)
        repo.markViewingDone("v_00000002", "visit-1")
        val done = repo.getViewing("v_00000002")!!
        assertEquals(listOf("DONE", "visit-1"), listOf(done.status, done.visitId))
        assertEquals("v_00000003", repo.nextViewing("h1", at)?.id)
        repo.markViewingDone("missing")
        assertEquals(repo.viewings(), repo.observeViewings().first())
        // A house delete leaves its viewings: the history shows them as a house that is gone.
        repo.deleteHouse("h1")
        assertEquals(3, repo.viewingsOf("h1").size)
    }

    @Test
    fun theFiveThousandAndFirstLiveViewingIsRefused(): Unit = runBlocking {
        for (i in 0 until Viewing.MAX_VIEWINGS) {
            db.records().upsert(RecordEntity(ViewingType.name, "v_" + i.toString(16).padStart(8, '0'), ViewingType.encode(viewing("")), at))
        }
        assertThrows(RecordLimitException::class.java) { runBlocking { repo.saveViewing(viewing("v_fffffff0")) } }
        // Changing one that is there is not refused.
        repo.saveViewing(viewing("v_00000000").copy(notes = "Changed"))
        assertEquals("Changed", repo.getViewing("v_00000000")!!.notes)
    }

    @Test
    fun theAiSeesTheViewingLinesWithoutWithWhom(): Unit = runBlocking {
        house()
        repo.saveViewing(viewing("v_00000001").copy(kind = "SECOND", withWhom = "Ravi Kumar", notes = "Bring a tape"))
        val text = app.doorprints.shared.ai.HouseDocuments.text(
            app.doorprints.shared.ai.AiHouse(
                id = "h1", label = "Green View 2BHK", lat = 1.0, lon = 1.0,
                viewings = repo.viewings().map { app.doorprints.shared.ai.AiViewing(it.id, it.startsAt, it.kind, it.status, it.notes) },
            ),
        )
        assertTrue(text, text.contains("Viewing: 2026-09-27 09:30 | SECOND | PLANNED | Notes: Bring a tape"))
        assertFalse(text.contains("Ravi"))
    }

    @Test
    fun aBackupCarriesTheViewingsAndAnImportMergesThemByIdWithLastWriteWins(): Unit = runBlocking {
        house()
        val none = BackupData.of(repo.localRows().toBundle(ExportOptions(exportedAtMillis = at)))
        assertEquals(BackupFormat.ID, none.format)
        repo.saveViewing(viewing("v_00000001").copy(withWhom = "Ravi"))
        repo.saveViewing(viewing("v_00000002", at + 1))
        val data = BackupData.of(repo.localRows().toBundle(ExportOptions(exportedAtMillis = at)))
        assertEquals(BackupFormat.ID_2, data.format)
        assertEquals(listOf("Ravi", null), data.viewingRows.map { it.withWhom })
        val without = BackupData.of(repo.localRows().toBundle(ExportOptions(exportedAtMillis = at, includeContacts = false)))
        assertEquals(listOf(null, null), without.viewingRows.map { it.withWhom })
        repo.deleteViewing("v_00000002")

        // A newer v1 in the file wins; the deleted v2 (the tombstone is newer) stays deleted; a new one is added.
        val v1 = data.viewingRows.first { it.id == "v_00000001" }
        val file = data.copy(
            viewings = listOf(
                v1.copy(notes = "From the file", updatedAt = Long.MAX_VALUE / 2),
                data.viewingRows.first { it.id == "v_00000002" },
                v1.copy(id = "v_0000abcd", updatedAt = 5),
            ),
        )
        val local = repo.localVersions()
        val actions = ImportPlan.plan(
            file, local.houses, local.visits, local.photoIds, emptySet(), ImportMode.MERGE, newId = { "x" },
            localViewings = local.viewings,
        )
        val result = repo.applyImport(actions) { null }
        assertEquals(2, result.viewings)
        val byId = repo.viewings().associateBy { it.id }
        assertEquals("From the file", byId.getValue("v_00000001").notes)
        assertEquals(setOf("v_00000001", "v_0000abcd"), byId.keys)
        assertTrue(db.records().get(ViewingType.name, "v_0000abcd")!!.dirty)
        // A newer row in a file brings the deleted one back.
        val revive = file.copy(viewings = listOf(data.viewingRows.first { it.id == "v_00000002" }.copy(updatedAt = Long.MAX_VALUE / 2)))
        val again = repo.localVersions()
        repo.applyImport(
            ImportPlan.plan(revive, again.houses, again.visits, again.photoIds, emptySet(), ImportMode.MERGE, newId = { "y" }, localViewings = again.viewings),
        ) { null }
        assertTrue("v_00000002" in repo.viewings().map { it.id })
    }
}
