package app.doorprints.data

import app.doorprints.shared.api.AskResponseDto
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.api.PlanRequest
import app.doorprints.shared.api.PlanResponseDto
import app.doorprints.shared.api.StatsDto
import app.doorprints.shared.export.ImportActions
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.sync.SyncOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's data: the houses, visits and photos in the Room database, the settings, the sync with the optional
 * server and the offline copy's reads and writes (common since CMP-4 P4c, ADR-23).
 *
 * The members here are the ones whose types are platform-neutral, so common UI code (`:ui`) can take a [Repository].
 * Android's implementation is `:app`'s `AndroidRepository`, which also has the members that need the platform: adding
 * a photo from a `Uri` and the photo files (`photoDir`, `photoFile`). The behaviour of each member is documented on
 * the implementation.
 */
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

    /** Deletes the photo's local file now; a photo the server has is queued for deletion on the next sync. */
    suspend fun deletePhoto(photo: PhotoEntity)

    suspend fun testConnection(): Result<StatsDto>

    /** Whether the server has AI features on; see [refreshAiStatus]. */
    val aiEnabled: StateFlow<Boolean>
    suspend fun refreshAiStatus(): Boolean
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

    enum class AddPhotoResult { ADDED, LIMIT_REACHED, UNREADABLE }

    // ---- Offline copy: export and import (Sprint 4a, S4-02/S4-04) ----

    /** Everything a copy is built from, read in one pass. Tombstones are left out; the export never carries them. */
    data class LocalRows(
        val houses: List<HouseEntity>,
        val visits: List<VisitEntity>,
        val photos: List<PhotoEntity>,
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
    ) {
        /** Everything written, of every type. */
        val rows: Int get() = houses + visits + photos
    }

    /**
     * What [undoCopyImport] did: houses removed, and houses kept because the user had changed them since, with the ids
     * of those kept houses ([keptHouses]), so they can still be found behind the "Just imported" chip (UX review,
     * round 18).
     */
    data class UndoResult(val removed: Int, val kept: Int, val keptHouses: Set<String> = emptySet())
}
