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

package app.doorprints.drive.wiring

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.doorprints.data.AppDatabase
import app.doorprints.data.DatabaseFile
import app.doorprints.data.HouseEntity
import app.doorprints.data.PhotoEntity
import app.doorprints.data.RecordEntity
import app.doorprints.data.VisitEntity
import app.doorprints.data.create
import app.doorprints.shared.sync.SyncKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
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

/** The phone's rows as the Drive sync reads them, from the app's own Room database (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomSyncRowsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val at = 1_760_000_000_000
    private val DEV1 = "0123456789abcdef0123456789abcdef"
    private val DEV_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val DEV_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val sizes = mutableMapOf<String, Long?>()

    private fun rows() = RoomSyncRows(db, { DEV1 }) { sizes[it] }

    @Before
    fun setUp() {
        context.deleteDatabase(DatabaseFile.NAME)
        db = AppDatabase.create(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun house(id: String, deleted: Boolean = false, updatedAt: Long = at) =
        HouseEntity(id = id, label = "House $id", lat = 12.9, lon = 77.6, createdAt = at, updatedAt = updatedAt, deleted = deleted)

    @Test
    fun liveAndDeletedHousesAreBothThereWithTheirStamps() = runBlocking {
        db.houses().upsert(house("h1"))
        db.houses().upsert(house("h2", deleted = true, updatedAt = at + 10))
        val all = rows().all().filter { it.kind == SyncKind.HOUSES }.associateBy { it.key }
        assertEquals(setOf("h1", "h2"), all.keys)
        assertFalse(all.getValue("h1").stamp.deleted)
        assertTrue("a deleted house must reach the other devices", all.getValue("h2").stamp.deleted)
        assertEquals(at + 10, all.getValue("h2").stamp.updatedAt)
        assertEquals(DEV1, all.getValue("h1").stamp.by)
        assertEquals("House h1", all.getValue("h1").json["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun visitsKeepTheirTombstonesToo() = runBlocking {
        db.houses().upsert(house("h1"))
        db.visits().upsert(VisitEntity(id = "v1", houseId = "h1", lat = 1.0, lon = 2.0, arrivedAt = at, updatedAt = at))
        db.visits().upsert(VisitEntity(id = "v2", houseId = "h1", lat = 1.0, lon = 2.0, arrivedAt = at, updatedAt = at + 5, deleted = true))
        val visits = rows().all().filter { it.kind == SyncKind.VISITS }.associateBy { it.key }
        assertEquals(setOf("v1", "v2"), visits.keys)
        assertTrue(visits.getValue("v2").stamp.deleted)
        assertFalse(visits.getValue("v1").stamp.deleted)
    }

    @Test
    fun recordsComeKeyedByTheirTypeAndTombstonesStay() = runBlocking {
        db.records().upsert(RecordEntity("area", "a_00000001", """{"name":"Old","lat":1.0,"lon":2.0,"radiusM":900}""", at))
        db.records().upsert(RecordEntity("area", "a_00000002", """{"name":"Gone","lat":1.0,"lon":2.0,"radiusM":900}""", at + 1, deleted = true))
        val records = rows().all().filter { it.kind == SyncKind.RECORDS }.associateBy { it.key }
        assertEquals(setOf("area/a_00000001", "area/a_00000002"), records.keys)
        assertTrue(records.getValue("area/a_00000002").stamp.deleted)
    }

    @Test
    fun aLivePhotoCarriesItsSizeAndTheLaterOfAddedAndEdited() = runBlocking {
        db.houses().upsert(house("h1"))
        sizes["p1"] = 4_321
        db.photos().upsert(PhotoEntity("p1", "h1", "/x/p1.jpg", uploaded = true, createdAt = at, tags = listOf("kitchen"), caption = "Nice", metaUpdatedAt = at + 50))
        val photo = rows().all().single { it.kind == SyncKind.PHOTOS }
        assertEquals("p1", photo.key)
        assertEquals(at + 50, photo.stamp.updatedAt)
        assertEquals(4_321L, (photo.json["sizeBytes"] as JsonPrimitive).content.toLong())
        assertEquals("image/jpeg", photo.json["contentType"]!!.jsonPrimitive.content)
        assertEquals("Nice", photo.json["caption"]!!.jsonPrimitive.content)
    }

    @Test
    fun aPhotoNeverEditedIsStampedWhenItWasAdded() = runBlocking {
        db.houses().upsert(house("h1"))
        db.photos().upsert(PhotoEntity("p1", "h1", "/x/p1.jpg", createdAt = at))
        assertEquals(at, rows().all().single { it.kind == SyncKind.PHOTOS }.stamp.updatedAt)
    }

    @Test
    fun aPhotoQueuedForDeletionIsLeftOutOfTheRowsButAnswersByIdAsDeleted() = runBlocking {
        db.houses().upsert(house("h1"))
        db.photos().upsert(PhotoEntity("p1", "h1", "/x/p1.jpg", createdAt = at, deleted = true))
        assertTrue(rows().all().none { it.kind == SyncKind.PHOTOS })
        // The backend writes the tombstone itself, from this row.
        val dto = rows().photo("p1")
        assertNotNull(dto)
        assertTrue(dto!!.deleted)
        assertNull(rows().photo("nope"))
    }

    @Test
    fun aMissingFileHasNoSizeButTheRowIsStillThere() = runBlocking {
        db.houses().upsert(house("h1"))
        db.photos().upsert(PhotoEntity("p1", "h1", "/x/p1.jpg", createdAt = at))
        sizes["p1"] = null
        val photo = rows().all().single { it.kind == SyncKind.PHOTOS }
        assertNull(photo.json["sizeBytes"])
    }

    @Test
    fun anEnormousSizeIsCappedToWhatTheRowCanHold() = runBlocking {
        db.houses().upsert(house("h1"))
        db.photos().upsert(PhotoEntity("p1", "h1", "/x/p1.jpg", createdAt = at))
        sizes["p1"] = Long.MAX_VALUE
        assertEquals(Int.MAX_VALUE.toLong(), (rows().all().single { it.kind == SyncKind.PHOTOS }.json["sizeBytes"] as JsonPrimitive).content.toLong())
    }

    @Test
    fun aRowTheSyncFileRefusesIsLeftOutNotFatal() = runBlocking {
        db.houses().upsert(house("h1"))
        // An id with a space is not a valid record id: the file's schema would refuse the whole file for it.
        db.houses().upsert(house("bad id"))
        val keys = rows().all().filter { it.kind == SyncKind.HOUSES }.map { it.key }
        assertEquals(listOf("h1"), keys)
    }

    @Test
    fun aWriterIdTheFileRefusesStopsTheReadInsteadOfWritingAnEmptyFile() {
        runBlocking { db.houses().upsert(house("h1")) }
        val e = org.junit.Assert.assertThrows(IllegalStateException::class.java) { runBlocking { RoomSyncRows(db, { "dev1" }) { null }.all() } }
        assertTrue(e.message!!.contains("device id"))
    }

    @Test
    fun theWriterIsTheDeviceNow() = runBlocking {
        db.houses().upsert(house("h1"))
        var id = DEV_A
        val r = RoomSyncRows(db, { id }) { null }
        assertEquals(DEV_A, r.all().single().stamp.by)
        id = DEV_B
        assertEquals(DEV_B, r.all().single().stamp.by)
    }
}
