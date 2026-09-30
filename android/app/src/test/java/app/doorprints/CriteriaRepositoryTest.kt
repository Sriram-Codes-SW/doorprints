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
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.data.create
import app.doorprints.data.toBundle
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.BackupFormat
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.export.ImportPlan
import app.doorprints.shared.model.Criterion
import app.doorprints.shared.model.CriterionType
import app.doorprints.shared.model.Preference
import app.doorprints.shared.model.PreferenceType
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
import org.junit.Assert.assertNotNull
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
 * Criteria and the rating share on the phone (docs/11 5.4, slice 2) on the app's own database (Robolectric): only what
 * differs from the defaults is stored, *Reset to defaults*, the cap of 40, custom keys that never clash, a custom
 * criterion deleted only while unused, the effective scoring every screen reads, and a `/2` backup's lists merging by key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CriteriaRepositoryTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: AndroidRepository
    private val at = 1_760_000_000_000

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
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "criteria-test.preferences_pb") },
            MemorySecrets,
        )
        repo = AndroidRepository(context, db, settings)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
        File(context.filesDir, "criteria-test.preferences_pb").delete()
    }

    private suspend fun liveKeys(type: String) = db.records().listByType(type).map { it.id }

    @Test
    fun savingACriterionStoresOnlyWhatDiffersFromTheDefaults(): Unit = runBlocking {
        assertEquals(emptyList<String>(), liveKeys(CriterionType.name))
        repo.saveCriterion(Criterion("water", weight = 3, mustHave = true, minScore = 4, sort = 0))
        assertEquals(listOf("water"), liveKeys(CriterionType.name))
        assertEquals("""{"weight":3,"mustHave":true,"minScore":4,"sort":0}""", db.records().get(CriterionType.name, "water")!!.payload)
        val scoring = repo.observeScoring().first()
        assertEquals(3, scoring["water"]!!.weight)
        assertTrue(scoring["water"]!!.mustHave)

        // Saving the same again writes nothing (the stamp stays); back at its default the record goes.
        val stamp = db.records().get(CriterionType.name, "water")!!.updatedAt
        repo.saveCriterion(Criterion("water", weight = 3, mustHave = true, minScore = 4, sort = 0))
        assertEquals(stamp, db.records().get(CriterionType.name, "water")!!.updatedAt)
        repo.saveCriterion(Criterion.default("water"))
        assertEquals(emptyList<String>(), liveKeys(CriterionType.name))
        assertTrue(db.records().get(CriterionType.name, "water")!!.deleted)
        assertEquals(2, repo.scoring()["water"]!!.weight)
    }

    @Test
    fun movingRenumbersAndKeepsOnlyTheCriteriaThatLeftTheirPlace(): Unit = runBlocking {
        // Water and power swap: both leave their default place, the other eight stay without records.
        val order = repo.scoring().criteria.toMutableList()
        order[0] = order[1].also { order[1] = order[0] }
        repo.saveCriteria(order.mapIndexed { i, c -> c.copy(sort = i) })
        assertEquals(listOf("power", "water"), liveKeys(CriterionType.name))
        assertEquals(listOf("power", "water", "parking"), repo.scoring().criteria.take(3).map { it.key })
    }

    @Test
    fun resetDeletesEveryCriterionAndPreferenceRecordAndKeepsTheScores(): Unit = runBlocking {
        repo.saveHouse(HouseEntity(id = "h1", label = "A", lat = 1.0, lon = 1.0, checklist = mapOf("water" to 4), createdAt = at, updatedAt = at))
        repo.saveCriterion(Criterion("noise", weight = 0, sort = 5, archived = true))
        repo.addCriterion("Pets allowed")
        repo.saveRatingShare(0.25)
        assertEquals(0.25, repo.scoring().ratingShare, 0.0)
        repo.resetScoring()
        assertEquals(emptyList<String>(), liveKeys(CriterionType.name))
        assertEquals(emptyList<String>(), liveKeys(PreferenceType.name))
        assertEquals(10, repo.scoring().criteria.size)
        assertEquals(0.5, repo.scoring().ratingShare, 0.0)
        assertEquals(mapOf("water" to 4), repo.getHouse("h1")!!.checklist)
    }

    @Test
    fun theRatingShareIsARecordOnlyWhenItIsNotHalf(): Unit = runBlocking {
        repo.saveRatingShare(0.75)
        assertEquals("""{"value":"0.75"}""", db.records().get(PreferenceType.name, Preference.RATING_SHARE)!!.payload)
        repo.saveRatingShare(0.0)
        assertEquals("""{"value":"0"}""", db.records().get(PreferenceType.name, Preference.RATING_SHARE)!!.payload)
        assertEquals(0.0, repo.observeScoring().first().ratingShare, 0.0)
        repo.saveRatingShare(0.5)
        assertEquals(emptyList<String>(), liveKeys(PreferenceType.name))
    }

    @Test
    fun aCustomCriterionGetsAFreshKeyAtTheEndAndTheFortyFirstIsRefused(): Unit = runBlocking {
        val key = repo.addCriterion("  Pets allowed ")
        assertTrue(key, Criterion.isCustomKey(key))
        val pets = repo.scoring()[key]!!
        assertEquals("Pets allowed", pets.label)
        assertEquals(2, pets.weight)
        assertEquals(10, pets.sort)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.addCriterion(" ") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.addCriterion("x".repeat(61)) } }
        // Up to 40 in all, built-ins included; the keys never clash, a deleted one's included.
        val keys = (1..29).map { repo.addCriterion("Custom $it") }
        assertEquals(40, repo.scoring().criteria.size)
        assertEquals(30, (keys + key).toSet().size)
        assertThrows(RecordLimitException::class.java) { runBlocking { repo.addCriterion("One too many") } }
        // Saving a criterion that is not there yet is refused at 40 too, while changing one that is there is not.
        assertThrows(RecordLimitException::class.java) {
            runBlocking { repo.saveCriterion(Criterion("c_ffffffff", "Another", sort = 50)) }
        }
        repo.saveCriterion(pets.copy(weight = 3))
        assertEquals(3, repo.scoring()[key]!!.weight)
    }

    @Test
    fun aCustomCriterionIsDeletedOnlyWhileNoHouseHasAScoreForItAndABuiltInNever(): Unit = runBlocking {
        val used = repo.addCriterion("Pets allowed")
        val unused = repo.addCriterion("Lift")
        repo.saveHouse(HouseEntity(id = "h1", label = "A", lat = 1.0, lon = 1.0, checklist = mapOf(used to 5), createdAt = at, updatedAt = at))
        assertFalse(repo.deleteCriterion(used))
        assertNotNull(repo.scoring()[used])
        assertTrue(repo.deleteCriterion(unused))
        assertNull(repo.scoring()[unused])
        assertFalse(repo.deleteCriterion("water"))
        assertEquals(10, repo.scoring().criteria.count { it.isBuiltIn })
    }

    @Test
    fun theScoringFollowsTheRecordsAndTheHousesScoreWithIt(): Unit = runBlocking {
        val house = HouseEntity(id = "h1", label = "A", lat = 1.0, lon = 1.0, rating = 4, checklist = mapOf("water" to 5, "parking" to 4), createdAt = at, updatedAt = at)
        assertEquals(4.25, house.score(repo.scoring())!!, 1e-9)
        repo.saveCriterion(Criterion("parking", mustHave = true, minScore = 5, sort = 2))
        assertEquals(listOf("parking"), house.scoreResult(repo.observeScoring().first()).failedMustHave)
    }

    @Test
    fun aBackupCarriesTheListsAndAnImportMergesThemByKeyWithLastWriteWins(): Unit = runBlocking {
        val none = BackupData.of(repo.localRows().toBundle(ExportOptions(exportedAtMillis = at)))
        assertEquals(BackupFormat.ID, none.format)

        repo.saveCriterion(Criterion("water", weight = 3, sort = 0))
        val pets = repo.addCriterion("Pets allowed")
        repo.saveRatingShare(0.4)
        val rows = repo.localRows()
        val data = BackupData.of(rows.toBundle(ExportOptions(exportedAtMillis = at)))
        assertEquals(BackupFormat.ID_2, data.format)
        assertEquals(setOf("water", pets), data.criterionRows.map { it.key }.toSet())
        assertEquals("0.4", data.preferenceRows.single().value)
        // Without contact details the lists stay: they are not contacts.
        val without = BackupData.of(rows.toBundle(ExportOptions(exportedAtMillis = at, includeContacts = false)))
        assertEquals(2, without.criterionRows.size)

        // A newer water in the file wins, an older pets does not, a new one is added; nothing here is deleted.
        val water = data.criterionRows.first { it.key == "water" }
        val file = data.copy(
            criteria = listOf(
                water.copy(weight = 1, updatedAt = Long.MAX_VALUE / 2),
                data.criterionRows.first { it.key == pets }.copy(label = "Old name", updatedAt = 1),
                water.copy(key = "c_0000abcd", label = "Lift", updatedAt = 5),
            ),
            preferences = listOf(data.preferenceRows.single().copy(value = "0.75", updatedAt = Long.MAX_VALUE / 2)),
        )
        val local = repo.localVersions()
        val actions = ImportPlan.plan(
            file, local.houses, local.visits, local.photoIds, emptySet(), ImportMode.MERGE, newId = { "x" },
            localCriteria = local.criteria, localPreferences = local.preferences,
        )
        val result = repo.applyImport(actions) { null }
        assertEquals(2, result.criteria)
        assertEquals(1, result.preferences)
        val scoring = repo.scoring()
        assertEquals(1, scoring["water"]!!.weight)
        assertEquals("Pets allowed", scoring[pets]!!.label)
        assertEquals("Lift", scoring["c_0000abcd"]!!.label)
        assertEquals(0.75, scoring.ratingShare, 0.0)
        assertTrue(db.records().get(CriterionType.name, "water")!!.dirty)
    }
}
