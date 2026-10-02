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

import app.doorprints.shared.api.ApiClient
import app.doorprints.shared.api.ApiException
import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.api.PhotoChangeDto
import app.doorprints.shared.api.PhotoMetaDto
import app.doorprints.shared.api.RecordDto
import app.doorprints.shared.api.VisitDto
import app.doorprints.shared.sync.MergeRule
import app.doorprints.shared.sync.SyncRules
import kotlinx.io.Source

/**
 * The self-hosted server as a [SyncBackend] (S4b-BL-70): the calls [CommonRepository.sync] made on [ApiClient] before
 * the seam, moved here unchanged. Positions are the server's change-log counter (`sync_seq`), the merge rule is
 * [SyncRules.serverMerge], and failures are the client's [ApiException] and IO errors.
 */
class ServerSyncBackend(private val api: ApiClient) : SyncBackend {
    override val mergeRule: MergeRule = SyncRules.serverMerge

    /**
     * S4b-BL-20: a server whose database was replaced (a new, empty one; a restore from an older dump) is behind the
     * stored cursors. Its highest version (GET /api/stats, maxSyncVersion; null from an older server, which is
     * unknown) below a cursor means it lost what this phone sent and hides changes below the cursors.
     */
    override suspend fun isBehind(cursors: List<Long>): Boolean {
        val highest = try {
            api.stats().maxSyncVersion
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null // Unknown; a real failure shows in the push that follows.
        }
        return SyncRules.serverBehind(highest, cursors)
    }

    override suspend fun pushHouse(house: HouseDto): HouseDto = api.putHouse(house)

    override suspend fun pushVisit(visit: VisitDto): VisitDto = api.putVisit(visit)

    override suspend fun pushRecord(record: RecordDto): RecordDto = api.putRecord(record)

    override suspend fun deletePhoto(photoId: String) = api.deletePhoto(photoId)

    override suspend fun uploadPhoto(houseId: String, photoId: String, fileName: String, size: Long, open: () -> Source) {
        try {
            api.uploadPhoto(houseId, photoId, fileName, size, open)
        } catch (e: ApiException) {
            // Permanent rejections (not an image, too big, over the per-house limit) would fail on every sync;
            // keep the photo on this phone only and move on. Anything else (5xx, auth) aborts the sync.
            if (e.kind != ApiException.Kind.CLIENT && e.kind != ApiException.Kind.CONFLICT &&
                e.kind != ApiException.Kind.NOT_FOUND
            ) throw e
        }
    }

    // A photo the server does not have (404) or refuses (400) would fail on every sync: null, and the meta stays here.
    override suspend fun pushPhotoMeta(photoId: String, meta: PhotoMetaDto): PhotoChangeDto? = try {
        api.putPhotoMeta(photoId, meta)
    } catch (e: ApiException) {
        if (e.kind != ApiException.Kind.CLIENT && e.kind != ApiException.Kind.NOT_FOUND) throw e
        null
    }

    override suspend fun housesSince(cursor: Long): List<HouseDto> = api.housesSince(cursor)

    override suspend fun visitsSince(cursor: Long): List<VisitDto> = api.visitsSince(cursor)

    override suspend fun recordsSince(cursor: Long): List<RecordDto> = api.recordsSince(cursor)

    override suspend fun photoChangesSince(cursor: Long): List<PhotoChangeDto> = api.photoChangesSince(cursor)

    override suspend fun downloadPhoto(photoId: String): ByteArray = api.downloadPhoto(photoId)
}
