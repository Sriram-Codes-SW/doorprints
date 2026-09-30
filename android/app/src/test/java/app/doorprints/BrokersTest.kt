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
import app.doorprints.shared.model.Broker
import app.doorprints.shared.model.BrokerType
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Brokers on the phone (docs/11 5.25, slice 1b) on the app's own database (Robolectric): the broker `saveHouse` finds
 * or makes from a phone number, `saveBroker` rewriting the copies of its name and phone on its houses, `deleteBroker`
 * unlinking them, the once-only move of contacts into brokers with its duplicates, and a `/2` backup's brokers merging
 * by id.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code) and its own WorkManager.
@Config(sdk = [35], application = Application::class)
class BrokersTest {
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
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "brokers-test.preferences_pb") },
            MemorySecrets,
        )
        repo = AndroidRepository(context, db, settings)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
        File(context.filesDir, "brokers-test.preferences_pb").delete()
    }

    private fun house(id: String, name: String? = null, phone: String? = null, updatedAt: Long = at, brokerId: String? = null) =
        HouseEntity(
            id = id, label = "House $id", lat = 12.97, lon = 77.59, contactName = name, contactPhone = phone,
            brokerId = brokerId, createdAt = at, updatedAt = updatedAt,
        )

    // ---- ensureBroker, inside saveHouse ----

    @Test
    fun savingAHouseWithAPhoneMakesTheBrokerLinksItAndReusesItForTheSameNumber(): Unit = runBlocking {
        repo.saveHouse(house("h1", "Ravi", "+91 98400 11111"))
        val brokers = repo.observeBrokers().first()
        assertEquals(1, brokers.size)
        assertEquals(Broker(name = "Ravi", phone = "+91 98400 11111"), brokers[0].second)
        assertEquals(brokers[0].first, repo.getHouse("h1")!!.brokerId)

        // The same number written another way is the same broker, and the house takes the broker's name and phone.
        repo.saveHouse(house("h2", "R. Kumar", "098400-11111"))
        assertEquals(1, repo.observeBrokers().first().size)
        val second = repo.getHouse("h2")!!
        assertEquals(brokers[0].first, second.brokerId)
        assertEquals("Ravi", second.contactName)
        assertEquals("+91 98400 11111", second.contactPhone)
        assertTrue(second.dirty)

        // A different number is another broker, named by its number when the contact has no name.
        repo.saveHouse(house("h3", null, "99999 22222"))
        assertEquals(listOf("99999 22222", "Ravi"), repo.observeBrokers().first().map { it.second.name })
    }

    @Test
    fun aBlankPhoneNeverMakesABrokerATooShortOneMatchesNoneAndATombstoneIsLeftAlone(): Unit = runBlocking {
        repo.saveHouse(house("h1", "Ravi", "  "))
        repo.saveHouse(house("h3", "Ravi", "98400 11111").copy(deleted = true))
        assertEquals(emptyList<Any>(), repo.observeBrokers().first())
        assertNull(repo.getHouse("h1")!!.brokerId)
        assertNull(repo.getHouse("h3")!!.brokerId)

        // "12" is no number to compare, so it never finds a broker, but it does make one, once (the house is then linked).
        repo.saveHouse(house("h2", "Short", "12"))
        repo.saveHouse(house("h4", "Other", "12"))
        assertEquals(2, repo.observeBrokers().first().size)
        val h2 = repo.getHouse("h2")!!
        assertNotNull(h2.brokerId)
        repo.saveHouse(h2)
        assertEquals(2, repo.observeBrokers().first().size)
    }

    @Test
    fun aLinkedHouseTakesItsBrokersNameAndPhoneAndADanglingIdStaysAsItIs(): Unit = runBlocking {
        val id = repo.saveBroker(Broker(name = "Meena Iyer", phone = "97000 33333", agency = "Beach Road Realty"))
        repo.saveHouse(house("h1", "old name", "12345 67890", brokerId = id))
        val linked = repo.getHouse("h1")!!
        assertEquals("Meena Iyer", linked.contactName)
        assertEquals("97000 33333", linked.contactPhone)
        assertEquals(id, linked.brokerId)

        repo.saveHouse(house("h2", "Someone", "98765 43210", brokerId = "gone-broker"))
        val dangling = repo.getHouse("h2")!!
        assertEquals("gone-broker", dangling.brokerId)
        assertEquals("Someone", dangling.contactName)
    }

    // ---- saveBroker, deleteBroker, brokerHouses ----

    @Test
    fun savingABrokerRewritesTheCopiesOnItsHousesOnlyWhenTheyDiffer(): Unit = runBlocking {
        repo.saveHouse(house("h1", "Ravi", "98400 11111"))
        repo.saveHouse(house("h2", "Ravi", "98400 11111"))
        val id = repo.getHouse("h1")!!.brokerId!!
        db.houses().upsert(repo.getHouse("h1")!!.copy(dirty = false, updatedAt = 5))
        db.houses().upsert(repo.getHouse("h2")!!.copy(dirty = false, updatedAt = 5))

        // Nothing differs: the houses are not touched.
        assertEquals(id, repo.saveBroker(Broker(name = "Ravi", phone = "98400 11111", agency = "Adyar Homes"), id))
        assertFalse(repo.getHouse("h1")!!.dirty)
        assertEquals(5L, repo.getHouse("h1")!!.updatedAt)

        repo.saveBroker(Broker(name = "Ravi Kumar", phone = "+91 98400 11111"), id)
        for (h in listOf("h1", "h2")) {
            val row = repo.getHouse(h)!!
            assertEquals("Ravi Kumar", row.contactName)
            assertEquals("+91 98400 11111", row.contactPhone)
            assertTrue(row.dirty)
            assertTrue(row.updatedAt > 5)
        }
        assertEquals(listOf("h1", "h2"), repo.brokerHouses(id).first().map { it.id }.sorted())
    }

    @Test
    fun aBrokerWithoutANameIsRefused(): Unit = runBlocking {
        val refused = runCatching { repo.saveBroker(Broker(name = "  ")) }
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
        assertEquals(emptyList<Any>(), repo.observeBrokers().first())
    }

    @Test
    fun deletingABrokerUnlinksItsHousesKeepsTheirContactAndTombstonesTheRecord(): Unit = runBlocking {
        repo.saveHouse(house("h1", "Ravi", "98400 11111"))
        val id = repo.getHouse("h1")!!.brokerId!!
        db.houses().upsert(repo.getHouse("h1")!!.copy(dirty = false))

        repo.deleteBroker(id)

        val row = repo.getHouse("h1")!!
        assertNull(row.brokerId)
        assertEquals("Ravi", row.contactName)
        assertEquals("98400 11111", row.contactPhone)
        assertTrue(row.dirty)
        assertEquals(emptyList<Any>(), repo.observeBrokers().first())
        val record = db.records().get(BrokerType.name, id)!!
        assertTrue(record.deleted)
        assertEquals("{}", record.payload)
        assertEquals(emptyList<Any>(), repo.brokerHouses(id).first())
    }

    // ---- the once-only move of contacts into brokers ----

    @Test
    fun theMigrationGroupsHousesByNormalisedPhoneNamesFromTheNewestAndRunsOnce(): Unit = runBlocking {
        // Written straight to the table, as the houses of an install from before the update are.
        db.houses().upsert(house("a", "Old Name", "+91 98400 11111", updatedAt = at + 1))
        db.houses().upsert(house("b", "Ravi Kumar", "098400-11111", updatedAt = at + 3))
        db.houses().upsert(house("c", "R K", "9840011111", updatedAt = at + 2))
        db.houses().upsert(house("d", "Meena", "97000 33333"))
        db.houses().upsert(house("e", "No phone", null))
        db.houses().upsert(house("f", "Blank phone", " "))
        db.houses().upsert(house("g", "Short", "12345"))
        db.houses().upsert(house("i", "Short again", "12345"))
        assertFalse(settings.brokersMigrated())

        repo.migrateContactsToBrokers()

        val brokers = repo.observeBrokers().first()
        // "12345" is too short to compare: one broker for the number typed the same way twice, named by the newest.
        assertEquals(listOf("Meena", "Ravi Kumar", "Short again"), brokers.map { it.second.name })
        val ravi = brokers.first { it.second.name == "Ravi Kumar" }
        assertEquals("098400-11111", ravi.second.phone)
        val houses = db.houses().all().associateBy { it.id }
        for (h in listOf("a", "b", "c")) {
            assertEquals(ravi.first, houses.getValue(h).brokerId)
            assertTrue(houses.getValue(h).dirty)
        }
        // The contact each house holds is left as it was.
        assertEquals("Old Name", houses.getValue("a").contactName)
        assertEquals(brokers.first { it.second.name == "Meena" }.first, houses.getValue("d").brokerId)
        for (h in listOf("e", "f")) assertNull(houses.getValue(h).brokerId)
        assertEquals(houses.getValue("g").brokerId, houses.getValue("i").brokerId)
        assertNotNull(houses.getValue("g").brokerId)
        assertTrue(settings.brokersMigrated())

        // Once only: a house that is unlinked on purpose (or written by an old app) is never linked again.
        db.houses().upsert(house("h", "Late", "98400 11111"))
        repo.migrateContactsToBrokers()
        assertNull(db.houses().get("h")!!.brokerId)
        assertEquals(3, repo.observeBrokers().first().size)
    }

    @Test
    fun anExistingBrokerWithTheNumberIsReused(): Unit = runBlocking {
        val id = repo.saveBroker(Broker(name = "Already here", phone = "9840011111"))
        db.houses().upsert(house("a", "Ravi", "+91 98400 11111"))
        repo.migrateContactsToBrokers()
        assertEquals(id, db.houses().get("a")!!.brokerId)
        assertEquals(1, repo.observeBrokers().first().size)
        assertTrue(settings.brokersMigrated())
    }

    @Test
    fun theFirstReadOfTheHousesRunsTheMigrationBeforeAnythingIsShown(): Unit = runBlocking {
        db.houses().upsert(house("a", "Ravi", "98400 11111"))
        val first = repo.houses.first()
        assertNotNull(first.single().brokerId)
        assertEquals(1, db.records().countLive(BrokerType.name))
    }

    // ---- a /2 backup's brokers ----

    @Test
    fun aBackupCarriesTheBrokersAndAnImportMergesThemByIdWithLastWriteWins(): Unit = runBlocking {
        repo.saveHouse(house("h1", "Ravi", "98400 11111"))
        val id = repo.getHouse("h1")!!.brokerId!!
        repo.saveBroker(Broker(name = "Ravi Kumar", phone = "98400 11111", agency = "Adyar Homes", rating = 4), id)

        val rows = repo.localRows()
        val options = ExportOptions(exportedAtMillis = at)
        val data = BackupData.of(rows.toBundle(options))
        assertEquals(BackupFormat.ID_2, data.format)
        assertEquals(listOf(id), data.brokerRows.map { it.id })
        assertEquals("Adyar Homes", data.brokerRows.single().agency)
        assertEquals(id, data.houses.single().brokerId)
        // Without contact details the copy has neither the brokers nor the links, and is a /1 file.
        val without = BackupData.of(rows.toBundle(options.copy(includeContacts = false)))
        assertEquals(BackupFormat.ID, without.format)
        assertNull(without.brokers)
        assertNull(without.houses.single().brokerId)

        // A newer broker in the file replaces the one here, an older one does not, a new one is added.
        val newer = data.brokerRows.single().copy(name = "Ravi K.", updatedAt = Long.MAX_VALUE / 2)
        val fresh = data.brokerRows.single().copy(id = "extra", name = "Extra", updatedAt = 1)
        val file = data.copy(brokers = listOf(newer, fresh))
        val local = repo.localVersions()
        assertEquals(setOf(id), local.brokers.keys)
        val actions = ImportPlan.plan(
            file, local.houses, local.visits, local.photoIds, emptySet(), ImportMode.MERGE, newId = { "x" },
            localBrokers = local.brokers,
        )
        assertEquals(setOf(id), actions.updatedBrokerIds)
        val result = repo.applyImport(actions) { null }
        assertEquals(2, result.brokers)
        assertEquals(listOf("Extra", "Ravi K."), repo.observeBrokers().first().map { it.second.name })
        assertEquals(Long.MAX_VALUE / 2, db.records().get(BrokerType.name, id)!!.updatedAt)

        // The same file again changes nothing (equal timestamps mean already here).
        val again = ImportPlan.plan(
            file, repo.localVersions().houses, emptyMap(), emptySet(), emptySet(), ImportMode.MERGE, newId = { "x" },
            localBrokers = repo.localVersions().brokers,
        )
        assertEquals(emptyList<Any>(), again.brokers)
    }
}
