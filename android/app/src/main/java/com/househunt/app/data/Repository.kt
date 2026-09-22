package com.househunt.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class Repository(
    private val context: Context,
    private val db: AppDatabase,
    val settings: SettingsStore,
) {
    val houses = db.houses().observeAll()
    val visitCounts = db.visits().observeCounts()

    fun house(id: String) = db.houses().observe(id)
    fun visitsFor(houseId: String) = db.visits().observeForHouse(houseId)
    fun photosFor(houseId: String) = db.photos().observeForHouse(houseId)

    suspend fun houseSnapshot(): List<HouseEntity> = db.houses().all()
    suspend fun getHouse(id: String) = db.houses().get(id)

    suspend fun saveHouse(house: HouseEntity) {
        db.houses().upsert(house.copy(updatedAt = System.currentTimeMillis(), dirty = true))
        SyncWorker.syncSoon(context)
    }

    suspend fun deleteHouse(id: String) {
        val house = db.houses().get(id) ?: return
        saveHouse(house.copy(deleted = true))
    }

    suspend fun saveVisit(visit: VisitEntity) {
        db.visits().upsert(visit.copy(updatedAt = System.currentTimeMillis(), dirty = true))
        SyncWorker.syncSoon(context)
    }

    suspend fun getVisit(id: String) = db.visits().get(id)

    suspend fun deleteVisit(id: String) {
        val visit = db.visits().get(id) ?: return
        saveVisit(visit.copy(deleted = true))
    }

    /** Records a "been here" visit for a house, now. */
    suspend fun markVisitedNow(house: HouseEntity) {
        val now = System.currentTimeMillis()
        saveVisit(
            VisitEntity(
                id = UUID.randomUUID().toString(), houseId = house.id, lat = house.lat, lon = house.lon,
                street = house.street, arrivedAt = now, source = VisitSource.MANUAL, updatedAt = now,
            )
        )
    }

    data class StreetInfo(val street: String, val houses: Int, val visits: Int, val firstVisit: Long?)

    suspend fun streetInfo(street: String) = StreetInfo(
        street,
        db.houses().countOnStreet(street),
        db.visits().countOnStreet(street),
        db.visits().firstOnStreet(street),
    )

    private fun photoDir() = File(context.filesDir, "photos").apply { mkdirs() }

    enum class AddPhotoResult { ADDED, LIMIT_REACHED, UNREADABLE }

    /**
     * Copies, shrinks (max 1600 px) and stores a photo locally; it uploads on the next sync.
     *
     * Privacy (docs/09 L6): the photo is decoded to pixels and re-encoded with Bitmap.compress, which writes a bare
     * JPEG without any Exif block, so the camera's GPS position, time and device model are not kept. The Exif
     * orientation is applied to the pixels first. The server strips metadata again for other clients.
     */
    suspend fun addPhoto(houseId: String, source: Uri): AddPhotoResult = withContext(Dispatchers.IO) {
        if (db.photos().countLive(houseId) >= MAX_PHOTOS_PER_HOUSE) return@withContext AddPhotoResult.LIMIT_REACHED
        val id = UUID.randomUUID().toString()
        val out = File(photoDir(), "$id.jpg")
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
        db.photos().upsert(PhotoEntity(id, houseId, out.absolutePath, uploaded = false, createdAt = System.currentTimeMillis()))
        SyncWorker.syncSoon(context)
        AddPhotoResult.ADDED
    }

    /**
     * Deletes the local file now. A photo the server already has is queued (deleted = 1) and the delete is sent on
     * the next sync, even if the phone is offline now (threat model F-15).
     */
    suspend fun deletePhoto(photo: PhotoEntity) = withContext(Dispatchers.IO) {
        File(photo.path).delete()
        if (photo.uploaded) {
            db.photos().markDeleted(photo.id)
            SyncWorker.syncSoon(context)
        } else {
            db.photos().delete(photo.id)
        }
    }

    suspend fun testConnection(): Result<StatsDto> = withContext(Dispatchers.IO) {
        val s = settings.current()
        runCatching { ApiClient(s.serverUrl, s.apiKey).stats() }
    }

    // ---- AI features (optional; hidden unless the server reports enabled = true) ----

    private val _aiEnabled = MutableStateFlow(false)
    val aiEnabled: StateFlow<Boolean> = _aiEnabled.asStateFlow()

    /** Asks the server whether AI features are on. Offline or not configured means off. */
    suspend fun refreshAiStatus(): Boolean = withContext(Dispatchers.IO) {
        val s = settings.current()
        val enabled = s.serverConfigured && runCatching { ApiClient(s.serverUrl, s.apiKey).aiStatus().enabled }
            .getOrDefault(false)
        _aiEnabled.value = enabled
        enabled
    }

    private suspend fun <T> withApi(block: (ApiClient) -> T): T = withContext(Dispatchers.IO) {
        val s = settings.current()
        check(s.serverConfigured) { "Server not configured" }
        block(ApiClient(s.serverUrl, s.apiKey))
    }

    suspend fun extractListing(text: String): HouseDraftDto = withApi { it.extractListing(text) }
    suspend fun ask(question: String): AskResponseDto = withApi { it.ask(question) }
    suspend fun planVisits(request: PlanRequest): PlanResponseDto = withApi { it.planVisits(request) }

    /**
     * Two-way sync: push local changes, then pull everything the server has changed since last time.
     * Conflicts are "last edit wins" (by updatedAt), on both sides. Houses and visits always sync; photo transfers
     * only when [photosAllowed] (the caller checks for an unmetered network when the user asked for Wi-Fi only).
     * Throws on failure; see [SyncOutcome.fromError].
     */
    suspend fun sync(photosAllowed: Boolean = true): SyncOutcome = withContext(Dispatchers.IO) {
        val s = settings.current()
        if (!s.serverConfigured) return@withContext SyncOutcome(SyncOutcome.Kind.NOT_CONFIGURED)
        val api = ApiClient(s.serverUrl, s.apiKey)

        var pushed = 0
        for (h in db.houses().dirty()) {
            api.putHouse(h.toDto()); db.houses().markClean(h.id, h.updatedAt); pushed++
        }
        for (v in db.visits().dirty()) {
            api.putVisit(v.toDto()); db.visits().markClean(v.id, v.updatedAt); pushed++
        }
        // Deletes are tiny, so they go out on any network.
        for (p in db.photos().pendingDelete()) {
            api.deletePhoto(p.id); db.photos().delete(p.id); pushed++
        }
        var photosWaiting = 0
        for (p in db.photos().pendingUpload()) {
            val file = File(p.path)
            if (!file.exists() || db.houses().get(p.houseId)?.deleted != false) continue
            if (!photosAllowed) {
                photosWaiting++; continue
            }
            try {
                api.uploadPhoto(p.houseId, p.id, file)
            } catch (e: ApiException) {
                // Permanent rejections (not an image, too big, over the per-house limit) would fail on every sync;
                // keep the photo on this phone only and move on. Anything else (5xx, auth) aborts the sync.
                if (e.kind != ApiException.Kind.CLIENT && e.kind != ApiException.Kind.CONFLICT &&
                    e.kind != ApiException.Kind.NOT_FOUND
                ) throw e
            }
            db.photos().upsert(p.copy(uploaded = true)); pushed++
        }

        val cursors = settings.cursors()
        var houseCursor = cursors.house
        var visitCursor = cursors.visit
        var pulled = 0
        for (dto in api.housesSince(houseCursor)) {
            houseCursor = maxOf(houseCursor, dto.syncVersion)
            val local = db.houses().get(dto.id)
            val incoming = dto.toEntity()
            if (local != null && local.dirty && local.updatedAt > incoming.updatedAt) continue
            db.houses().upsert(incoming); pulled++
        }
        for (dto in api.visitsSince(visitCursor)) {
            visitCursor = maxOf(visitCursor, dto.syncVersion)
            val local = db.visits().get(dto.id)
            val incoming = dto.toEntity()
            if (local != null && local.dirty && local.updatedAt > incoming.updatedAt) continue
            db.visits().upsert(incoming); pulled++
        }
        settings.saveCursors(houseCursor, visitCursor)

        // Photos: apply delete tombstones from other devices, download new photos of live houses.
        var photoCursor = cursors.photo
        var photosComplete = true
        for (change in api.photoChangesSince(cursors.photo)) {
            val local = db.photos().get(change.id)
            if (change.deleted) {
                if (local != null) {
                    File(local.path).delete(); db.photos().delete(change.id); pulled++
                }
            } else if (local == null) {
                val house = db.houses().get(change.houseId)
                if (house != null && !house.deleted) {
                    if (!photosAllowed) {
                        photosWaiting++; photosComplete = false; continue
                    }
                    val out = File(photoDir(), "${change.id}.jpg")
                    out.writeBytes(api.downloadPhoto(change.id))
                    db.photos().upsert(PhotoEntity(change.id, change.houseId, out.absolutePath, true, System.currentTimeMillis()))
                    pulled++
                }
            }
            // Only move the cursor past rows that are fully handled, so skipped downloads are retried on Wi-Fi.
            if (photosComplete) photoCursor = maxOf(photoCursor, change.syncVersion)
        }
        settings.savePhotoCursor(photoCursor)

        SyncOutcome(SyncOutcome.Kind.OK, pushed = pushed, pulled = pulled, photosWaiting = photosWaiting)
    }
}
