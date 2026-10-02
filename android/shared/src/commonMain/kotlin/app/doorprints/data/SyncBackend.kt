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

import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.api.PhotoChangeDto
import app.doorprints.shared.api.PhotoMetaDto
import app.doorprints.shared.api.RecordDto
import app.doorprints.shared.api.VisitDto
import app.doorprints.shared.sync.MergeRule
import kotlinx.io.Source

/**
 * Where [CommonRepository.sync] sends this device's changes and gets the others' (S4b-BL-70, docs/15 §7 phase 1): the
 * seam between the sync loop and one remote. Today the only one is the self-hosted server ([ServerSyncBackend]); the
 * Google Drive backend (S4b-BL-118) comes later behind the same interface.
 *
 * The loop stays in [CommonRepository.sync] and does not change with the backend: it reads the dirty rows, decides the
 * push order ([app.doorprints.shared.sync.SyncRules.visitsByPushOrder]), marks rows clean, validates and stores every
 * pulled row as untrusted input, keeps the cursors and holds photo transfers back unless the caller allows them. A
 * backend only moves rows and photo bytes, and says how a pulled row meets the local one ([mergeRule]).
 *
 * The contract, written for what Drive needs as well (docs/15 §5):
 * - **Rows travel as the sync DTOs** ([HouseDto], [VisitDto], [RecordDto], [PhotoChangeDto]), tombstones included
 *   (`deleted`), so the loop's validation is the same for every backend. A deleted row is never dropped: a backend
 *   that keeps whole snapshots (Drive's one sync file per device) keeps tombstones for ever (docs/15 §5.3).
 * - **The cursor is a position the loop only compares and stores** (one per kind, kept in [SettingsStore.cursors]):
 *   each pulled row's `syncVersion` is its position, the loop moves a cursor to the highest one it has handled and
 *   asks for rows after it. The server's is its change-log counter; a backend without one gives a number that only
 *   grows within its own log (a time or a revision) and keeps whatever else it needs (Drive: the map of device id to
 *   file checksum) itself. A pushed row answered with `syncVersion` 0 says nothing about the remote being behind.
 * - **"Complete" means handled**: the loop moves the photo cursor only past changes it has fully handled, so photos
 *   held back (no Wi-Fi) come again; a backend never reports a row it has only half written (Drive's `partial-`
 *   files are not listed).
 * - **Photos are gated by the caller's network policy** (`photosAllowed`, later docs/15 §11's policy): the loop calls
 *   [uploadPhoto] and [downloadPhoto] only when allowed; deletes and metadata go on any network.
 * - **Failures are thrown as [app.doorprints.shared.api.ApiException] or [kotlinx.io.IOException]**, so
 *   [app.doorprints.shared.sync.SyncOutcome.fromError] classifies them for every backend (a Drive backend maps its
 *   answers to the same kinds, S4b-BL-115). A sync that throws has stored nothing beyond the rows already handled.
 */
interface SyncBackend {
    /** How a pulled row meets the local one: [app.doorprints.shared.sync.SyncRules.serverMerge] for the server. */
    val mergeRule: MergeRule

    /**
     * "Is it behind": true when the remote has lost what this device sent (S4b-BL-20: the server's highest position
     * below one of the stored [cursors]), so the loop sends everything again and pulls from the start. Asked only when
     * a cursor is above 0. False when unknown; a real failure shows in the push that follows.
     */
    suspend fun isBehind(cursors: List<Long>): Boolean

    /** Sends one changed house and returns the row the remote keeps (last write wins there, or the one sent). */
    suspend fun pushHouse(house: HouseDto): HouseDto

    /** Sends one changed visit; see [pushHouse]. */
    suspend fun pushVisit(visit: VisitDto): VisitDto

    /** Sends one changed record; see [pushHouse]. */
    suspend fun pushRecord(record: RecordDto): RecordDto

    /** Removes a photo; one the remote never had, or already removed, is fine. */
    suspend fun deletePhoto(photoId: String)

    /**
     * Uploads a photo's bytes, streamed from [open] (called again on each try). A refusal that would come back on every
     * sync (not an image, too big, over the per-house limit) is swallowed: the photo stays on this device only and
     * counts as sent. Anything else is thrown and ends the sync.
     */
    suspend fun uploadPhoto(houseId: String, photoId: String, fileName: String, size: Long, open: () -> Source)

    /**
     * Sends a photo's metadata and returns the remote's current one (last write wins on `metaUpdatedAt`), or null when
     * the remote refuses it for good (no such photo, a value it does not take): the edit then stays on this device.
     */
    suspend fun pushPhotoMeta(photoId: String, meta: PhotoMetaDto): PhotoChangeDto?

    /** The houses changed after [cursor] (positions in `syncVersion`), tombstones included. */
    suspend fun housesSince(cursor: Long): List<HouseDto>

    /** The visits changed after [cursor]; see [housesSince]. */
    suspend fun visitsSince(cursor: Long): List<VisitDto>

    /** The records changed after [cursor]; see [housesSince]. */
    suspend fun recordsSince(cursor: Long): List<RecordDto>

    /** The photos added, deleted or with new metadata after [cursor]; the bytes come from [downloadPhoto]. */
    suspend fun photoChangesSince(cursor: Long): List<PhotoChangeDto>

    /** One photo's bytes. */
    suspend fun downloadPhoto(photoId: String): ByteArray
}
