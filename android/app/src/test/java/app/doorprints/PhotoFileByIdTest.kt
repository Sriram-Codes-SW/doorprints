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
import app.doorprints.data.PhotoEntity
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.data.create
import app.doorprints.shared.export.ExportPhoto
import app.doorprints.shared.export.ImportActions
import app.doorprints.shared.export.ImportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * S4b-BL-52 on the app's own database (Robolectric): the repository reaches a photo's file by its id, so a row whose
 * stored path names a folder that has moved (an iOS app's container after an update) still has its file deleted, and
 * a row written the Android way names the very file `photoFileOf` finds.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code) and its own WorkManager.
@Config(sdk = [35], application = Application::class)
class PhotoFileByIdTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var repo: AndroidRepository

    /** Nothing here reads the API key. */
    private object NoSecrets : SecretStore {
        override fun get(settings: Preferences): String? = null
        override fun put(settings: MutablePreferences, apiKey: String) = Unit
        override fun clear(settings: MutablePreferences) = Unit
    }

    @Before
    fun setUp() {
        // "Sync soon" goes to a test WorkManager; its network constraint keeps the job queued, so no sync runs.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        context.deleteDatabase(DatabaseFile.NAME)
        db = AppDatabase.create(context)
        val settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "test.preferences_pb") },
            NoSecrets,
        )
        repo = AndroidRepository(context, db, settings)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
    }

    @Test
    fun deletingARowWithAStalePathDeletesTheFileInTheCurrentFolder(): Unit = runBlocking {
        val at = 1_760_000_000_000
        db.houses().upsert(
            HouseEntity(id = "h1", label = "House", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at)
        )
        val file = repo.photoFile("p1").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val stale = File(context.cacheDir, "old-container/photos/p1.jpg").path
        val row = PhotoEntity("p1", "h1", stale, uploaded = false, createdAt = at)
        db.photos().upsert(row)

        repo.deletePhoto(row)

        assertFalse("the file in the current folder is gone", file.exists())
        assertNull(db.photos().get("p1"))
    }

    @Test
    fun aRowWrittenTheAndroidWayNamesTheFileFoundById() {
        val file = repo.photoFile("p2")
        assertEquals(file.absolutePath, repo.photoFileOf("p2").toString())
        assertTrue(file.parentFile!!.isDirectory)
    }

    @Test
    fun findingAFileByIdCreatesNothing() {
        val dir = File(context.filesDir, "photos")
        dir.deleteRecursively()

        repo.photoFileOf("p3")

        assertFalse("the photo folder is not created", dir.exists())
    }

    @Test
    fun deletingAPhotoTheServerHasRemovesTheFileAndQueuesTheDelete(): Unit = runBlocking {
        val at = 1_760_000_000_000
        db.houses().upsert(HouseEntity(id = "h1", label = "House", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at))
        val file = repo.photoFile("p4").apply { writeBytes(byteArrayOf(1)) }
        val row = PhotoEntity("p4", "h1", file.path, uploaded = true, createdAt = at)
        db.photos().upsert(row)

        repo.deletePhoto(row)

        assertFalse(file.exists())
        assertTrue("kept as a tombstone, so the next sync sends the delete", db.photos().get("p4")!!.deleted)
    }

    @Test
    fun anImportWritesAPhotoUnderItsIdAndSkipsAnIdThatWouldLeaveThePhotoFolder(): Unit = runBlocking {
        val at = 1_760_000_000_000
        db.houses().upsert(HouseEntity(id = "h1", label = "House", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at))
        val bytes = byteArrayOf(7, 8, 9)
        val actions = ImportActions(
            ImportMode.MERGE, emptyList(), emptyList(),
            photos = listOf(ExportPhoto("p5", "h1", "p5.jpg", at), ExportPhoto("../escape", "h1", "x.jpg", at)),
            photoSources = mapOf("p5" to "p5.jpg", "../escape" to "x.jpg"),
        )

        val result = repo.applyImport(actions, { _, _ -> }) { bytes }

        assertEquals(1, result.photos)
        assertEquals(1, result.photosSkipped)
        assertArrayEquals(bytes, repo.photoFile("p5").readBytes())
        assertFalse("nothing landed beside the photo folder", File(repo.photoDir().parentFile, "escape.jpg").exists())
        assertNull(db.photos().get("../escape"))
    }
}
