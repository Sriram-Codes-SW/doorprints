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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * *Retry photos* under the result of a restore (S4b-BL-293), on real Room and real files: a retry asks only for the photos
 * that still have no bytes and failed for a reason a second try can fix, stores each photo at most once even though the sync
 * file lists all of them again, and keeps the words adding up. The twin of the website's `retryPhotos` specs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BinRetryRepositoryTest {
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
        override fun now(): Long = DELETED_AT + 5_000
    }

    private val settings by lazy {
        SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "retry.preferences_pb") },
            MemorySecrets("serverKey"), geminiSecrets = MemorySecrets("aiKey"),
        )
    }
    private val repo by lazy { ClockRepository(db, settings, File(tmp.root, "photos").path) }

    @After
    fun close() {
        db.close()
        scope.cancel()
    }

    private fun house() = HouseEntity(
        id = "h1", label = "h1", lat = 13.0, lon = 80.0, createdAt = DELETED_AT - 1_000_000, updatedAt = DELETED_AT, deleted = true, dirty = false,
    )

    private fun gone(id: String) = GonePhoto(id, DELETED_AT + 10, "image/jpeg", "2026-10-01T10:00:00.000Z")
    private fun fetched(id: String) = FetchedPhoto(gone(id), ByteArray(10) { (it * 3).toByte() })
    private suspend fun stored() = db.photos().observeForHouse("h1").first().size

    @Test
    fun aRestoreCutShortByNoConnectionOffersARetryOfJustTheMissingPhoto() = runBlocking {
        db.houses().upsert(house())
        val out = repo.restoreHouseFromBin("h1") {
            PhotoRestoreReport(RestoreConnection.OFFLINE, listOf(fetched("p1")), listOf(FailedPhoto(gone("p2"), PhotoRestoreReason.OFFLINE)))
        }!!
        assertEquals(2, out.photosTotal)
        assertEquals(1, out.photosBack)
        assertEquals(listOf(PhotoRestoreReason.OFFLINE), out.reasons)
        val retry = out.retry!!
        assertEquals(listOf("p2"), retry.known.map { it.id })
        assertEquals(setOf("p1"), retry.settled)
        assertEquals(1, stored())
    }

    @Test
    fun aRetryAsksForTheMissingPhotoOnlyAndStoresEachPhotoOnceWhenTheFileListsThemAllAgain() = runBlocking {
        db.houses().upsert(house())
        val first = repo.restoreHouseFromBin("h1") {
            PhotoRestoreReport(RestoreConnection.OFFLINE, listOf(fetched("p1")), listOf(FailedPhoto(gone("p2"), PhotoRestoreReason.OFFLINE)))
        }!!
        var asked: PhotoRestoreRequest? = null
        val second = repo.retryBinPhotos(first.retry!!) { req ->
            asked = req
            // The sync file names both photos again; p1 is already stored here.
            PhotoRestoreReport(RestoreConnection.OK, listOf(fetched("p1"), fetched("p2")))
        }
        assertEquals(listOf("p2"), asked!!.known.map { it.id })
        assertEquals(2, stored())
        assertEquals(BinRestore(photosTotal = 2, photosBack = 2, photosFromDrive = 2), second)
        assertNull(second.retry)
    }

    @Test
    fun aRetryThatStillGetsNoAnswerKeepsOnlyTheStillMissingPhotosAndStoresNothing() = runBlocking {
        db.houses().upsert(house())
        val first = repo.restoreHouseFromBin("h1") {
            PhotoRestoreReport(RestoreConnection.OFFLINE, listOf(fetched("p1")), listOf(FailedPhoto(gone("p2"), PhotoRestoreReason.OFFLINE), FailedPhoto(gone("p3"), PhotoRestoreReason.OFFLINE)))
        }!!
        val second = repo.retryBinPhotos(first.retry!!) {
            PhotoRestoreReport(RestoreConnection.OFFLINE, emptyList(), listOf(FailedPhoto(gone("p1"), PhotoRestoreReason.OFFLINE), FailedPhoto(gone("p2"), PhotoRestoreReason.OFFLINE), FailedPhoto(gone("p3"), PhotoRestoreReason.OFFLINE)))
        }
        assertEquals(1, stored())
        assertEquals(listOf("p2", "p3"), second.retry!!.known.map { it.id })
        assertEquals(3, second.photosTotal)
        assertEquals(1, second.photosBack)
        assertEquals(listOf(PhotoRestoreReason.OFFLINE), second.reasons)
    }

    @Test
    fun aReasonNoRetryCanFixIsNeverRetriedAndStillCountsInTheWords() = runBlocking {
        db.houses().upsert(house())
        val first = repo.restoreHouseFromBin("h1") {
            PhotoRestoreReport(RestoreConnection.OFFLINE, emptyList(), listOf(FailedPhoto(gone("p1"), PhotoRestoreReason.PAST_WINDOW), FailedPhoto(gone("p2"), PhotoRestoreReason.OFFLINE)))
        }!!
        assertEquals(listOf("p2"), first.retry!!.known.map { it.id })
        val second = repo.retryBinPhotos(first.retry!!) {
            PhotoRestoreReport(RestoreConnection.OK, listOf(fetched("p2")), listOf(FailedPhoto(gone("p1"), PhotoRestoreReason.PAST_WINDOW)))
        }
        assertEquals(2, second.photosTotal)
        assertEquals(1, second.photosBack)
        assertEquals(listOf(PhotoRestoreReason.PAST_WINDOW), second.reasons)
        assertNull(second.retry)
        assertEquals(1, stored())
    }

    @Test
    fun aMissingOrUnreadableCopyIsNotOfferedARetry() = runBlocking {
        db.houses().upsert(house())
        val out = repo.restoreHouseFromBin("h1") {
            PhotoRestoreReport(RestoreConnection.OK, emptyList(), listOf(FailedPhoto(gone("p1"), PhotoRestoreReason.MISSING), FailedPhoto(gone("p2"), PhotoRestoreReason.UNREADABLE)))
        }!!
        assertNull(out.retry)
    }

    @Test
    fun aFetchThatThrowsOnRetryKeepsTheRetryAndNeverFails() = runBlocking {
        db.houses().upsert(house())
        val first = repo.restoreHouseFromBin("h1") {
            PhotoRestoreReport(RestoreConnection.OFFLINE, emptyList(), listOf(FailedPhoto(gone("p2"), PhotoRestoreReason.OFFLINE)))
        }!!
        val second = repo.retryBinPhotos(first.retry!!) { error("boom") }
        assertEquals(listOf("p2"), second.retry!!.known.map { it.id })
        assertEquals(listOf(PhotoRestoreReason.NOT_CONNECTED), second.reasons)
        assertEquals(0, stored())
    }

    @Test
    fun aRetryBeforeAnyPhotoWasListedFindsThemAndTheCandidateIsNotTouched() = runBlocking {
        db.houses().upsert(house().copy(deleted = false, updatedAt = DELETED_AT + 1))
        // Two photos were noted but Drive could not even be listed: nothing known, a retry may still help.
        val noted = BinRetry("h1", DELETED_AT, noted = 2, known = emptyList())
        val offline = repo.retryBinPhotos(noted) { PhotoRestoreReport.noConnection(it.known, RestoreConnection.OFFLINE) }
        assertEquals(listOf(PhotoRestoreReason.OFFLINE), offline.reasons)
        assertNotNull(offline.retry)
        val back = repo.retryBinPhotos(offline.retry!!) { PhotoRestoreReport(RestoreConnection.OK, listOf(fetched("p1"), fetched("p2"))) }
        assertEquals(BinRestore(2, 2, 2), back)
        assertEquals(2, stored())
        assertEquals(DELETED_AT + 1, db.houses().get("h1")!!.updatedAt)
    }

    private companion object {
        const val DELETED_AT = 1_791_000_000_000L
    }
}
