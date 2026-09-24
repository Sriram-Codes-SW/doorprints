package app.doorprints.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.doorprints.data.Repository.AddPhotoResult
import app.doorprints.data.Repository.ImportResult
import app.doorprints.data.Repository.LocalRows
import app.doorprints.data.Repository.LocalVersions
import app.doorprints.data.Repository.StreetInfo
import app.doorprints.data.Repository.UndoResult
import app.doorprints.export.CopyUndo
import app.doorprints.shared.api.ApiClient
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.api.ApiException
import app.doorprints.shared.api.AskResponseDto
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.api.PlanRequest
import app.doorprints.shared.api.PlanResponseDto
import app.doorprints.shared.api.StatsDto
import app.doorprints.shared.export.BackupValidation
import app.doorprints.shared.export.ImportActions
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.model.MAX_PHOTOS_PER_HOUSE
import app.doorprints.shared.model.VisitSource
import app.doorprints.shared.sync.SyncOutcome
import app.doorprints.shared.sync.SyncRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.util.UUID

/**
 * Android's [Repository] (CMP-4 P4c: the interface is common, in `:shared`): Room through the framework SQLite, the
 * photo files in the app's private `photos` folder, WorkManager for "sync soon" and the app-wide API client. Its
 * transactions and the export's change flow use Room's common API ([withImmediateTransaction],
 * [localTablesChanged]), so the database code here no longer needs Android (S4b-BL-23).
 */
