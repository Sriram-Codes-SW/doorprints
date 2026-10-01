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

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.VisitSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Room on iOS (S4b-BL-24): [iosAppDatabase] on a file in a temporary folder, with the bundled SQLite driver the app
 * uses. Every table is written and read back through its DAO, [withImmediateTransaction] rolls back on a throw,
 * [localTablesChanged] emits after a write, and a version-1 file opens through [AppDatabase.MIGRATIONS] with its
 * rows. Runs on the iOS simulator only (macOS CI); on Linux the test code is compiled, not run.
 */
@OptIn(ExperimentalForeignApi::class)
class AppDatabaseIosTest {
    private val dir = NSTemporaryDirectory().trimEnd('/') + "/doorprints-db-" + NSUUID().UUIDString
    private val path = "$dir/doorprints.db"

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null,
            error = null)
    }

    @AfterTest
    fun removeTheFolder() {
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
    }

    private fun house(id: String = "h1") = HouseEntity(
        id = id, label = "Flat in Indiranagar", street = "12th Main", lat = 12.97, lon = 77.64,
        status = HouseStatus.SHORTLISTED, price = 45_000, checklist = mapOf("water" to 4), createdAt = 1, updatedAt = 2,
    )

    @Test
    fun eachTableIsWrittenAndReadBack() = runTest {
        val db = iosAppDatabase(path)
        try {
            db.houses().upsert(house())
            db.visits().upsert(
                VisitEntity(id = "v1", houseId = "h1", lat = 12.97, lon = 77.64, arrivedAt = 3, leftAt = 5,
                    source = VisitSource.MANUAL, updatedAt = 3),
            )
            db.photos().upsert(PhotoEntity(id = "p1", houseId = "h1", path = "photos/p1.jpg", createdAt = 4))

            assertEquals(house(), db.houses().get("h1"))
            assertEquals(1, db.houses().countOnStreet("12TH MAIN"))
            val visit = db.visits().get("v1")!!
            assertEquals(VisitSource.MANUAL, visit.source)
            assertEquals(5L, visit.leftAt)
            assertEquals(listOf("v1"), db.visits().all().map { it.id })
            assertEquals(listOf("p1"), db.photos().pendingUpload().map { it.id })

            db.photos().markDeleted("p1")
            assertTrue(db.photos().get("p1")!!.deleted)
            assertEquals(listOf("p1"), db.photos().pendingDelete().map { it.id })
            db.houses().markClean("h1", updatedAt = 2)
            assertTrue(db.houses().dirty().isEmpty())
        } finally {
            db.close()
        }

        // The rows are in the file, not only in Room's connection: a new database on the same path reads them.
        val reopened = iosAppDatabase(path)
        try {
            assertEquals("Flat in Indiranagar", reopened.houses().get("h1")?.label)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun aThrowInsideTheTransactionRollsEverythingBack() = runTest {
        val db = iosAppDatabase(path)
        try {
            assertFailsWith<IllegalStateException> {
                db.withImmediateTransaction {
                    db.houses().upsert(house())
                    db.photos().upsert(PhotoEntity(id = "p1", houseId = "h1", path = "photos/p1.jpg", createdAt = 4))
                    error("stop")
                }
            }
            assertNull(db.houses().get("h1"))
            assertNull(db.photos().get("p1"))

            db.withImmediateTransaction { db.houses().upsert(house()) }
            assertEquals("h1", db.houses().get("h1")?.id)
        } finally {
            db.close()
        }
    }

    @Test
    fun localTablesChangedEmitsAfterAWrite() = runTest {
        val db = iosAppDatabase(path)
        try {
            val changes = Channel<Set<String>>(Channel.UNLIMITED)
            val collector = launch { db.localTablesChanged().collect { changes.send(it) } }
            // Real time, not runTest's virtual clock: a missing emission fails in seconds instead of hanging.
            withContext(Dispatchers.Default) { withTimeout(10.seconds) { changes.receive() } } // the emission at once

            db.visits().upsert(VisitEntity(id = "v1", lat = 1.0, lon = 2.0, arrivedAt = 3, updatedAt = 3))

            val next = withContext(Dispatchers.Default) { withTimeout(10.seconds) { changes.receive() } }
            assertTrue("visits" in next)
            collector.cancel()
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersion1DatabaseMigratesAndKeepsItsRows() = runTest {
        writeVersion1(path)

        val db = iosAppDatabase(path)
        try {
            val house = db.houses().get("h1")!!
            assertEquals("Flat in Indiranagar", house.label)
            assertEquals(HouseStatus.SHORTLISTED, house.status)
            assertEquals(mapOf("water" to 4), house.checklist)
            assertEquals(VisitSource.MANUAL, db.visits().get("v1")!!.source)
            val photo = db.photos().get("p1")!!
            assertFalse(photo.deleted, "a photo from version 1 is not queued for deletion")
            assertEquals(listOf("p1"), db.photos().pendingUpload().map { it.id })
            assertTrue(db.photos().pendingDelete().isEmpty())
        } finally {
            db.close()
        }

        val connection = BundledSQLiteDriver().open(path)
        try {
            // The current version (10 since S4b-BL-87: houses.floor; 9 the photos' meta and houses.moveIn; 8 houses.answers;
            // 7 houses.rooms; 6 houses.brokerId; 5 the house's values; 4 records; track_points and records empty after the
            // migration, and a house from before has no area, source, cost, broker, rooms, answers, move-in or floor).
            assertEquals(listOf("10"), connection.rows("PRAGMA user_version"))
            assertEquals(
                listOf("h1|||||||"),
                connection.rows(
                    "SELECT id, IFNULL(areaSqft, ''), IFNULL(cost_deposit, ''), IFNULL(brokerId, ''), IFNULL(rooms, ''), " +
                        "IFNULL(answers, ''), IFNULL(moveIn, ''), IFNULL(floor, '') FROM houses",
                ),
            )
            // A photo from before has no meta: no room, tags or caption, never edited, nothing to send (slice 5).
            assertEquals(
                listOf("p1|0||||0|0"),
                connection.rows(
                    "SELECT id, deleted, IFNULL(roomId, ''), IFNULL(tags, ''), IFNULL(caption, ''), metaUpdatedAt, metaDirty FROM photos",
                ),
            )
            assertEquals(listOf("0"), connection.rows("SELECT COUNT(*) FROM track_points"))
            assertEquals(listOf("0"), connection.rows("SELECT COUNT(*) FROM records"))
        } finally {
            connection.close()
        }
    }

    /** A version-1 file with one house, one visit and one photo, as the app wrote it before photos.deleted. */
    private fun writeVersion1(path: String) {
        val connection = BundledSQLiteDriver().open(path)
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
                "INSERT INTO photos (id, houseId, path, uploaded, createdAt) VALUES ('p1', 'h1', 'photos/p1.jpg', 0, 4)",
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

    private companion object {
        /**
         * The version-1 layout, as in `:app`'s `AppDatabaseMigrationTest`: no `1.json` was ever exported (S4b-BL-25),
         * so these are the CREATE statements of the committed `2.json` with `photos.deleted` left out, plus Room's
         * master table. Room does not read the old identity hash on an upgrade, so a placeholder stands in for it.
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
