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

package app.doorprints.drive.sync

import app.doorprints.data.SyncBackend
import app.doorprints.drive.DriveException
import app.doorprints.drive.photo.DrivePhotos
import app.doorprints.drive.photo.PhotoRef
import app.doorprints.drive.photo.SkippedPhoto
import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.api.PhotoChangeDto
import app.doorprints.shared.api.PhotoMetaDto
import app.doorprints.shared.api.RecordDto
import app.doorprints.shared.api.VisitDto
import app.doorprints.shared.sync.DriveMerge
import app.doorprints.shared.sync.MergeRule
import app.doorprints.shared.sync.SyncKind
import app.doorprints.shared.sync.SyncRow
import app.doorprints.shared.sync.SyncTime
import kotlinx.io.Source
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Google Drive as a [SyncBackend] (S4b-BL-118): the seam the loop in `CommonRepository.sync` already uses, over a
 * [DriveSyncEngine]. Web twin: `DriveSyncBackend` in `drive-sync-backend.ts`.
 *
 * Drive keeps whole snapshots, not rows, so the backend differs from the server's in three ways the loop was taught
 * ([SyncBackend.stagesPushes]):
 * - `push*` send nothing: the engine writes this device's whole state ([LocalRows]) once, in [commitPushes], and the
 *   loop marks rows clean only after that returned (the file complete and read back). The answer to a push is the row
 *   sent, with no position (`syncVersion` 0), so it never shows a "reset".
 * - What the other devices' files changed is found in [commitPushes] too; `*Since` hand it out. A row's position is the
 *   pass counter (`syncVersion`), only compared by the loop.
 * - Photos (S4b-BL-128): metadata and tombstones flow in the sync file; the bytes go as one `photo/1` file each through
 *   [DrivePhotos]. A photo's row carries the Drive file and the plaintext SHA-256 once uploaded; a photo another device
 *   has not uploaded yet, or one that cannot be read (tampered, planted, revoked writer), is **not** handed to the loop
 *   ([photoChangesSince] leaves it out until its bytes can be fetched; [downloadPhotoIfAvailable] answers null and the
 *   skip is in [photoSkips]). Without [photos] the photo calls throw [DriveSyncNotYet].
 *
 * The shrink guard's question is not an error: the outcome of the last pass is in [lastResult] (a
 * [SyncPassResult.NeedsConfirmation] after which [confirmShrink] and the next sync apply the held deletions).
 */
