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

import app.doorprints.shared.api.AskResponseDto
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.api.PairPolledDto
import app.doorprints.shared.api.PairStartedDto
import app.doorprints.shared.api.PlanRequest
import app.doorprints.shared.api.PlanResponseDto
import app.doorprints.shared.api.StatsDto
import app.doorprints.shared.export.ImportActions
import app.doorprints.shared.export.ExportBroker
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.model.Broker
import app.doorprints.shared.records.RecordType
import app.doorprints.shared.sync.SyncOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's data: the houses, visits and photos in the Room database, the settings, the sync with the optional
 * server and the offline copy's reads and writes (common since CMP-4 P4c, ADR-23).
 *
 * The members here are the ones whose types are platform-neutral, so common UI code (`:ui`) can take a [Repository].
 * The implementation is common too, [CommonRepository] (S4b-BL-32), where the behaviour of each member is documented.
 * Android's `AndroidRepository` (`:app`) extends it with what needs the platform: adding a photo from a `Uri` and the
 * photo files as `java.io.File` (`photoDir`, `photoFile`).
 */
/**
 * Why AI is not offered (the web's `AiOffReason`): no server; off on the server (or paused, or no Gemini key); off
 * for this device on the owner page; or this phone's *AI features* switch is off.
 */
enum class AiOff { NO_SERVER, SERVER, DEVICE, OPT_IN }

interface Repository {
    val settings: SettingsStore

    /** Live houses, newest edit first. */
    val houses: Flow<List<HouseEntity>>

    /** Live visits per live house. */
    val visitCounts: Flow<List<HouseVisitCount>>

    fun house(id: String): Flow<HouseEntity?>
    fun visitsFor(houseId: String): Flow<List<VisitEntity>>
    fun photosFor(houseId: String): Flow<List<PhotoEntity>>

    suspend fun houseSnapshot(): List<HouseEntity>
    suspend fun getHouse(id: String): HouseEntity?

    /** Writes [house] as a local edit (`updatedAt` now, dirty) and asks for a sync soon. */
    suspend fun saveHouse(house: HouseEntity)

    /** Turns a house into a tombstone (see [saveHouse]); nothing when there is no such house. */
    suspend fun deleteHouse(id: String)

    suspend fun saveVisit(visit: VisitEntity)
    suspend fun getVisit(id: String): VisitEntity?
    suspend fun deleteVisit(id: String)

    /** Records a "been here" visit for a house, now. */
    suspend fun markVisitedNow(house: HouseEntity)

    suspend fun streetInfo(street: String): StreetInfo

    /** The path trace of the last [TRACK_KEPT_DAYS] days, oldest first (docs/11 5.27; this phone only). */
    val trackPoints: Flow<List<TrackPointEntity>>
    suspend fun saveTrackPoint(point: TrackPointEntity)
    /** Points older than [before] go (the retention limit; the engine calls it when Hunt mode starts). */
    suspend fun pruneTrack(before: Long)
    /** *Clear the path* in Settings. */
    suspend fun clearTrack()

    /** Deletes the photo's local file now; a photo the server has is queued for deletion on the next sync. */
    suspend fun deletePhoto(photo: PhotoEntity)

    // The record envelope (docs/11 5.30 item 2, ADR-28): every new kind of data of Sprint 4b, typed by a RecordType.

    /** The live records of [type] as id to value; a row whose payload does not decode is left out, never thrown. */
    fun <T> observeRecords(type: RecordType<T>): Flow<List<Pair<String, T>>>

    /**
     * Writes [value] under [type] and [id] as a local edit (`updatedAt` now, dirty) and asks for a sync soon. Throws
     * `IllegalArgumentException` for an id outside `RecordRules.isValidId` or a payload over
     * `RecordRules.MAX_PAYLOAD_BYTES`, and `RecordLimitException` when the type already has
     * `RecordRules.MAX_ROWS_PER_TYPE` live rows and [id] is not one of them.
     */
    suspend fun <T> saveRecord(type: RecordType<T>, id: String, value: T)

    /** Turns the record into a tombstone with `{}` as its payload; nothing when there is no such live record. */
    suspend fun deleteRecord(type: RecordType<*>, id: String)

    // Brokers (docs/11 5.25, slice 1b): records of type `broker`, and the copies of their name and phone that a linked
    // house keeps in `contactName` / `contactPhone` so old apps, the AI redaction, the exports and the search work on.