class AndroidRepository(
    private val context: Context,
    private val db: AppDatabase,
    override val settings: SettingsStore,
    /** The API client for a server address and key: the app-wide HTTP stack ([Api.client]); a test's fake engine. */
    private val apiFor: (serverUrl: String, apiKey: String) -> ApiClient = Api::client,
) : Repository {
    override val houses = db.houses().observeAll()
    override val visitCounts = db.visits().observeCounts()

    override fun house(id: String) = db.houses().observe(id)
    override fun visitsFor(houseId: String) = db.visits().observeForHouse(houseId)
    override fun photosFor(houseId: String) = db.photos().observeForHouse(houseId)

    override suspend fun houseSnapshot(): List<HouseEntity> = db.houses().all()
    override suspend fun getHouse(id: String) = db.houses().get(id)

    override suspend fun saveHouse(house: HouseEntity) {
        db.houses().upsert(house.copy(updatedAt = System.currentTimeMillis(), dirty = true))
        SyncWorker.syncSoon(context)
    }

    override suspend fun deleteHouse(id: String) {
        val house = db.houses().get(id) ?: return
        saveHouse(house.copy(deleted = true))
    }

    override suspend fun saveVisit(visit: VisitEntity) {
        db.visits().upsert(visit.copy(updatedAt = System.currentTimeMillis(), dirty = true))
        SyncWorker.syncSoon(context)
    }

    override suspend fun getVisit(id: String) = db.visits().get(id)

    override suspend fun deleteVisit(id: String) {
        val visit = db.visits().get(id) ?: return
        saveVisit(visit.copy(deleted = true))
    }

    /** Records a "been here" visit for a house, now. */
    override suspend fun markVisitedNow(house: HouseEntity) {
        val now = System.currentTimeMillis()
        saveVisit(
            VisitEntity(
                id = UUID.randomUUID().toString(), houseId = house.id, lat = house.lat, lon = house.lon,
                street = house.street, arrivedAt = now, source = VisitSource.MANUAL, updatedAt = now,
            )
        )
    }

    override suspend fun streetInfo(street: String) = StreetInfo(
        street,
        db.houses().countOnStreet(street),
        db.visits().countOnStreet(street),
        db.visits().firstOnStreet(street),
    )

    fun photoDir() = File(context.filesDir, "photos").apply { mkdirs() }

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
    override suspend fun deletePhoto(photo: PhotoEntity): Unit = withContext(Dispatchers.IO) {
        File(photo.path).delete()
        if (photo.uploaded) {
            db.photos().markDeleted(photo.id)
            SyncWorker.syncSoon(context)
        } else {
            db.photos().delete(photo.id)
        }
    }

    override suspend fun testConnection(): Result<StatsDto> = withContext(Dispatchers.IO) {
        val s = settings.current()
        runCatching { Api.client(s.serverUrl, s.apiKey).stats() }
    }

    // ---- AI features (optional; hidden unless the server reports enabled = true) ----

    private val _aiEnabled = MutableStateFlow(false)
    override val aiEnabled: StateFlow<Boolean> = _aiEnabled.asStateFlow()

    /**
     * Asks the server whether AI features are on. No server set up means off, and so does a real `enabled: false`
     * answer. A failed request (offline, a timeout, a server error) keeps what was known (UX review, whole-app
     * audit): turning the Assistant tab off on every network error removed it while the user was on it.
     */
    override suspend fun refreshAiStatus(): Boolean = withContext(Dispatchers.IO) {
        val s = settings.current()
        val enabled = if (!s.serverConfigured) {
            false
        } else {
            runCatching { Api.client(s.serverUrl, s.apiKey).aiStatus().enabled }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _aiEnabled.value
            }
        }
        _aiEnabled.value = enabled
        enabled
    }

    private suspend fun <T> withApi(block: suspend (ApiClient) -> T): T = withContext(Dispatchers.IO) {
        val s = settings.current()
        check(s.serverConfigured) { "Server not configured" }
        block(Api.client(s.serverUrl, s.apiKey))
    }

    override suspend fun extractListing(text: String): HouseDraftDto = withApi { it.extractListing(text) }
    override suspend fun ask(question: String): AskResponseDto = withApi { it.ask(question) }
    override suspend fun planVisits(request: PlanRequest): PlanResponseDto = withApi { it.planVisits(request) }

    /**
     * Two-way sync: push local changes, then pull everything the server has changed since last time.
     * Conflicts are "last edit wins" (by updatedAt), on both sides. Houses and visits always sync; photo transfers
     * only when [photosAllowed] (the caller checks for an unmetered network when the user asked for Wi-Fi only).
     * A server found behind this phone (S4b-BL-20: its highest version below a stored cursor, or a push answered
     * with a version at or below one) gets everything again and is pulled from 0, and the outcome says so
     * ([SyncOutcome.serverReset]). Throws on failure; see [SyncOutcome.fromError].
     */
    override suspend fun sync(photosAllowed: Boolean): SyncOutcome = withContext(Dispatchers.IO) {
        val s = settings.current()
        if (!s.serverConfigured) return@withContext SyncOutcome(SyncOutcome.Kind.NOT_CONFIGURED)
        val api = apiFor(s.serverUrl, s.apiKey)

        // S4b-BL-20: a server whose database was replaced (a new, empty one; a restore from an older dump) is behind
        // the stored cursors. Its highest version (GET /api/stats, maxSyncVersion; null from an older server, which
        // is unknown) below a cursor means it lost what this phone sent and hides changes below the cursors, so
        // everything goes again and the pull starts from 0. A push answer can show the same ([pushAll]).
        val stored = settings.cursors()
        val storedCursors = listOf(stored.house, stored.visit, stored.photo)
        var serverReset = false
        if (storedCursors.any { it > 0 }) {
            val highest = try {
                api.stats().maxSyncVersion
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null // Unknown; a real failure shows in the push that follows.
            }
            if (SyncRules.serverBehind(highest, storedCursors)) {
                resetForServer()
                serverReset = true
            }
        }
        var pushed: Int
        var photosWaiting: Int
        try {
            val first = pushAll(api, photosAllowed, if (serverReset) 0L else storedCursors.max())
            pushed = first.first
            photosWaiting = first.second
        } catch (e: ServerWasReset) {
            resetForServer()
            serverReset = true
            val again = pushAll(api, photosAllowed, highestCursor = 0L)
            pushed = e.pushed + again.first
            photosWaiting = again.second
        }

        val cursors = settings.cursors()
        var houseCursor = cursors.house
        var visitCursor = cursors.visit
        var pulled = 0
        for (dto in api.housesSince(houseCursor)) {
            houseCursor = maxOf(houseCursor, dto.syncVersion)
            val local = db.houses().get(dto.id)
            val incoming = dto.toEntity()
            if (SyncRules.keepLocal(local, incoming)) continue
            db.houses().upsert(incoming); pulled++
        }
        for (dto in api.visitsSince(visitCursor)) {
            visitCursor = maxOf(visitCursor, dto.syncVersion)
            val local = db.visits().get(dto.id)
            val incoming = dto.toEntity()
            if (SyncRules.keepLocal(local, incoming)) continue
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

        SyncOutcome(
            SyncOutcome.Kind.OK, pushed = pushed, pulled = pulled, photosWaiting = photosWaiting,
            serverReset = serverReset,
        )
    }

    /** Thrown by [pushAll] when a push answer shows the server behind this phone; [pushed] rows went before it. */
    private class ServerWasReset(val pushed: Int) : Exception()

    /**
     * Marks every house and visit for upload and every live photo for upload again, then resets the pull cursors
     * (S4b-BL-20). Rows first: a sync cut off in between finds the server behind again on its next run, instead of
     * leaving rows marked clean that the server does not have.
     */
    private suspend fun resetForServer() {
        db.houses().markAllDirty()
        db.visits().markAllDirty()
        db.photos().markAllForUpload()
        settings.resetCursors()
    }

    /**
     * Pushes local changes: visit tombstones without a house, houses, the other visits, photo deletes, photo uploads.
     * Returns the rows pushed and the photos left waiting for Wi-Fi. With a [highestCursor] above 0, an accepted
     * house or visit write answered with a version at or below it ([SyncRules.pushShowsReset]) stops the push with
     * [ServerWasReset].
     */
    private suspend fun pushAll(api: ApiClient, photosAllowed: Boolean, highestCursor: Long): Pair<Int, Int> {
        var pushed = 0
        fun check(sentUpdatedAt: Long, answerUpdatedAt: String?, answerVersion: Long) {
            val at = answerUpdatedAt?.let { runCatching { IsoTime.parseMillis(it) }.getOrNull() }
            if (SyncRules.pushShowsReset(sentUpdatedAt, at, answerVersion, highestCursor)) throw ServerWasReset(pushed)
        }
        // Visit tombstones without a house go first, before any house tombstone can make the server unlink (and
        // re-stamp) them; see SyncRules.pushesBeforeHouses (Android review, round 17). The houses are read BEFORE the
        // visits: an undo writes its house and visit tombstones in one transaction, so every house tombstone this
        // sync pushes has its visit tombstones in the list read after it, even when the undo lands mid-sync.
        val dirtyHouses = db.houses().dirty()
        val (visitsFirst, visitsAfter) =
            SyncRules.visitsByPushOrder(db.visits().dirty(), { it.deleted }, { it.houseId })
        for (v in visitsFirst) {
            val answer = api.putVisit(v.toDto())
            check(v.updatedAt, answer.updatedAt, answer.syncVersion)
            db.visits().markClean(v.id, v.updatedAt); pushed++
        }
        for (h in dirtyHouses) {
            val answer = api.putHouse(h.toDto())
            check(h.updatedAt, answer.updatedAt, answer.syncVersion)
            db.houses().markClean(h.id, h.updatedAt); pushed++
        }
        for (v in visitsAfter) {
            val answer = api.putVisit(v.toDto())
            check(v.updatedAt, answer.updatedAt, answer.syncVersion)
            db.visits().markClean(v.id, v.updatedAt); pushed++
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
                // Streamed from the file, not read into memory; each (re)try opens the file again.
                api.uploadPhoto(p.houseId, p.id, file.name, file.length()) { file.inputStream().asSource().buffered() }
            } catch (e: ApiException) {
                // Permanent rejections (not an image, too big, over the per-house limit) would fail on every sync;
                // keep the photo on this phone only and move on. Anything else (5xx, auth) aborts the sync.
                if (e.kind != ApiException.Kind.CLIENT && e.kind != ApiException.Kind.CONFLICT &&
                    e.kind != ApiException.Kind.NOT_FOUND
                ) throw e
            }
            db.photos().upsert(p.copy(uploaded = true)); pushed++
        }
        return pushed to photosWaiting
    }

    // ---- Offline copy: export and import (Sprint 4a, S4-02/S4-04) ----

    override suspend fun localRows(): LocalRows = withContext(Dispatchers.IO) {
        LocalRows(db.houses().all(), db.visits().all(), db.photos().all())
    }

    /**
     * [localRows] now, and again after every change to the houses, visits or photos table, for the Export screen's
     * live count (docs/05 section 14.1: the honest answer to what the file will hold). Keyed on all three tables,
     * not on the house list: a visit Hunt mode records, or a photo added, while the screen is open changes the file
     * but not a single house row (Android review, 2026-09-22). Changes that arrive while a read is still going are
     * conflated into one more read.
     */
    override fun localRowsFlow(): Flow<LocalRows> =
        db.localTablesChanged().conflate().map { localRows() }

    override suspend fun localVersions(): LocalVersions = withContext(Dispatchers.IO) {
        LocalVersions(
            db.houses().versions().associate { it.id to it.updatedAt },
            db.visits().versions().associate { it.id to it.updatedAt },
            db.photos().allIds().toSet(),
            db.houses().deletedIds().toSet(),
            db.houses().all().filter { it.checklist.isNotEmpty() }.mapTo(HashSet()) { it.id },
            db.visits().unlinkedIds().toSet(),
            db.houses().syncedDeletedIds().toSet(),
        )
    }

    /** The file a photo row's bytes live in, for the exporter and the importer. */
    fun photoFile(id: String) = File(photoDir(), "$id.jpg")

    /**
     * The photo file for an id that came out of a backup, or null when it would not land in the photo
     * directory.
     *
     * `BackupValidation.checkData` already refuses a backup whose ids are not `[A-Za-z0-9_-]{1,64}`, so this
     * never fires on a file that got this far; it is here because [photoFile] interpolates its argument straight
     * into a path and a second, independent check costs one `canonicalPath` per photo. Defence in depth for the
     * same bug class as the zip-slip guard on entry names.
     */
    private fun importedPhotoFile(id: String): File? {
        if (!BackupValidation.isValidId(id)) return null
        val file = photoFile(id)
        val dir = photoDir()
        return if (file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) file else null
    }

    /**
     * Writes an import's rows (S4-04). Imported rows keep the timestamps the backup gave them, so the server's
     * own last-write-wins rule reaches the same answer, and they are marked `dirty` so they are pushed on the
     * next sync. [photoBytes] returns a photo's bytes from the ZIP, or null if it cannot be read or its hash
     * does not match the manifest; a photo whose bytes are missing is skipped rather than written as a row
     * pointing at nothing, and counted in [ImportResult.photosSkipped] so the screen can say so instead of
     * reporting a clean import of a damaged file.
     *
     * **Merge** writes rows one by one rather than in a transaction on purpose: a 1 GB import that is interrupted
     * should leave the houses it already wrote, not roll everything back, and every row is idempotent (upsert by
     * id), so re-running the same import finishes the job.
     *
     * **Restored houses** (`ImportActions.restoredHouseIds`, a merge with "Also bring back houses deleted on this
     * phone") keep the backup's row but not its timestamp: the tombstone is cleared and `updatedAt` is stamped *now*
     * (and past the tombstone's own, whatever the clock says). Otherwise the next sync would meet the server's
     * tombstone, which is newer than the backup's row, and delete the house again; stamped now, the undelete is the
     * newest edit and wins on the server too (UX review, round 11). Re-running the same import then sees the house
     * as newer here and leaves it alone.
     *
     * **Relinked visits** (`ImportActions.relinkedVisitIds`, Android review round 12) are visits the server unlinked
     * when it purged a house this import writes over a tombstone. The phone's own copy is kept (it is at least as new
     * as the backup's and may have been edited since), its `houseId` is set back from the backup and it is stamped
     * like a restored house, past its own `updatedAt`, so the relink beats the server's unlink on the next sync. A
     * visit that was deleted or put in another house since the preview is left alone and not counted. The photos of
     * such a house already arrive under fresh ids (`ImportPlan.plan`) once the delete has reached the server
     * (`LocalVersions.syncedDeletedHouseIds`), because the server never takes a tombstoned photo id back.
     *
     * **Copy** is all or nothing ([applyCopy]). Its rows get fresh ids on every run, so re-running a half-finished
     * copy would not finish it but add everything it had already written a second time — and a stopped or failed
     * copy left the user no safe way forward (UX review, 2026-09-22). Nothing a copy writes is visible until the
     * very end, and a stop or a failure leaves the phone exactly as it was.
     */
    override suspend fun applyImport(
        actions: ImportActions,
        onProgress: (done: Int, total: Int) -> Unit,
        photoBytes: suspend (entry: String) -> ByteArray?,
    ): ImportResult = withContext(Dispatchers.IO) {
        if (actions.mode == ImportMode.COPY) return@withContext applyCopy(actions, onProgress, photoBytes)
        val total = actions.houses.size + actions.visits.size + actions.photos.size
        var done = 0
        var houses = 0
        var visits = 0
        var photos = 0
        var skipped = 0
        var updatedHouses = 0
        var updatedVisits = 0
        var restoredHouses = 0
        for (house in actions.houses) {
            if (house.id in actions.restoredHouseIds) {
                val tombstone = db.houses().get(house.id)
                val stamp = maxOf(System.currentTimeMillis(), (tombstone?.updatedAt ?: 0L) + 1)
                // toEntity already writes deleted = false; the stamp is what makes the undelete stick.
                db.houses().upsert(house.toEntity(dirty = true).copy(updatedAt = stamp))
                restoredHouses++
            } else {
                db.houses().upsert(house.toEntity(dirty = true))
                if (house.id in actions.updatedHouseIds) updatedHouses++
            }
            houses++
            onProgress(++done, total)
        }
        for (visit in actions.visits) {
            if (visit.id in actions.relinkedVisitIds) {
                val local = db.visits().get(visit.id)
                val row = when {
                    // Gone since the preview (visits are not removed, but be safe): the backup's row is all there is.
                    local == null -> visit.toEntity(dirty = true)
                    // Deleted, or put in a house, since the preview: that is a newer decision of the user's.
                    local.deleted || local.houseId != null -> null
                    else -> local.copy(houseId = visit.houseId)
                }
                if (row != null) {
                    val stamp = maxOf(System.currentTimeMillis(), (local?.updatedAt ?: 0L) + 1)
                    db.visits().upsert(row.copy(updatedAt = stamp, dirty = true))
                    visits++
                }
                onProgress(++done, total)
                continue
            }
            db.visits().upsert(visit.toEntity(dirty = true))
            visits++
            if (visit.id in actions.updatedVisitIds) updatedVisits++
            onProgress(++done, total)
        }
        for (photo in actions.photos) {
            val entry = actions.photoSources[photo.id]
            val bytes = entry?.let { photoBytes(it) }
            val out = importedPhotoFile(photo.id)
            if (bytes != null && out != null && db.houses().get(photo.houseId)?.deleted == false) {
                out.writeBytes(bytes)
                db.photos().upsert(
                    PhotoEntity(photo.id, photo.houseId, out.absolutePath, uploaded = false, createdAt = photo.createdAt)
                )
                photos++
            } else {
                // Not written, so counted: bytes we could not read or verify, or — the narrow race — a house
                // deleted on this phone after the preview was made. The preview counted that photo under "new
                // photos" (`ImportPlan` only excludes houses that were already tombstoned when it ran, via
                // `LocalVersions.deletedHouseIds`), so leaving it out here would quietly deliver one fewer photo
                // than the user was promised. Either way the user hears about it.
                skipped++
            }
            onProgress(++done, total)
        }
        val result = ImportResult(houses, visits, photos, skipped, updatedHouses, updatedVisits, restoredHouses)
        if (result.rows > 0) SyncWorker.syncSoon(context)
        result
    }

    /**
     * [applyImport] for [ImportMode.COPY]: all or nothing.
     *
     * The slow part — reading, verifying and writing the photo files — comes first, into files that no row points
     * at yet. Then every house, visit and photo row is written in **one transaction**, which is quick (rows only)
     * and either commits all of them or none. A Stop ([onProgress] throws `CancellationException`), a system stop,
     * a full disk or any other failure rolls the transaction back and deletes the photo files already written (each
     * one whose row is not in the database, [discardUncommittedPhotoFiles]: a cancellation that lands just after the
     * commit keeps them), so the phone is left exactly as it was and importing the file again is safe. (If the process is killed outright
     * between the two steps, photo files without a row can be left in the app's private photo folder; no row, and
     * so nothing the user sees, refers to them.)
     *
     * Progress counts the photos as their files are written and then each house and visit row, so the bar moves
     * during the long part; its total is the same as in merge mode.
     *
     * A copy keeps the backup's `updatedAt` unless it lies in the future, when it is stamped now instead
     * ([CopyUndo.copyStamp], Android review round 17): the result's [ImportResult.copiedHouses] / `copiedVisits`
     * carry the stamp each row was really written with, which is what the undo compares against.
     */
    private suspend fun applyCopy(
        actions: ImportActions,
        onProgress: (done: Int, total: Int) -> Unit,
        photoBytes: suspend (entry: String) -> ByteArray?,
    ): ImportResult {
        val total = actions.houses.size + actions.visits.size + actions.photos.size
        var done = 0
        var skipped = 0
        // In copy mode every photo belongs to a house of this import (ImportPlan maps it to the house's new id);
        // anything else is not written, and counted, exactly as merge mode counts a photo whose house is gone.
        val newHouseIds = actions.houses.mapTo(HashSet()) { it.id }
        // Each photo file written, with the id of the row that will point at it.
        val written = LinkedHashMap<File, String>()
        val photoRows = ArrayList<PhotoEntity>()
        // The id and the updatedAt each row was written with, for the undo record (ImportUndo).
        val copiedHouses = LinkedHashMap<String, Long>()
        val copiedVisits = LinkedHashMap<String, Long>()
        try {
            for (photo in actions.photos) {
                val entry = actions.photoSources[photo.id]
                val bytes = entry?.let { photoBytes(it) }
                val out = importedPhotoFile(photo.id)
                if (bytes != null && out != null && photo.houseId in newHouseIds) {
                    written[out] = photo.id
                    out.writeBytes(bytes)
                    photoRows += PhotoEntity(
                        photo.id, photo.houseId, out.absolutePath, uploaded = false, createdAt = photo.createdAt,
                    )
                } else {
                    skipped++
                }
                onProgress(++done, total)
            }
            // Never stamped in the future (CopyUndo.copyStamp): the server would clamp it, the pull would write the
            // clamped value back, and the undo would take every such copy for one the user edited since.
            val now = System.currentTimeMillis()
            db.withImmediateTransaction {
                for (house in actions.houses) {
                    val row = house.toEntity(dirty = true).copy(updatedAt = CopyUndo.copyStamp(house.updatedAt, now))
                    db.houses().upsert(row)
                    copiedHouses[row.id] = row.updatedAt
                    onProgress(++done, total)
                }
                for (visit in actions.visits) {
                    val row = visit.toEntity(dirty = true).copy(updatedAt = CopyUndo.copyStamp(visit.updatedAt, now))
                    db.visits().upsert(row)
                    copiedVisits[row.id] = row.updatedAt
                    onProgress(++done, total)
                }
                for (row in photoRows) db.photos().upsert(row)
            }
        } catch (e: Throwable) {
            // Rolled back (or never started): the files are the only thing left to undo. Not always, though: a
            // cancellation can also land after the commit, as the transaction hands back to this coroutine, and then
            // the rows are there and their files must stay (see discardUncommittedPhotoFiles).
            discardUncommittedPhotoFiles(written)
            throw e
        }
        val result = ImportResult(
            actions.houses.size, actions.visits.size, photoRows.size, skipped,
            copiedHouses = copiedHouses,
            copiedVisits = copiedVisits,
            copiedPhotos = photoRows.map { it.id },
        )
        if (result.rows > 0) SyncWorker.syncSoon(context)
        return result
    }

    /**
     * After a copy import that did not finish ([applyCopy]'s catch): deletes each photo file it wrote ([written], file
     * to photo id) whose row is not in the database, and keeps the others. The transaction is all or nothing, so
     * after a rollback no row is there and every file goes, as before; but a cancellation of the caller can also land
     * after the commit (the rows are written, then the resumption throws), and deleting then would leave committed
     * rows pointing at missing files (code review of PR #23). A row that cannot be read counts as missing, the old
     * rule. `NonCancellable`, because the caller is usually being cancelled right now (Room 2.8's reads do not check
     * that today, but nothing promises it); only these reads and deletes are, never the transaction itself.
     */
    internal suspend fun discardUncommittedPhotoFiles(written: Map<File, String>) = withContext(NonCancellable) {
        for ((file, id) in written) {
            val committed = runCatching { db.photos().get(id) != null }.getOrDefault(false)
            if (!committed) file.delete()
        }
    }

    /**
     * Undoes a copy import (UX review, round 16): removes exactly the rows it added, given as the ids of its
     * `ImportUndo` record ([houses] and [visits] with the `updatedAt` each was written with, and [photos]).
     *
     * Every keep-or-remove decision is [CopyUndo]'s (pure, and pinned by `CopyUndoTest`, Android review round 17);
     * this function only reads the rows, asks it and writes the answer. All in **one transaction**, as the copy itself
     * was: either every copy goes or none does. A removed house becomes a tombstone that syncs
     * ([CopyUndo.houseTombstone], as [deleteHouse] writes it), so the next sync removes it from the server and every
     * other device too; a house the user has touched since is kept, with everything in it, and counted in
     * [UndoResult.kept]; one already deleted is skipped. The import's unchanged visits go with their removed house,
     * and so do its unchanged loose street visits, as tombstones **without a house** ([CopyUndo.visitTombstone]) so
     * that [sync] pushes them before the house tombstones and the server's purge cannot unlink and revive them. The
     * photos of a removed house lose their row and, after the commit, their file (the server purges its copies with
     * the house).
     */
    override suspend fun undoCopyImport(
        houses: Map<String, Long>,
        visits: Map<String, Long>,
        photos: Collection<String>,
    ): UndoResult = withContext(Dispatchers.IO) {
        val photoIds = photos.toHashSet()
        val files = ArrayList<File>()
        var removed = 0
        val keptHouses = HashSet<String>()
        db.withImmediateTransaction {
            val now = System.currentTimeMillis()
            val removedHouses = HashSet<String>()
            for ((id, writtenAt) in houses) {
                val house = db.houses().get(id)
                val state = house?.let { h ->
                    CopyUndo.HouseNow(
                        updatedAt = h.updatedAt,
                        deleted = h.deleted,
                        liveVisits = db.visits().liveForHouse(id).associate { it.id to it.updatedAt },
                        livePhotoIds = db.photos().liveForHouse(id).map { it.id },
                    )
                }
                when (CopyUndo.decideHouse(state, writtenAt, visits, photoIds)) {
                    CopyUndo.Decision.REMOVE -> {
                        db.houses().upsert(CopyUndo.houseTombstone(checkNotNull(house), now))
                        removedHouses += id
                        removed++
                    }
                    CopyUndo.Decision.KEEP -> keptHouses += id
                    CopyUndo.Decision.SKIP -> Unit
                }
            }
            for ((id, writtenAt) in visits) {
                val visit = db.visits().get(id)
                val state = visit?.let { CopyUndo.VisitNow(it.updatedAt, it.deleted, it.houseId) }
                if (CopyUndo.decideVisit(state, writtenAt, removedHouses) == CopyUndo.Decision.REMOVE) {
                    db.visits().upsert(CopyUndo.visitTombstone(checkNotNull(visit), now))
                }
            }
            for (id in photoIds) {
                val photo = db.photos().get(id) ?: continue
                if (!CopyUndo.removesPhoto(photo.houseId, removedHouses)) continue
                db.photos().delete(id)
                files += File(photo.path)
            }
        }
        // After the commit: a rolled-back undo must not have deleted a single photo file.
        files.forEach { it.delete() }
        if (removed > 0) SyncWorker.syncSoon(context)
        UndoResult(removed, keptHouses.size, keptHouses)
    }
}