class DriveSyncBackend(
    private val engine: DriveSyncEngine,
    private val local: LocalRows,
    private val clock: () -> Long,
    private val deviceId: String,
    /** Photo bytes over Drive (S4b-BL-128); null: the photo calls throw [DriveSyncNotYet] and the caller keeps `photosAllowed` false. */
    private val photos: DrivePhotos? = null,
) : SyncBackend {
    override val mergeRule: MergeRule = DriveMerge.rule
    override val stagesPushes: Boolean = true

    /** The outcome of the last [commitPushes]. */
    var lastResult: SyncPassResult? = null
        private set

    private var confirmNext = false
    private var taken: List<SyncRow> = emptyList()
    private var generation = 0L

    /** The person said yes to the shrink guard: the next pass applies the house deletions it held. */
    fun confirmShrink() {
        confirmNext = true
    }

    override suspend fun isBehind(cursors: List<Long>): Boolean = engine.isBehind()

    override suspend fun commitPushes() {
        val confirm = confirmNext
        taken = emptyList()
        val result = engine.run(confirmShrink = confirm)
        lastResult = result
        when (result) {
            is SyncPassResult.Waiting ->
                throw DriveException(DriveException.Kind.RATE_LIMITED, retryAfterMs = (result.notBefore - clock()).coerceAtLeast(0))
            SyncPassResult.Paused -> throw DriveException(DriveException.Kind.CANCELLED, reason = "paused")
            else -> {
                if (confirm) confirmNext = false
                taken = result.report?.take.orEmpty()
                generation = result.report?.generation ?: 0
            }
        }
    }

    override suspend fun pushHouse(house: HouseDto): HouseDto = house.copy(syncVersion = 0)

    override suspend fun pushVisit(visit: VisitDto): VisitDto = visit.copy(syncVersion = 0)

    override suspend fun pushRecord(record: RecordDto): RecordDto = record.copy(syncVersion = 0)

    /** The tombstone of a photo deleted here (its local row goes right after): kept in the file for ever. */
    override suspend fun deletePhoto(photoId: String) {
        val photo = local.photo(photoId) ?: return
        val previous = photo.updatedAt?.let(SyncTime::parse)
        val at = DriveMerge.nextStamp(clock(), previous)
        // The tombstone keeps the Drive file of the bytes, so a later clean-up (docs/15 §5.1: 30 days, no kept backup) finds it.
        engine.stage(SyncRows.photo(photo.copy(deleted = true, updatedAt = IsoTime.format(at)), deviceId, photos?.refs()?.get(photoId)))
    }

    /** The photos skipped since the last call (reported, never thrown): too large, tampered, planted, revoked writer, not uploaded yet. */
    fun photoSkips(): List<SkippedPhoto> = photos?.drainSkipped().orEmpty()

    /** One `photo/1` file per photo; a photo that can never go (too large, empty) stays on this device and counts as sent. */
    override suspend fun uploadPhoto(houseId: String, photoId: String, fileName: String, size: Long, open: () -> Source) {
        val service = photos ?: throw DriveSyncNotYet("photo upload", "S4b-BL-128")
        service.upload(photoId, size, open)
    }

    override suspend fun pushPhotoMeta(photoId: String, meta: PhotoMetaDto): PhotoChangeDto? {
        val photo = local.photo(photoId) ?: return null
        return photo.copy(roomId = meta.roomId, tags = meta.tags, caption = meta.caption, metaUpdatedAt = meta.metaUpdatedAt, syncVersion = 0)
    }

    override suspend fun housesSince(cursor: Long): List<HouseDto> = decode(SyncKind.HOUSES, cursor, HouseDto.serializer()) { it.copy(syncVersion = generation) }

    override suspend fun visitsSince(cursor: Long): List<VisitDto> = decode(SyncKind.VISITS, cursor, VisitDto.serializer()) { it.copy(syncVersion = generation) }

    override suspend fun recordsSince(cursor: Long): List<RecordDto> = decode(SyncKind.RECORDS, cursor, RecordDto.serializer()) { it.copy(syncVersion = generation) }

    override suspend fun photoChangesSince(cursor: Long): List<PhotoChangeDto> {
        val all = decode(SyncKind.PHOTOS, cursor, PhotoChangeDto.serializer()) { it.copy(syncVersion = generation) }
        val service = photos ?: return all
        // A live photo this device does not have and whose bytes are not in Drive yet (the other device waits for Wi-Fi)
        // is left out; its row comes again in a later pass, once the other device's file names the Drive file.
        val refs = service.refs()
        return all.filter { it.deleted || it.id in refs || local.photo(it.id) != null }
    }

    override suspend fun downloadPhoto(photoId: String): ByteArray =
        downloadPhotoIfAvailable(photoId) ?: throw DriveException(DriveException.Kind.NOT_FOUND, reason = "photoUnavailable")

    override suspend fun downloadPhotoIfAvailable(photoId: String): ByteArray? {
        val service = photos ?: throw DriveSyncNotYet("photo download", "S4b-BL-128")
        return service.download(photoId)
    }

    private fun <T> decode(kind: SyncKind, cursor: Long, serializer: kotlinx.serialization.KSerializer<T>, withVersion: (T) -> T): List<T> {
        if (generation <= cursor) return emptyList()
        return taken.filter { it.kind == kind }.mapNotNull { row ->
            // A row the DTO cannot hold is skipped, like any pulled row the loop cannot use; the loop validates the rest.
            try {
                withVersion(JSON.decodeFromJsonElement(serializer, row.json))
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** Builds the [SyncRow]s the platform's [LocalRows] returns from the sync DTOs of its stores. */
object SyncRows {
    private val out = Json { encodeDefaults = true; explicitNulls = false }

    private fun <T> row(kind: SyncKind, serializer: kotlinx.serialization.KSerializer<T>, dto: T, by: String): SyncRow {
        val base = out.encodeToJsonElement(serializer, dto) as JsonObject
        val json = buildJsonObject {
            for ((k, v) in base) if (k != "syncVersion") put(k, v)
            put("by", JsonPrimitive(by))
        }
        return SyncRow.of(kind, json)
    }

    /** The Drive file of a photo's bytes that [row] names (S4b-BL-128), or null when it names none. */
    fun refOf(row: SyncRow): PhotoRef? {
        if (row.kind != SyncKind.PHOTOS) return null
        val file = (row.json["driveFileId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val sha = (row.json["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return PhotoRef(file, sha)
    }

    /** [row] with the Drive file of its bytes written in (the row's stamp does not change). */
    fun withRef(row: SyncRow, ref: PhotoRef): SyncRow = SyncRow.of(
        row.kind,
        buildJsonObject {
            for ((k, v) in row.json) put(k, v)
            put("driveFileId", JsonPrimitive(ref.driveFileId))
            put("sha256", JsonPrimitive(ref.sha256))
        },
    )

    /** [by]: the device that made this version of the row (the local device for a row whose writer is not kept). */
    fun house(dto: HouseDto, by: String): SyncRow = row(SyncKind.HOUSES, HouseDto.serializer(), dto, by)

    fun visit(dto: VisitDto, by: String): SyncRow = row(SyncKind.VISITS, VisitDto.serializer(), dto, by)

    fun record(dto: RecordDto, by: String): SyncRow = row(SyncKind.RECORDS, RecordDto.serializer(), dto, by)

    fun photo(dto: PhotoChangeDto, by: String, ref: PhotoRef? = null): SyncRow {
        val base = row(SyncKind.PHOTOS, PhotoChangeDto.serializer(), dto, by)
        return if (ref == null) base else withRef(base, ref)
    }
}
