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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.doorprints.data.Repository.AddPhotoResult
import app.doorprints.shared.api.ApiClient
import app.doorprints.shared.model.MAX_PHOTOS_PER_HOUSE
import app.doorprints.shared.model.PhotoMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Android's [Repository]: the common [CommonRepository] (S4b-BL-32) with the photo files in the app's private
 * `photos` folder, WorkManager for "sync soon" and the app-wide API client, plus what only Android has: adding a photo
 * from a `Uri` ([addPhoto]) and the photo files as `java.io.File` for the exporters ([photoDir], [photoFile]).
 */
@OptIn(ExperimentalUuidApi::class)
class AndroidRepository(
    private val context: Context,
    db: AppDatabase,
    settings: SettingsStore,
    /** Where sync goes (S4b-BL-70); null for the default, the configured server over [apiFor]. A test's fake backend. */
    syncBackendFor: (suspend (AppSettings) -> SyncBackend?)? = null,
    /** The API client for a server address and key: the app-wide HTTP stack ([Api.client]); a test's fake engine. */
    apiFor: (serverUrl: String, apiKey: String) -> ApiClient = Api::client,
) : CommonRepository(
    db, settings,
    photoDir = File(context.filesDir, "photos").path,
    syncSoon = { SyncWorker.syncSoon(context) },
    apiFor = apiFor,
    geminiFor = Api::gemini,
    syncBackendFor = syncBackendFor,
    openAiFor = Api::openAi,
    anthropicFor = Api::anthropic,
    emulatorHostAllowed = true,
) {
    /** The folder holding every photo file, for the exporters and the backup. */
    fun photoDir() = File(photoDirPath().toString())

    /** The file a photo row's bytes live in, for the exporters. */
    fun photoFile(id: String) = File(photoPath(id).toString())

    /**
     * Copies, shrinks (max 1600 px) and stores a photo locally; it uploads on the next sync.
     *
     * Privacy (docs/09 L6): the photo is decoded to pixels and re-encoded with Bitmap.compress, which writes a bare
     * JPEG without any Exif block, so the camera's GPS position, time and device model are not kept. The Exif
     * orientation is applied to the pixels first. The server strips metadata again for other clients.
     *
     * [tags] (slice 5) are the photo's tags from the start (the Moving in card's *Add a photo*: MOVE_IN), stamped now
     * and sent with `PUT /api/photos/{id}/meta` once the photo is uploaded.
     */
    suspend fun addPhoto(houseId: String, source: Uri, tags: List<String> = emptyList()): AddPhotoResult = withContext(Dispatchers.IO) {
        if (db.photos().countLive(houseId) >= MAX_PHOTOS_PER_HOUSE) return@withContext AddPhotoResult.LIMIT_REACHED
        val id = Uuid.random().toString()
        val out = photoFile(id)
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext AddPhotoResult.UNREADABLE
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        var bitmap = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@withContext AddPhotoResult.UNREADABLE
        val rotation = resolver.openInputStream(source)?.use { ExifInterface(it).rotationDegrees } ?: 0
        if (rotation != 0) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val stamp = now()
        val row = PhotoEntity(id, houseId, out.absolutePath, uploaded = false, createdAt = stamp)
        db.photos().upsert(
            if (tags.isEmpty()) row else row.withMeta(PhotoMeta(tags = tags, metaUpdatedAt = stamp), dirty = true),
        )
        SyncWorker.syncSoon(context)
        AddPhotoResult.ADDED
    }

    /** [CommonRepository.discardUncommittedPhotoFiles] for `java.io.File`s (`RepositoryTransactionTest`). */
    @JvmName("discardUncommittedFiles")
    internal suspend fun discardUncommittedPhotoFiles(written: Map<File, String>): Unit =
        super.discardUncommittedPhotoFiles(written.mapKeys { Path(it.key.path) })
}
