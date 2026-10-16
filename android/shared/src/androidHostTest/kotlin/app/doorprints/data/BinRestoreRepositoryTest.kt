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

import android.app.Application
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import app.doorprints.drive.photo.FailedPhoto
import app.doorprints.drive.photo.FetchedPhoto
import app.doorprints.drive.photo.GonePhoto
import app.doorprints.drive.photo.PhotoRestoreReason
import app.doorprints.drive.photo.PhotoRestoreReport
import app.doorprints.drive.photo.PhotoRestoreRequest
import app.doorprints.drive.photo.RestoreConnection
import app.doorprints.shared.model.PhotoMeta
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * *Restore* in *Recently deleted* with photos (S4b-BL-293), on real Room and real files: the candidate comes back first, and
 * the photos the fetch returns are stored under new ids, not uploaded, with the words they had; the ones it cannot give are
 * counted with their reason; whatever the fetch does, the candidate stays restored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BinRestoreRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

    private class MemorySecrets(name: String) : SecretStore {
        private val key = stringPreferencesKey(name)
        override fun get(settings: Preferences): String? = settings[key]
        override fun put(settings: MutablePreferences, apiKey: String) {
            settings[key] = apiKey
        }
        override fun clear(settings: MutablePreferences) {
            settings.remove(key)
        }
    }

    private class ClockRepository(db: AppDatabase, settings: SettingsStore, photoDir: String) : CommonRepository(
        db, settings, photoDir = photoDir, syncSoon = {}, apiFor = { _, _ -> error("no server") },
    ) {
        var clock = 0L
        override fun now(): Long = clock
    }

    private val settings by lazy {
        SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "bin.preferences_pb") },
            MemorySecrets("serverKey"), geminiSecrets = MemorySecrets("aiKey"),
        )
    }
    private fun repo(photoDir: String = File(tmp.root, "photos").path) = ClockRepository(db, settings, photoDir).also { it.clock = DELETED_AT + 5_000 }

    @After
    fun close() {
        db.close()
        scope.cancel()
    }

    private fun house(id: String, deleted: Boolean) = HouseEntity(
        id = id, label = id, lat = 13.0, lon = 80.0, createdAt = DELETED_AT - 1_000_000, updatedAt = DELETED_AT, deleted = deleted, dirty = false,
    )

    private fun gone(id: String, meta: PhotoMeta = PhotoMeta()) = GonePhoto(id, DELETED_AT + 10, "image/jpeg", "2026-10-01T10:00:00.000Z", meta)
    private fun bytes(n: Int) = ByteArray(n) { (it * 7).toByte() }

    @Test
    fun everyPhotoComesBackUnderANewIdNotUploadedWithItsWords() = runBlocking {
        db.houses().upsert(house("h1", deleted = true))
        val repo = repo()
        var asked: PhotoRestoreRequest? = null
        val out = repo.restoreHouseFromBin("h1") { req ->
            asked = req
            PhotoRestoreReport(
                RestoreConnection.OK,
                fetched = listOf(
                    FetchedPhoto(gone("p1", PhotoMeta("r1", listOf("KITCHEN"), "sink", 5)), bytes(10)),
                    FetchedPhoto(gone("p2"), bytes(20)),
                ),
            )
        }!!
        assertEquals("h1", asked!!.houseId)
        assertEquals(DELETED_AT, asked!!.houseDeletedAt)
        assertEquals(BinRestore(photosTotal = 2, photosBack = 2, photosFromDrive = 2), out)
        assertFalse(db.houses().get("h1")!!.deleted)
        assertTrue(db.houses().get("h1")!!.dirty)
        val rows = db.photos().observeForHouse("h1").first()
        assertEquals(2, rows.size)
        for (p in rows) {
            assertFalse(p.id in setOf("p1", "p2"))
            assertFalse(p.uploaded)
            assertTrue(File(tmp.root, "photos/${p.id}.jpg").exists())
        }
        val withWords = rows.first { it.caption == "sink" }
        assertEquals(listOf("KITCHEN"), withWords.tags)
        assertEquals("r1", withWords.roomId)
        assertTrue(withWords.metaDirty)
        assertEquals(10, File(tmp.root, "photos/${withWords.id}.jpg").length())
        assertEquals(java.time.Instant.parse("2026-10-01T10:00:00.000Z").toEpochMilli(), withWords.createdAt)
    }

    @Test
    fun somePhotosComeBackAndTheOthersSayWhy() = runBlocking {
        db.houses().upsert(house("h1", deleted = true))
        val out = repo().restoreHouseFromBin("h1") {
            PhotoRestoreReport(
                RestoreConnection.OK,
                fetched = listOf(FetchedPhoto(gone("p1"), bytes(10))),
                failed = listOf(FailedPhoto(gone("p2"), PhotoRestoreReason.MISSING), FailedPhoto(gone("p3"), PhotoRestoreReason.UNREADABLE)),
            )
        }!!
        assertEquals(BinRestore(3, 1, 1, listOf(PhotoRestoreReason.MISSING, PhotoRestoreReason.UNREADABLE)), out)
    }

    @Test
    fun driveNotConnectedRestoresTheCandidateAndSaysNothingOfPhotosItCannotKnow() = runBlocking {
        db.houses().upsert(house("h1", deleted = true))
        val out = repo().restoreHouseFromBin("h1") { PhotoRestoreReport.noConnection(it.known) }!!
        assertEquals(BinRestore.PLAIN, out)
        assertFalse(db.houses().get("h1")!!.deleted)
    }

    @Test
    fun aFetchThatThrowsNeverFailsTheCandidatesOwnRestore() = runBlocking {
        db.houses().upsert(house("h1", deleted = true))
        val out = repo().restoreHouseFromBin("h1") { error("boom") }!!
        assertEquals(BinRestore.PLAIN, out)
        assertFalse(db.houses().get("h1")!!.deleted)
    }

    @Test
    fun aPhotoThatCannotBeStoredIsCountedAsUnreadableAndTheCandidateStaysRestored() = runBlocking {
        db.houses().upsert(house("h1", deleted = true))
        val notAFolder = File(tmp.root, "photos-file").also { it.writeText("x") }
        val out = repo(notAFolder.path).restoreHouseFromBin("h1") {
            PhotoRestoreReport(RestoreConnection.OK, fetched = listOf(FetchedPhoto(gone("p1"), bytes(10))))
        }!!
        assertEquals(BinRestore(1, 0, 0, listOf(PhotoRestoreReason.UNREADABLE)), out)
        assertFalse(db.houses().get("h1")!!.deleted)
    }

    @Test
    fun aHouseThatIsNotDeletedOrMissingIsRefusedAndDriveIsNotAsked() = runBlocking {
        db.houses().upsert(house("live", deleted = false))
        var asked = false
        assertNull(repo().restoreHouseFromBin("live") { asked = true; PhotoRestoreReport.noConnection(it.known) })
        assertNull(repo().restoreHouseFromBin("missing") { asked = true; PhotoRestoreReport.noConnection(it.known) })
        assertFalse(asked)
        assertNotNull(db.houses().get("live"))
    }

    private companion object {
        const val DELETED_AT = 1_791_000_000_000L
    }
}
