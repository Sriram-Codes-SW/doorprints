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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.doorprints.data.AppDatabase
import app.doorprints.data.create
import app.doorprints.data.DatabaseFile
import app.doorprints.data.HouseEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Room file was `househunt.db` until the rename of 2026-09-24 and is `doorprints.db` now ([DatabaseFile]).
 * An existing install must open its old database, with every house in it, under the new name.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code) and WorkManager.
@Config(sdk = [35], application = Application::class)
class DatabaseFileTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val legacy get() = context.getDatabasePath(DatabaseFile.LEGACY_NAME)
    private val current get() = context.getDatabasePath(DatabaseFile.NAME)

    @Before
    @After
    fun cleanUp() {
        for (name in listOf(DatabaseFile.LEGACY_NAME, DatabaseFile.NAME)) context.deleteDatabase(name)
    }

    @Test
    fun anExistingHousehuntDatabaseOpensUnderTheNewNameWithItsHouses() = runBlocking {
        val old = Room.databaseBuilder(context, AppDatabase::class.java, DatabaseFile.LEGACY_NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        old.houses().upsert(
            HouseEntity(id = "h1", label = "Flat", lat = 12.97, lon = 77.64, createdAt = 1L, updatedAt = 1L),
        )
        old.close()
        assertTrue(legacy.exists())

        val db = AppDatabase.create(context)
        try {
            assertEquals("Flat", db.houses().get("h1")?.label)
        } finally {
            db.close()
        }
        assertTrue(current.exists())
        assertFalse("the old file is moved, not copied", legacy.exists())
    }

    @Test
    fun movesTheMainFileAndEveryCompanionFile() {
        legacy.parentFile!!.mkdirs()
        val suffixes = listOf("", "-wal", "-shm", "-journal")
        for (suffix in suffixes) File(legacy.path + suffix).writeText("old$suffix")

        assertEquals(DatabaseFile.NAME, DatabaseFile.resolve(context))

        for (suffix in suffixes) {
            assertFalse(File(legacy.path + suffix).exists())
            assertEquals("old$suffix", File(current.path + suffix).readText())
        }
    }

    @Test
    fun finishesAMoveThatStoppedAfterTheCompanionFiles() {
        legacy.parentFile!!.mkdirs()
        legacy.writeText("old")
        File(current.path + "-wal").writeText("old-wal, moved by the interrupted run")

        assertEquals(DatabaseFile.NAME, DatabaseFile.resolve(context))

        assertEquals("old", current.readText())
        assertEquals("old-wal, moved by the interrupted run", File(current.path + "-wal").readText())
        assertFalse(legacy.exists())
    }

    @Test
    fun leavesBothFilesAloneWhenTheNewOneAlreadyExists() {
        legacy.parentFile!!.mkdirs()
        legacy.writeText("old")
        current.writeText("new")

        assertEquals(DatabaseFile.NAME, DatabaseFile.resolve(context))

        assertEquals("old", legacy.readText())
        assertEquals("new", current.readText())
    }

    @Test
    fun aNewInstallUsesTheNewName() {
        assertEquals(DatabaseFile.NAME, DatabaseFile.resolve(context))
        assertFalse(current.exists())
    }

    @Test
    fun opensTheOldFileWhenItCannotBeRenamed() {
        // A directory in the way of a companion file makes that rename fail; the moved files must go back.
        val dir = File(context.cacheDir, "db-rename-${System.nanoTime()}").apply { mkdirs() }
        val old = File(dir, DatabaseFile.LEGACY_NAME).apply { writeText("old") }
        File(old.path + "-journal").writeText("old-journal")
        File(old.path + "-wal").writeText("old-wal")
        val new = File(dir, DatabaseFile.NAME)
        File(new.path + "-journal").mkdirs()
        File(new.path + "-journal", "blocker").writeText("x")

        val opened = DatabaseFile.resolve(legacy = old, current = new)

        assertEquals(old, opened)
        assertEquals("old", old.readText())
        assertEquals("old-wal", File(old.path + "-wal").readText())
        assertNotNull("the old journal stays with the old file", File(old.path + "-journal").takeIf { it.isFile })
        assertFalse(new.exists())
        dir.deleteRecursively()
    }

    @Test
    fun aFailedRetryPutsBackTheCompanionsAnInterruptedRunMoved() {
        // An earlier run moved the WAL and was killed; this run cannot rename the journal (a directory is in the way).
        val dir = File(context.cacheDir, "db-retry-${System.nanoTime()}").apply { mkdirs() }
        val old = File(dir, DatabaseFile.LEGACY_NAME).apply { writeText("old") }
        File(old.path + "-journal").writeText("old-journal")
        val new = File(dir, DatabaseFile.NAME)
        File(new.path + "-wal").writeText("old-wal, moved by the interrupted run")
        File(new.path + "-journal").mkdirs()
        File(new.path + "-journal", "blocker").writeText("x")

        val opened = DatabaseFile.resolve(legacy = old, current = new)

        assertEquals(old, opened)
        assertEquals(
            "the WAL goes back to the file it belongs to",
            "old-wal, moved by the interrupted run",
            File(old.path + "-wal").readText(),
        )
        assertFalse(File(new.path + "-wal").exists())
        dir.deleteRecursively()
    }
}
