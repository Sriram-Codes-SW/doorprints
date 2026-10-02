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
 * - Photos are the next ticket (S4b-BL-128): metadata and tombstones flow, [uploadPhoto] and [downloadPhoto] throw
 *   [DriveSyncNotYet], so the caller passes `photosAllowed = false` until then.
 *
 * The shrink guard's question is not an error: the outcome of the last pass is in [lastResult] (a
 * [SyncPassResult.NeedsConfirmation] after which [confirmShrink] and the next sync apply the held deletions).
 */
class DriveSyncBackend(
    private val engine: DriveSyncEngine,
    private val local: LocalRows,
    private val clock: () -> Long,
    private val deviceId: String,
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
        engine.stage(SyncRows.photo(photo.copy(deleted = true, updatedAt = IsoTime.format(at)), deviceId))
    }

    override suspend fun uploadPhoto(houseId: String, photoId: String, fileName: String, size: Long, open: () -> Source) {
        throw DriveSyncNotYet("photo upload", "S4b-BL-128")
    }

    override suspend fun pushPhotoMeta(photoId: String, meta: PhotoMetaDto): PhotoChangeDto? {
        val photo = local.photo(photoId) ?: return null
        return photo.copy(roomId = meta.roomId, tags = meta.tags, caption = meta.caption, metaUpdatedAt = meta.metaUpdatedAt, syncVersion = 0)
    }

    override suspend fun housesSince(cursor: Long): List<HouseDto> = decode(SyncKind.HOUSES, cursor, HouseDto.serializer()) { it.copy(syncVersion = generation) }

    override suspend fun visitsSince(cursor: Long): List<VisitDto> = decode(SyncKind.VISITS, cursor, VisitDto.serializer()) { it.copy(syncVersion = generation) }

    override suspend fun recordsSince(cursor: Long): List<RecordDto> = decode(SyncKind.RECORDS, cursor, RecordDto.serializer()) { it.copy(syncVersion = generation) }

    override suspend fun photoChangesSince(cursor: Long): List<PhotoChangeDto> =
        decode(SyncKind.PHOTOS, cursor, PhotoChangeDto.serializer()) { it.copy(syncVersion = generation) }

    override suspend fun downloadPhoto(photoId: String): ByteArray = throw DriveSyncNotYet("photo download", "S4b-BL-128")

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

    /** [by]: the device that made this version of the row (the local device for a row whose writer is not kept). */
    fun house(dto: HouseDto, by: String): SyncRow = row(SyncKind.HOUSES, HouseDto.serializer(), dto, by)

    fun visit(dto: VisitDto, by: String): SyncRow = row(SyncKind.VISITS, VisitDto.serializer(), dto, by)

    fun record(dto: RecordDto, by: String): SyncRow = row(SyncKind.RECORDS, RecordDto.serializer(), dto, by)

    fun photo(dto: PhotoChangeDto, by: String): SyncRow = row(SyncKind.PHOTOS, PhotoChangeDto.serializer(), dto, by)
}