    /** The live brokers as id to value, by name (case ignored); a row that does not decode or is invalid is left out. */
    fun observeBrokers(): Flow<List<Pair<String, Broker>>>

    /**
     * Creates the broker (a new UUID when [id] is null) or updates it, and returns its id. Every live house of it gets
     * `contactName` / `contactPhone` rewritten from it when they differ (dirty, `updatedAt` now). Throws
     * `IllegalArgumentException` for a blank or oversized name.
     */
    suspend fun saveBroker(broker: Broker, id: String? = null): String

    /** Turns the broker into a tombstone; each of its houses gets `brokerId = null` (dirty) and keeps its contact copies. */
    suspend fun deleteBroker(id: String)

    /** The live houses that name the broker, newest edit first: the broker's page. */
    fun brokerHouses(id: String): Flow<List<HouseEntity>>

    suspend fun testConnection(): Result<StatsDto>

    /**
     * Whether AI is offered here: the server has it on for this device and this phone's *AI features* switch is on
     * ([AppSettings.aiFeatures]); see [refreshAiStatus].
     */
    val aiEnabled: StateFlow<Boolean>

    /** Why AI is not offered, or null when it is ([aiEnabled]). */
    val aiOff: StateFlow<AiOff?>
    suspend fun refreshAiStatus(): Boolean

    /** Turns this phone's *AI features* switch on or off; [aiEnabled] follows at once. */
    suspend fun setAiFeatures(on: Boolean)

    // On-device AI with the person's own Gemini key (docs/03 §13.1, ADR-26).

    /** Saves the person's own Gemini key and answers AI requests with it on this device. */
    suspend fun saveGeminiKey(key: String)

    /** Forgets the Gemini key; AI goes back to the server, if one is connected. */
    suspend fun removeGeminiKey()

    /** Chooses who answers AI requests; the Gemini key, if any, is kept. */
    suspend fun setAiProvider(choice: AiProviderChoice)

    /** Whether Google accepts [key]: one tiny request, nothing saved. */
    suspend fun testGeminiKey(key: String): Result<Unit>

    // Pairing (docs/03 §12.1, ADR-25): the app gets a device key of its own, with no key typed.

    /** Asks [serverUrl] (already checked with [ServerUrl.check]) for a code to type on its owner page. */
    suspend fun startPairing(serverUrl: String, deviceName: String): PairStartedDto

    /** One poll of a started pairing; `approved` carries the device key, once. */
    suspend fun pollPairing(serverUrl: String, pollToken: String): PairPolledDto

    /** Redeems a connect link's invite and returns the device key; an [ApiException] with code 410 when it was used. */
    suspend fun redeemInvite(link: ConnectLink, deviceName: String): String
    suspend fun extractListing(text: String): HouseDraftDto
    suspend fun ask(question: String): AskResponseDto
    suspend fun planVisits(request: PlanRequest): PlanResponseDto

    /** Two-way sync; photo transfers only when [photosAllowed]. Throws on failure; see [SyncOutcome.fromError]. */
    suspend fun sync(photosAllowed: Boolean = true): SyncOutcome

    /** Everything a copy is built from, read in one pass, without tombstones. */
    suspend fun localRows(): LocalRows

    /** [localRows] now, and again after every change to the houses, visits or photos table. */
    fun localRowsFlow(): Flow<LocalRows>

    /** What is already on this phone, for the import preview (tombstones included). */
    suspend fun localVersions(): LocalVersions

    /**
     * Writes an import's rows: a merge row by row, a copy ([ImportMode.COPY]) all or nothing. [photoBytes] returns a
     * photo's verified bytes from the backup, or null.
     */
    suspend fun applyImport(
        actions: ImportActions,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        photoBytes: suspend (entry: String) -> ByteArray?,
    ): ImportResult

    /** Undoes a copy import, in one transaction: removes exactly the rows it added that nobody changed since. */
    suspend fun undoCopyImport(
        houses: Map<String, Long>,
        visits: Map<String, Long>,
        photos: Collection<String>,
    ): UndoResult

    data class StreetInfo(val street: String, val houses: Int, val visits: Int, val firstVisit: Long?)

    companion object {
        /** How long the path trace is kept (docs/11 5.27). */
        const val TRACK_KEPT_DAYS = 30
        const val TRACK_KEPT_MS = TRACK_KEPT_DAYS * 24 * 60 * 60_000L
    }

