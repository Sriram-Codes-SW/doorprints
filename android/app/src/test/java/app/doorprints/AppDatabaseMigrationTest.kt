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
import app.doorprints.shared.model.HouseStatus
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
