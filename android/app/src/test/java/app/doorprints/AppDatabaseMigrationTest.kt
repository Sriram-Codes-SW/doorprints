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
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.doorprints.data.AppDatabase
import app.doorprints.data.DatabaseFile
import app.doorprints.data.create
import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.MoveIn
import app.doorprints.shared.model.MoveInItem
import app.doorprints.shared.model.PhotoMeta
import app.doorprints.shared.model.VisitSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Database version 1 to 2 (`MIGRATION_1_2`, photos.deleted) on the Room KMP database of CMP-4 P4a: an install that
 * still has a version-1 file must open it with every house, visit and photo in it.
 *
 * No `1.json` was ever exported (schema export started in Sprint 3.5, at version 2), so the version-1 file is written
 * here with the SQL of the committed `2.json` minus the one column version 2 added. Two paths are covered:
 * - Room's [MigrationTestHelper] with the framework driver runs the common `migrate(SQLiteConnection)` and validates
 *   the result against the committed `2.json` (handed to the helper as assets, see [SchemaAssets]);
 * - the app's own builder, [AppDatabase.create] (no driver, the framework open helper), on a version-1 `househunt.db`
 *   from before the rename: the file moves to `doorprints.db`, migrates, and the DAOs read the old rows.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code) and WorkManager.
