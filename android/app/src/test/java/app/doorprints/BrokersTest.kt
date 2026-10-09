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
import app.doorprints.data.toBroker
import app.doorprints.data.toBundle
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.BackupFormat
import app.doorprints.shared.export.ExportBroker
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.export.ImportPlan
import app.doorprints.shared.model.Broker
import app.doorprints.shared.model.BrokerType
import app.doorprints.shared.records.RecordLimitException
import app.doorprints.shared.records.RecordRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // ---- characterization before the move into BrokerStore (S4b-BL-168, slice 6) ----

    @Test
    fun brokersAreListedByNameIgnoringCaseThenIdAndARowThatCannotBeTrustedIsSkippedEverywhere(): Unit = runBlocking {
        repo.saveBroker(Broker(name = "beta"), "b2")
        repo.saveBroker(Broker(name = "Beta"), "b1")
        repo.saveBroker(Broker(name = "alpha"), "b3")
        repo.saveBroker(Broker(name = "Zed"), "b4")
        db.records().upsert(RecordEntity(BrokerType.name, "bad1", """{"name":" "}""", at))
        db.records().upsert(RecordEntity(BrokerType.name, "bad2", "not json", at))
        db.records().upsert(RecordEntity(BrokerType.name, "bad3", """{"name":"${"x".repeat(201)}"}""", at))
        assertEquals(listOf("b3", "b1", "b2", "b4"), repo.observeBrokers().first().map { it.first })
        // The backup lists the same rows by id, each with its own stamp.
        val exported = repo.localRows().brokers
        assertEquals(listOf("b1", "b2", "b3", "b4"), exported.map { it.id })
        assertEquals(db.records().get(BrokerType.name, "b3")!!.updatedAt, exported.first { it.id == "b3" }.updatedAt)
    }

    @Test
    fun aSavedBrokerIsCleanedMarkedDirtyAndKeepsTheIdItWasGiven(): Unit = runBlocking {
        val before = System.currentTimeMillis()
        assertEquals("b-ravi", repo.saveBroker(Broker(name = "  Ravi  ", phone = " ", agency = " Adyar Homes ", rating = 9), "b-ravi"))
        val row = db.records().get(BrokerType.name, "b-ravi")!!
        assertTrue(row.dirty)
        assertFalse(row.deleted)
        assertTrue(row.updatedAt >= before)
        assertEquals(Broker(name = "Ravi", agency = "Adyar Homes"), row.toBroker())

        val minted = repo.saveBroker(Broker(name = "Meena"))
        assertNotEquals("b-ravi", minted)
        assertEquals("Meena", db.records().get(BrokerType.name, minted)!!.toBroker()!!.name)
    }

    @Test
    fun aHouseLinkedToADeletedBrokerKeepsItsLinkAndItsOwnContact(): Unit = runBlocking {
        val id = repo.saveBroker(Broker(name = "Gone", phone = "98400 11111"))
        repo.deleteBroker(id)
        repo.saveHouse(house("h1", "Mine", "97000 33333", brokerId = id))
        val row = repo.getHouse("h1")!!
        assertEquals(id, row.brokerId)
        assertEquals("Mine", row.contactName)
        assertEquals("97000 33333", row.contactPhone)
        assertEquals(emptyList<Any>(), repo.observeBrokers().first())
    }

    @Test
    fun aBrokerMadeFromAContactIsTrimmedAndCutToTheRecordsLimits(): Unit = runBlocking {
        repo.saveHouse(house("h1", "N".repeat(250), "9".repeat(60)))
        val long = repo.getHouse("h1")!!
        assertEquals(200, long.contactName!!.length)
        assertEquals(50, long.contactPhone!!.length)
        val made = repo.observeBrokers().first().single()
        assertEquals(long.brokerId, made.first)
        assertEquals("N".repeat(200), made.second.name)
        assertEquals("9".repeat(50), made.second.phone)

        repo.saveHouse(house("h2", "  Kumar  ", " 98765 43210 "))
        assertEquals("Kumar", repo.getHouse("h2")!!.contactName)
        assertEquals("98765 43210", repo.getHouse("h2")!!.contactPhone)
        assertEquals(listOf("Kumar", "N".repeat(200)), repo.observeBrokers().first().map { it.second.name })
    }

    @Test
    fun aFullBrokerTypeRefusesANewBrokerButNotAChangeAndALinkingHouseIsSavedUnlinked(): Unit = runBlocking {
        for (i in 0 until RecordRules.MAX_ROWS_PER_TYPE) {
            db.records().upsert(RecordEntity(BrokerType.name, "b_" + i.toString().padStart(4, '0'), """{"name":"B$i"}""", at))
        }
        assertThrows(RecordLimitException::class.java) { runBlocking { repo.saveBroker(Broker(name = "One more")) } }
        repo.saveBroker(Broker(name = "Changed"), "b_0001")
        assertEquals("Changed", db.records().get(BrokerType.name, "b_0001")!!.toBroker()!!.name)

        repo.saveHouse(house("h1", "Ravi", "98400 11111"))
        val row = repo.getHouse("h1")!!
        assertNull(row.brokerId)
        assertEquals("Ravi", row.contactName)
        assertEquals("98400 11111", row.contactPhone)
        assertEquals(RecordRules.MAX_ROWS_PER_TYPE, db.records().countLive(BrokerType.name))
    }

    @Test
    fun deletingABrokerNobodyHasStillUnlinksTheHousesThatNameItAndWritesNoRecord(): Unit = runBlocking {
        repo.saveHouse(house("h1", "Someone", "98765 43210", brokerId = "gone-broker"))
        db.houses().upsert(repo.getHouse("h1")!!.copy(dirty = false, updatedAt = 5))
        repo.deleteBroker("gone-broker")
        val row = repo.getHouse("h1")!!
        assertNull(row.brokerId)
        assertTrue(row.dirty)
        assertTrue(row.updatedAt > 5)
        assertNull(db.records().get(BrokerType.name, "gone-broker"))
    }

    @Test
    fun aDeletedHouseIsNeitherListedUnderItsBrokerNorRewrittenWithIt(): Unit = runBlocking {
        repo.saveHouse(house("h1", "Ravi", "98400 11111"))
        repo.saveHouse(house("h2", "Meena", "97000 33333"))
        val ravi = repo.getHouse("h1")!!.brokerId!!
        val meena = repo.getHouse("h2")!!.brokerId!!
        repo.deleteHouse("h1")
        assertEquals(emptyList<Any>(), repo.brokerHouses(ravi).first())
        assertEquals(listOf("h2"), repo.brokerHouses(meena).first().map { it.id })
        repo.saveBroker(Broker(name = "Ravi Kumar", phone = "98400 11111"), ravi)
        assertEquals("Ravi", db.houses().get("h1")!!.contactName)
    }

    @Test
    fun theMigrationIgnoresADeletedBrokerAndWhenTheTypeIsFullLinksOnlyToTheOnesThatAreThere(): Unit = runBlocking {
        val old = repo.saveBroker(Broker(name = "Old", phone = "98400 11111"))
        repo.deleteBroker(old)
        db.houses().upsert(house("a", "Ravi", "98400 11111"))
        repo.migrateContactsToBrokers()
        val a = db.houses().get("a")!!
        assertNotNull(a.brokerId)
        assertNotEquals(old, a.brokerId)
        assertEquals(listOf("Ravi"), repo.observeBrokers().first().map { it.second.name })
    }

    @Test
    fun theMigrationOfAFullTypeLinksTheHouseWhoseNumberIsKnownLeavesTheOtherUnlinkedAndIsDoneAnyway(): Unit = runBlocking {
        for (i in 1 until RecordRules.MAX_ROWS_PER_TYPE) {
            db.records().upsert(RecordEntity(BrokerType.name, "b_" + i.toString().padStart(4, '0'), """{"name":"B$i"}""", at))
        }
        db.records().upsert(RecordEntity(BrokerType.name, "b_known", """{"name":"Known","phone":"97000 33333"}""", at))
        db.houses().upsert(house("a", "Ravi", "98400 11111"))
        db.houses().upsert(house("d", "Meena", "97000 33333"))
        repo.migrateContactsToBrokers()
        assertNull(db.houses().get("a")!!.brokerId)
        assertEquals("b_known", db.houses().get("d")!!.brokerId)
        assertTrue(settings.brokersMigrated())
        assertEquals(RecordRules.MAX_ROWS_PER_TYPE, db.records().countLive(BrokerType.name))
    }

    @Test
    fun anImportedBrokerIsCleanedMarkedDirtyAndStampedWithTheFilesTimeOrNoLaterThanNowForACopy(): Unit = runBlocking {
        val odd = ExportBroker(id = "b-odd", name = "  Odd  ", phone = " 98400 11111 ", rating = 9, updatedAt = 5)
        val file = BackupData.of(repo.localRows().toBundle(ExportOptions(exportedAtMillis = at))).copy(brokers = listOf(odd))
        val local = repo.localVersions()
        val merge = ImportPlan.plan(
            file, local.houses, local.visits, local.photoIds, emptySet(), ImportMode.MERGE, newId = { "x" },
            localBrokers = local.brokers,
        )
        assertEquals(1, repo.applyImport(merge) { null }.brokers)
        val row = db.records().get(BrokerType.name, "b-odd")!!
        assertEquals(5L, row.updatedAt)
        assertTrue(row.dirty)
        assertFalse(row.deleted)
        assertEquals(Broker(name = "Odd", phone = "98400 11111"), row.toBroker())

        // A copy stamps what it writes no later than now, even for a file from the future.
        val future = file.copy(brokers = listOf(odd.copy(id = "b-file", updatedAt = Long.MAX_VALUE / 2)))
        val before = System.currentTimeMillis()
        val copy = ImportPlan.plan(future, local.houses, local.visits, local.photoIds, emptySet(), ImportMode.COPY, newId = { "b-copy" })
        assertEquals(1, repo.applyImport(copy) { null }.brokers)
        val later = db.records().get(BrokerType.name, "b-copy")!!
        assertTrue(later.dirty)
        assertTrue(later.updatedAt in before..System.currentTimeMillis())
        assertEquals(Broker(name = "Odd", phone = "98400 11111"), later.toBroker())
    }

    // ---- written after the move, for mutants (BrokerStore) ----

    @Test
    fun everyFirstReadOfTheHousesOrTheBrokersRunsTheMigrationBeforeAnythingIsShown(): Unit = runBlocking {
        val reads: List<suspend () -> Unit> = listOf(
            { repo.observeBrokers().first() },
            { repo.house("a").first() },
            { repo.houseSnapshot() },
            { repo.getHouse("a") },
            { repo.localRows() },
            { repo.observeRecords(BrokerType).first() },
        )
        for ((n, read) in reads.withIndex()) {
            db.close()
            context.deleteDatabase(DatabaseFile.NAME)
            db = AppDatabase.create(context)
            settings = SettingsStore(
                PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "brokers-test-$n.preferences_pb") },
                MemorySecrets,
            )
            repo = AndroidRepository(context, db, settings)
            db.houses().upsert(house("a", "Ravi", "98400 11111"))
            read()
            assertNotNull("read $n", db.houses().get("a")!!.brokerId)
            assertTrue("read $n", settings.brokersMigrated())
            File(context.filesDir, "brokers-test-$n.preferences_pb").delete()
        }
    }

    @Test
    fun aBrokersPhoneAloneOrNameAloneIsCopiedToItsHouses(): Unit = runBlocking {
        repo.saveHouse(house("h1", "Ravi", "98400 11111"))
        val id = repo.getHouse("h1")!!.brokerId!!
        db.houses().upsert(repo.getHouse("h1")!!.copy(dirty = false, updatedAt = 5))
        repo.saveBroker(Broker(name = "Ravi", phone = "+91 98400 11111"), id)
        assertEquals("+91 98400 11111", db.houses().get("h1")!!.contactPhone)
        assertTrue(db.houses().get("h1")!!.dirty)
        db.houses().upsert(db.houses().get("h1")!!.copy(dirty = false, updatedAt = 5))
        repo.saveBroker(Broker(name = "Ravi K", phone = "+91 98400 11111"), id)
        assertEquals("Ravi K", db.houses().get("h1")!!.contactName)
        assertTrue(db.houses().get("h1")!!.dirty)
    }

    @Test
    fun theMigrationLeavesALinkedHouseAloneAndTakesTheFirstOfTwoBrokersWithTheNumber(): Unit = runBlocking {
        db.records().upsert(RecordEntity(BrokerType.name, "b1", """{"name":"First","phone":"98400 11111"}""", at))
        db.records().upsert(RecordEntity(BrokerType.name, "b2", """{"name":"Second","phone":"098400-11111"}""", at))
        db.records().upsert(RecordEntity(BrokerType.name, "b3", """{"name":"Third","phone":"97000 33333"}""", at))
        db.houses().upsert(house("a", "Ravi", "9840011111", updatedAt = at + 1))
        db.houses().upsert(house("l", "Linked", "97000 33333", updatedAt = 7, brokerId = "b1").copy(dirty = false))
        repo.migrateContactsToBrokers()
        assertEquals("b1", db.houses().get("a")!!.brokerId)
        val linked = db.houses().get("l")!!
        assertEquals("b1", linked.brokerId)
        assertEquals(7L, linked.updatedAt)
        assertFalse(linked.dirty)
        assertEquals(3, db.records().countLive(BrokerType.name))
    }
}