    /** Used by `AndroidRepository.addPhoto` until the photo seam joins this interface (CMP-6 P6a). */
    enum class AddPhotoResult { ADDED, LIMIT_REACHED, UNREADABLE }

    // ---- Offline copy: export and import (Sprint 4a, S4-02/S4-04) ----

    /** Everything a copy is built from, read in one pass. Tombstones are left out; the export never carries them. */
    data class LocalRows(
        val houses: List<HouseEntity>,
        val visits: List<VisitEntity>,
        val photos: List<PhotoEntity>,
        /** The live brokers (slice 1b); an export writes them only for a copy with contact details. */
        val brokers: List<ExportBroker> = emptyList(),
    )

    /** What is already on this phone, for the import preview's last-write-wins comparison (tombstones included). */
    data class LocalVersions(
        val houses: Map<String, Long>,
        val visits: Map<String, Long>,
        val photoIds: Set<String>,
        /**
         * Which of [houses] are tombstones. They belong in [houses] — a deleted house keeps its `updatedAt` so an
         * older row in a backup cannot resurrect it — but `ImportPlan` also has to know that they are not a place
         * a photo or a visit can be attached, or the preview counts photos that [applyImport] will not write.
         */
        val deletedHouseIds: Set<String>,
        /**
         * Live houses with at least one checklist score, so the preview can warn when a newer row without a
         * checklist will clear them (docs/schemas/README.md section 4.4).
         */
        val scoredHouseIds: Set<String> = emptySet(),
        /**
         * Live visits with no house. After a synced house delete the server's purge sends the house's visits back
         * this way (`houseId = null`, newer `updatedAt`), so `ImportPlan` relinks the ones a restore brings their
         * house back for instead of leaving them as loose street visits (Android review, round 12).
         */
        val unlinkedVisitIds: Set<String> = emptySet(),
        /**
         * The tombstones of [deletedHouseIds] that have reached the server (`dirty = 0`). A restore gives fresh photo
         * ids only to these houses: a delete still waiting to be pushed has not been purged, so the server's photos
         * of that house are live under their old ids (Android review, round 13).
         */
        val syncedDeletedHouseIds: Set<String> = emptySet(),
        /** Every broker record here, deleted ones too, with its `updatedAt`: an import merges brokers by id (slice 1b). */
        val brokers: Map<String, Long> = emptyMap(),
    )

    /** What an import actually managed to write. */
    data class ImportResult(
        /** Rows written per type, new and updated together. */
        val houses: Int,
        val visits: Int,
        val photos: Int,
        /**
         * Photos the import did not write: bytes missing from the ZIP, unreadable, or failing their SHA-256 — or
         * whose house was deleted on this phone between the preview and the import.
         */
        val photosSkipped: Int,
        /**
         * Of [houses] and [visits], how many replaced a row already on the phone (MERGE only, from
         * `ImportActions.updatedHouseIds` / `updatedVisitIds`), so the result can say "Added 2 houses. Updated 3
         * houses." in the preview's own words (UX review, 2026-09-22). Always 0 for a copy.
         */
        val updatedHouses: Int = 0,
        val updatedVisits: Int = 0,
        /** Of [houses], how many were deleted on this phone and are back (`ImportActions.restoredHouseIds`). */
        val restoredHouses: Int = 0,
        /**
         * COPY only (UX review, round 16): the new house and visit ids with the `updatedAt` each was written with,
         * and the new photo ids, for the import's undo record (`ImportUndo`). Empty for a merge.
         */
        val copiedHouses: Map<String, Long> = emptyMap(),
        val copiedVisits: Map<String, Long> = emptyMap(),
        val copiedPhotos: List<String> = emptyList(),
        /** Brokers written (slice 1b), new and updated together. */
        val brokers: Int = 0,
    ) {
        /** Everything written, of every type. */
        val rows: Int get() = houses + visits + photos + brokers
    }

    /**
     * What [undoCopyImport] did: houses removed, and houses kept because the user had changed them since, with the ids
     * of those kept houses ([keptHouses]), so they can still be found behind the "Just imported" chip (UX review,
     * round 18).
     */
    data class UndoResult(val removed: Int, val kept: Int, val keptHouses: Set<String> = emptySet())
}