@Config(sdk = [35], application = Application::class)
class AppDatabaseMigrationTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val helperFile: File = context.getDatabasePath(HELPER_DB)

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = SchemaAssets.instrumentation(),
        file = helperFile,
        driver = AndroidSQLiteDriver(),
        databaseClass = AppDatabase::class,
    )

    @Before
    @After
    fun cleanUp() {
        for (name in listOf(HELPER_DB, DatabaseFile.LEGACY_NAME, DatabaseFile.NAME)) context.deleteDatabase(name)
    }

    @Test
    fun migration1To2KeepsTheRowsAndMatchesTheExportedSchema() {
        writeVersion1(helperFile)

        val db = helper.runMigrationsAndValidate(2, listOf(AppDatabase.MIGRATION_1_2))
        try {
            assertEquals(listOf("h1|Flat in Indiranagar|SHORTLISTED|{\"water\":4}"),
                db.rows("SELECT id, label, status, checklist FROM houses"))
            assertEquals(listOf("v1|h1|MANUAL"), db.rows("SELECT id, houseId, source FROM visits"))
            // The new column: every photo from before is live (0), not queued for deletion.
            assertEquals(listOf("p1|h1|0|0"), db.rows("SELECT id, houseId, uploaded, deleted FROM photos"))
        } finally {
            db.close()
        }
    }

    /** v3 (S4b-FR-2): the chain from version 1 to 3 matches the committed `3.json`, with `track_points` empty. */
    @Test
    fun migrations1To3MatchTheExportedSchemaAndStartWithAnEmptyTrace() {
        writeVersion1(helperFile)

        val db = helper.runMigrationsAndValidate(3, listOf(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3))
        try {
            assertEquals(listOf("h1"), db.rows("SELECT id FROM houses"))
            assertEquals(listOf("0"), db.rows("SELECT COUNT(*) FROM track_points"))
        } finally {
            db.close()
        }
    }

    /**
     * v4 (docs/11 5.30 slice 0): the whole chain from version 1 ends in the committed `4.json`, the `records` table
     * empty and taking a row under its two-column key.
     */
    @Test
    fun migrations1To4MatchTheExportedSchemaAndStartWithNoRecords() {
        writeVersion1(helperFile)

        val db = helper.runMigrationsAndValidate(4, AppDatabase.MIGRATIONS.toList())
        try {
            assertEquals(listOf("h1"), db.rows("SELECT id FROM houses"))
            assertEquals(listOf("0"), db.rows("SELECT COUNT(*) FROM records"))
            db.execSQL("INSERT INTO records (type, id, payload, updatedAt, deleted, dirty) VALUES ('broker', 'b1', '{}', 5, 0, 1)")
            assertEquals(listOf("broker|b1|{}|5|0|1"), db.rows("SELECT type, id, payload, updatedAt, deleted, dirty FROM records"))
        } finally {
            db.close()
        }
    }

    /**
     * v5 (docs/11 5.30 slice 1a): the whole chain ends in the committed `5.json`; a house from before has its area,
     * location source and every `cost_*` column null, and the DAO reads that as no cost at all.
     */
    @Test
    fun migrations1To5MatchTheExportedSchemaAndAHouseFromBeforeHasNoValues() = runBlocking {
        writeVersion1(helperFile)

        val db = helper.runMigrationsAndValidate(5, AppDatabase.MIGRATIONS.toList())
        try {
            // IFNULL: the helper reads text, and a NULL column has none.
            assertEquals(listOf("h1|||||"), db.rows("SELECT id, IFNULL(areaSqft, ''), IFNULL(locationSource, ''), IFNULL(cost_deposit, ''), IFNULL(cost_availableFrom, ''), IFNULL(cost_agreedPrice, '') FROM houses"))
            assertEquals(13, AppDatabase.HOUSE_VALUE_COLUMNS.size)
        } finally {
            db.close()
        }
        val legacy = context.getDatabasePath(DatabaseFile.LEGACY_NAME)
        writeVersion1(legacy)
        val opened = AppDatabase.create(context)
        try {
            val house = opened.houses().get("h1")!!
            assertEquals(null, house.areaSqft)
            assertEquals(null, house.locationSource)
            assertEquals(null, house.cost)
        } finally {
            opened.close()
        }
    }

    /**
     * v6 (docs/11 5.30 slice 1b): the whole chain ends in the committed `6.json`; a house from before has no broker,
     * and `MIGRATION_5_6` alone adds the one nullable column to a version-5 house that keeps its values.
     */
    @Test
    fun migrations1To6MatchTheExportedSchemaAndAHouseFromBeforeHasNoBroker() = runBlocking {
        writeVersion1(helperFile)

        val db = helper.runMigrationsAndValidate(6, AppDatabase.MIGRATIONS.toList())
        try {
            assertEquals(listOf("h1|"), db.rows("SELECT id, IFNULL(brokerId, '') FROM houses"))
        } finally {
            db.close()
        }
        val legacy = context.getDatabasePath(DatabaseFile.LEGACY_NAME)
        writeVersion1(legacy)
        val opened = AppDatabase.create(context)
        try {
            assertEquals(null, opened.houses().get("h1")!!.brokerId)
            assertEquals(emptyList<Any>(), opened.houses().liveForBroker("b1"))
        } finally {
            opened.close()
        }
    }

    /**
     * v7 (docs/11 5.6 slice 1c): the whole chain from version 1 ends in the committed `7.json`; a house from before has
     * no rooms, and a house written through the DAO keeps its rooms as JSON text in `houses.rooms` and reads them back.
     */
    @Test
    fun migrations1To7MatchTheExportedSchemaAndAHouseFromBeforeHasNoRooms() = runBlocking {
        writeVersion1(helperFile)

        val db = helper.runMigrationsAndValidate(7, AppDatabase.MIGRATIONS.toList())
        try {
            assertEquals(listOf("h1|"), db.rows("SELECT id, IFNULL(rooms, '') FROM houses"))
        } finally {
            db.close()
        }
        val legacy = context.getDatabasePath(DatabaseFile.LEGACY_NAME)
        writeVersion1(legacy)
        val opened = AppDatabase.create(context)
        try {
            val house = opened.houses().get("h1")!!
            assertEquals(null, house.rooms)
            val rooms = listOf(HouseRoom("r1", "BEDROOM", "Master bedroom", 396, 366, 4, "Damp patch", 0))
            opened.houses().upsert(house.copy(rooms = rooms))
            assertEquals(rooms, opened.houses().get("h1")!!.rooms)
        } finally {
            opened.close()
        }
    }

    /** `MIGRATION_6_7` alone: a version-6 house keeps its values and its broker, and has no rooms. */
    @Test
    fun migration6To7AddsTheRoomsColumnToAVersion6House() {
        writeVersion1(helperFile)
        helper.runMigrationsAndValidate(6, AppDatabase.MIGRATIONS.toList().take(5)).use { v6 ->
            v6.execSQL("UPDATE houses SET areaSqft = 1150, brokerId = 'b1', cost_deposit = 64000")
        }
        val db = helper.runMigrationsAndValidate(7, listOf(AppDatabase.MIGRATION_6_7))
        try {
            assertEquals(listOf("h1|1150|b1|64000|"), db.rows("SELECT id, areaSqft, brokerId, cost_deposit, IFNULL(rooms, '') FROM houses"))
        } finally {
            db.close()
        }
    }

    /**
     * v8 (docs/11 5.5 slice 3a): the whole chain from version 1 ends in the committed `8.json`; a house from before has
     * no answers, and a house written through the DAO keeps its answers as JSON text in `houses.answers`.
     */
    @Test
    fun migrations1To8MatchTheExportedSchemaAndAHouseFromBeforeHasNoAnswers() = runBlocking {
        writeVersion1(helperFile)

        val db = helper.runMigrationsAndValidate(8, AppDatabase.MIGRATIONS.toList())
        try {
            assertEquals(listOf("h1||"), db.rows("SELECT id, IFNULL(rooms, ''), IFNULL(answers, '') FROM houses"))
        } finally {
            db.close()
        }
        val legacy = context.getDatabasePath(DatabaseFile.LEGACY_NAME)
        writeVersion1(legacy)
        val opened = AppDatabase.create(context)
        try {
            val house = opened.houses().get("h1")!!
            assertEquals(null, house.answers)
            val answers = listOf(
                HouseAnswer("a1", "qd_water", "Where does the water come from?", "Borewell", "ANSWERED", 0),
                HouseAnswer("a2", text = "Is the terrace open?", sort = 1),
            )
            opened.houses().upsert(house.copy(answers = answers))
            assertEquals(answers, opened.houses().get("h1")!!.answers)
        } finally {
            opened.close()
        }
    }

    /** `MIGRATION_7_8` alone: a version-7 house keeps its values, broker and rooms, and has no answers. */
    @Test
    fun migration7To8AddsTheAnswersColumnToAVersion7House() {
        writeVersion1(helperFile)
        helper.runMigrationsAndValidate(7, AppDatabase.MIGRATIONS.toList().take(6)).use { v7 ->
            v7.execSQL("UPDATE houses SET areaSqft = 1150, brokerId = 'b1', rooms = '[{\"id\":\"r1\",\"type\":\"HALL\",\"sort\":0}]'")
        }
        val db = helper.runMigrationsAndValidate(8, listOf(AppDatabase.MIGRATION_7_8))
        try {
            assertEquals(
                listOf("h1|1150|b1|[{\"id\":\"r1\",\"type\":\"HALL\",\"sort\":0}]|"),
                db.rows("SELECT id, areaSqft, brokerId, rooms, IFNULL(answers, '') FROM houses"),
            )
        } finally {
            db.close()
        }
    }

    /**
     * v9 (docs/11 slice 5): the whole chain from version 1 ends in the committed `9.json`; a photo from before has no
     * meta (never edited, nothing to send) and a house no move-in, and both are kept as JSON text through the DAOs.
     */
    @Test
    fun migrations1To9MatchTheExportedSchemaAndAPhotoFromBeforeHasNoMeta() = runBlocking {
        writeVersion1(helperFile)

        val db = helper.runMigrationsAndValidate(9, AppDatabase.MIGRATIONS.toList().take(8))
        try {
            assertEquals(listOf("h1|"), db.rows("SELECT id, IFNULL(moveIn, '') FROM houses"))
            assertEquals(
                listOf("p1||||0|0"),
                db.rows("SELECT id, IFNULL(roomId, ''), IFNULL(tags, ''), IFNULL(caption, ''), metaUpdatedAt, metaDirty FROM photos"),
            )
        } finally {
            db.close()
        }
        val legacy = context.getDatabasePath(DatabaseFile.LEGACY_NAME)
        writeVersion1(legacy)
        val opened = AppDatabase.create(context)
        try {
            val house = opened.houses().get("h1")!!
            assertEquals(null, house.moveIn)
            val moveIn = MoveIn(1_790_812_800_000L, "Keys from Ravi", listOf(MoveInItem("mi_keys", "Keys received", true, 0)))
            opened.houses().upsert(house.copy(moveIn = moveIn))
            assertEquals(moveIn, opened.houses().get("h1")!!.moveIn)
            val photo = opened.photos().get("p1")!!
            assertEquals(PhotoMeta(), photo.meta)
            assertFalse(photo.metaDirty)
            opened.photos().upsert(photo.withMeta(PhotoMeta("r1", listOf("MOVE_IN", "damp corner"), "Tap drips", 5L), dirty = true))
            val again = opened.photos().get("p1")!!
            assertEquals(PhotoMeta("r1", listOf("MOVE_IN", "damp corner"), "Tap drips", 5L), again.meta)
            assertTrue(again.metaDirty)
        } finally {
            opened.close()
        }
    }

    /** `MIGRATION_8_9` alone: a version-8 house keeps its answers and a version-8 photo its row, with no meta. */
    @Test
    fun migration8To9AddsThePhotoMetaAndMoveInColumns() {
        writeVersion1(helperFile)
        helper.runMigrationsAndValidate(8, AppDatabase.MIGRATIONS.toList().take(7)).use { v8 ->
            v8.execSQL("UPDATE houses SET answers = '[{\"id\":\"a1\",\"text\":\"Water?\",\"sort\":0}]'")
        }
        val db = helper.runMigrationsAndValidate(9, listOf(AppDatabase.MIGRATION_8_9))
        try {
            assertEquals(
                listOf("h1|[{\"id\":\"a1\",\"text\":\"Water?\",\"sort\":0}]|"),
                db.rows("SELECT id, answers, IFNULL(moveIn, '') FROM houses"),
            )
            assertEquals(listOf("p1|0|0|0"), db.rows("SELECT id, deleted, metaUpdatedAt, metaDirty FROM photos"))
        } finally {
            db.close()
        }
    }

    /** `MIGRATION_9_10` alone (S4b-BL-87): a version-9 house keeps its move-in and has no floor, and a floor round-trips. */
    @Test
    fun migration9To10AddsTheFloorColumn() = runBlocking {
        writeVersion1(helperFile)
        helper.runMigrationsAndValidate(9, AppDatabase.MIGRATIONS.toList().take(8)).use { v9 ->
            v9.execSQL("UPDATE houses SET moveIn = '{\"notes\":\"Keys\"}'")
        }
        val db = helper.runMigrationsAndValidate(10, listOf(AppDatabase.MIGRATION_9_10))
        try {
            assertEquals(listOf("h1|{\"notes\":\"Keys\"}|"), db.rows("SELECT id, moveIn, IFNULL(floor, '') FROM houses"))
        } finally {
            db.close()
        }
        val legacy = context.getDatabasePath(DatabaseFile.LEGACY_NAME)
        writeVersion1(legacy)
        val opened = AppDatabase.create(context)
        try {
            val house = opened.houses().get("h1")!!
            assertEquals(null, house.floor)
            opened.houses().upsert(house.copy(floor = -1))
            assertEquals(-1, opened.houses().get("h1")!!.floor)
        } finally {
            opened.close()
        }
    }

    @Test
    fun aVersion1HousehuntDatabaseMovesMigratesAndOpensWithItsRows() = runBlocking {
        val legacy = context.getDatabasePath(DatabaseFile.LEGACY_NAME)
        writeVersion1(legacy)

        val db = AppDatabase.create(context)
        try {
            val house = db.houses().get("h1")!!
            assertEquals("Flat in Indiranagar", house.label)
            assertEquals(HouseStatus.SHORTLISTED, house.status)
            assertEquals(mapOf("water" to 4), house.checklist)
            assertEquals(VisitSource.MANUAL, db.visits().get("v1")!!.source)
            val photo = db.photos().get("p1")!!
            assertFalse("a photo from version 1 is not queued for deletion", photo.deleted)
            assertEquals(listOf("p1"), db.photos().pendingUpload().map { it.id })
            assertTrue(db.photos().pendingDelete().isEmpty())
        } finally {
            db.close()
        }
        assertTrue(context.getDatabasePath(DatabaseFile.NAME).exists())
        assertFalse(legacy.exists())
    }

    /** A version-1 database with one house, one visit and one photo, as the app wrote it before photos.deleted. */
    private fun writeVersion1(file: File) {
        file.parentFile!!.mkdirs()
        val connection = AndroidSQLiteDriver().open(file.path)
        try {
            for (sql in VERSION_1_SCHEMA) connection.execSQL(sql)
            connection.execSQL(
                "INSERT INTO houses (id, label, lat, lon, status, priceType, checklist, createdAt, updatedAt, " +
                    "deleted, dirty) VALUES ('h1', 'Flat in Indiranagar', 12.97, 77.64, 'SHORTLISTED', 'RENT', " +
                    "'{\"water\":4}', 1, 2, 0, 1)",
            )
            connection.execSQL(
                "INSERT INTO visits (id, houseId, lat, lon, arrivedAt, source, updatedAt, deleted, dirty) " +
                    "VALUES ('v1', 'h1', 12.97, 77.64, 3, 'MANUAL', 3, 0, 1)",
            )
            connection.execSQL(
                "INSERT INTO photos (id, houseId, path, uploaded, createdAt) " +
                    "VALUES ('p1', 'h1', 'photos/p1.jpg', 0, 4)",
            )
            connection.execSQL("PRAGMA user_version = 1")
        } finally {
            connection.close()
        }
    }

    private fun SQLiteConnection.rows(sql: String): List<String> = prepare(sql).use { statement ->
        buildList {
            while (statement.step()) {
                add((0 until statement.getColumnCount()).joinToString("|") { statement.getText(it) })
            }
        }
    }

    /**
     * Room's helper reads `<class>/<version>.json` from the instrumentation context's assets. Robolectric serves
     * only the app's merged assets, so the committed schemas (in :shared) are packed into a small asset file for the
     * test and added to a context of their own (through `AssetManager.addAssetPath`, a hidden method that
     * Robolectric implements; test code only); nothing is added to the APK.
     */
    private object SchemaAssets {
        fun instrumentation(): Instrumentation {
            val real = InstrumentationRegistry.getInstrumentation()
            val schemas = ContextWrapperWithAssets(real.targetContext, schemaAssetManager())
            return object : Instrumentation() {
                override fun getContext(): Context = schemas
                override fun getTargetContext(): Context = real.targetContext
            }
        }

        private fun schemaAssetManager(): AssetManager {
            val dir = listOf(File("../shared/schemas"), File("shared/schemas")).firstOrNull { it.isDirectory }
                ?: error("shared/schemas not found from ${File(".").absolutePath}")
            val zip = File.createTempFile("room-schemas", ".zip").apply { deleteOnExit() }
            ZipOutputStream(zip.outputStream()).use { out ->
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    out.putNextEntry(ZipEntry("assets/" + file.relativeTo(dir).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            return try {
                val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, zip.path)
                assets
            } catch (e: ReflectiveOperationException) {
                throw AssertionError(
                    "schemaAssetManager: the hidden AssetManager.addAssetPath is no longer reachable (Robolectric " +
                        "upgrade?); load the schemas another way",
                    e,
                )
            }
        }
    }

    private class ContextWrapperWithAssets(base: Context, private val assets: AssetManager) : ContextWrapper(base) {
        override fun getAssets(): AssetManager = assets
    }

    private companion object {
        const val HELPER_DB = "migration-test.db"

        /**
         * The version-1 layout: `2.json`'s CREATE statements with `photos.deleted` left out, plus Room's master
         * table. Room does not read the old identity hash on an upgrade (it writes the new one after migrating), so
         * the placeholder stands in for the version-1 hash, which was never exported.
         */
        val VERSION_1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `houses` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, `address` TEXT, " +
                "`street` TEXT, `locality` TEXT, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `status` TEXT NOT NULL, " +
                "`price` INTEGER, `priceType` TEXT, `bedrooms` INTEGER, `rating` INTEGER, `contactName` TEXT, " +
                "`contactPhone` TEXT, `listingUrl` TEXT, `notes` TEXT, `checklist` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                "`dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_houses_street` ON `houses` (`street`)",
            "CREATE TABLE IF NOT EXISTS `visits` (`id` TEXT NOT NULL, `houseId` TEXT, `lat` REAL NOT NULL, " +
                "`lon` REAL NOT NULL, `street` TEXT, `arrivedAt` INTEGER NOT NULL, `leftAt` INTEGER, " +
                "`source` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                "`dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_visits_houseId` ON `visits` (`houseId`)",
            "CREATE INDEX IF NOT EXISTS `index_visits_street` ON `visits` (`street`)",
            "CREATE TABLE IF NOT EXISTS `photos` (`id` TEXT NOT NULL, `houseId` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                "`uploaded` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_photos_houseId` ON `photos` (`houseId`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'version-1-placeholder')",
        )
    }
}
