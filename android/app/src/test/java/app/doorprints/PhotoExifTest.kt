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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.doorprints.data.AndroidRepository
import app.doorprints.data.AppDatabase
import app.doorprints.data.DatabaseFile
import app.doorprints.data.Repository.AddPhotoResult
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.data.create
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * PRV-008 / TC-U-11 on Android: a photo added to a house is stored without the camera's Exif block (GPS position,
 * device make), and its Exif orientation is applied to the pixels first. The seam is `AndroidRepository.addPhoto`,
 * given a `file:` Uri of a JPEG that the test builds in its cache (a generated bitmap, then the GPS and make written
 * with `ExifInterface`; no binary fixture is committed). The first assertions prove the source really had the tags,
 * so "absent in the output" cannot pass by a fixture that never had them. Robolectric's native graphics mode runs the
 * real decoder and encoder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhotoExifTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var repo: AndroidRepository
    private val sources = mutableListOf<File>()

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
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        context.deleteDatabase(DatabaseFile.NAME)
        db = AppDatabase.create(context)
        val settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "exif-test.preferences_pb") },
            MemorySecrets,
        )
        repo = AndroidRepository(context, db, settings)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
        File(context.filesDir, "exif-test.preferences_pb").delete()
        sources.forEach { it.delete() }
    }

    /** A 64 x 48 JPEG with the Exif tags a phone camera writes: GPS 12.9716 N 77.5946 E, make and model, an orientation. */
    private fun cameraJpeg(orientation: Int = ExifInterface.ORIENTATION_NORMAL): File {
        val file = File.createTempFile("camera", ".jpg", context.cacheDir).also { sources += it }
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setLatLong(12.9716, 77.5946)
            setAttribute(ExifInterface.TAG_MAKE, "CameraMaker")
            setAttribute(ExifInterface.TAG_MODEL, "CameraModel")
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file
    }

    private fun storedPhoto(): File {
        val rows = runBlocking { db.photos().liveForHouse("h1") }
        assertEquals("one photo row", 1, rows.size)
        return repo.photoFile(rows.single().id)
    }

    private fun contains(bytes: ByteArray, text: String): Boolean =
        String(bytes, Charsets.ISO_8859_1).contains(text)

    @Test
    fun theStoredPhotoHasNoGpsAndNoExifBlockWhileTheSourceHadBoth(): Unit = runBlocking {
        val source = cameraJpeg()
        // The fixture is real: GPS, make and an Exif block are in the source.
        val before = ExifInterface(source.absolutePath).latLong
        assertNotNull("the fixture has a GPS position", before)
        assertEquals(12.9716, before!![0], 0.001)
        assertEquals(77.5946, before[1], 0.001)
        assertTrue("the fixture has an Exif block", contains(source.readBytes(), "Exif"))
        assertTrue("the fixture has the make", contains(source.readBytes(), "CameraMaker"))

        assertEquals(AddPhotoResult.ADDED, repo.addPhoto("h1", Uri.fromFile(source)))

        val stored = storedPhoto()
        assertTrue("the photo was stored", stored.length() > 0)
        assertNotNull("it is still a JPEG that decodes", BitmapFactory.decodeFile(stored.absolutePath))
        val exif = ExifInterface(stored.absolutePath)
        assertNull("no GPS position", exif.latLong)
        assertNull("no GPS latitude tag", exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull("no GPS longitude tag", exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertNull("no device make", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull("no device model", exif.getAttribute(ExifInterface.TAG_MODEL))
        val bytes = stored.readBytes()
        assertTrue("no Exif block at all", !contains(bytes, "Exif"))
        assertTrue("no make in the bytes", !contains(bytes, "CameraMaker"))
        assertTrue("it is not the source copied", !bytes.contentEquals(source.readBytes()))
    }

    @Test
    fun theExifOrientationIsAppliedToThePixelsBeforeTheTagIsDropped(): Unit = runBlocking {
        val source = cameraJpeg(ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))

        assertEquals(AddPhotoResult.ADDED, repo.addPhoto("h1", Uri.fromFile(source)))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(storedPhoto().absolutePath, bounds)
        // 64 wide x 48 high, rotated a quarter turn: 48 wide x 64 high. The tag is gone, the pixels are upright.
        assertEquals(48, bounds.outWidth)
        assertEquals(64, bounds.outHeight)
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface(storedPhoto().absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
    }
}
