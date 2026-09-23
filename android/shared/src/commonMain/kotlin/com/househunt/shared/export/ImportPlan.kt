package com.househunt.shared.export

/** How an import treats rows that already exist (docs/11 section 5.2, "Import (exact round trip)"). */
enum class ImportMode {
    /** Merge by id, last edit wins: a row that is newer in the file replaces the local one, otherwise nothing. */
    MERGE,

    /** Import everything as new rows with new ids. Nothing already on the phone is touched. */
    COPY,
}

/**
 * What an import would do, shown before anything is written ("*a* new, *b* newer in file, *c* newer here").
 */
data class ImportPreview(
    val mode: ImportMode,
    val newHouses: Int,
    val updatedHouses: Int,
    val newerHereHouses: Int,
    val unchangedHouses: Int,
    val newVisits: Int,
    val updatedVisits: Int,
    val newerHereVisits: Int,
    val unchangedVisits: Int,
    val newPhotos: Int,
    /** Photo rows the import will not write: already on the phone, or belonging to no house it will keep. */
    val skippedPhotos: Int,
    /** Photo rows in the file whose image is not in the ZIP (a backup made with "photos: none"). */
    val photosMissingFromFile: Int,
    /**
     * Houses on this phone that have checklist scores and that the import will overwrite with a newer row that has
     * none (an absent, `null` or empty `checklist` all read as `{}`), so their scores are cleared. MERGE only; the
     * device form of the server's "the file has no checklist … score(s) … are cleared" line (docs/schemas/README.md
     * section 4.4). It is a subset of [updatedHouses], shown as a warning because the user loses something.
     */
    val checklistsCleared: Int = 0,
    /**
     * Houses in the file that this phone holds a **tombstone** for, and that a MERGE therefore leaves deleted: the
     * tombstone is newer (deleting bumps `updatedAt`) or the same. Split out of [newerHereHouses] (UX review,
     * 2026-09-22): "kept, because this phone has a newer version" is untrue for a house that is not on the phone at
     * all, and restoring houses deleted by mistake is the most common reason to open a backup. The screen says
     * "Deleted on this phone; they stay deleted" and offers to bring them back: the opt-in `restoreDeleted` merge,
     * which moves them to [restoredHouses] (UX review, round 11). Always 0 in COPY mode, and 0 for every house a
     * `restoreDeleted` merge brings back.
     */
    val deletedHereHouses: Int = 0,
    /** Visits in the file that belong to one of [deletedHereHouses]; a MERGE does not write them. */
    val deletedHereVisits: Int = 0,
    /** Photos in the file (with their bytes) of one of [deletedHereHouses]; a subset of [skippedPhotos]. */
    val deletedHerePhotos: Int = 0,
    /**
     * MERGE with `restoreDeleted` only (UX review, round 11): houses this phone holds a tombstone for that the import
     * brings back with their **original id** — the opt-in undelete. Without the flag they are [deletedHereHouses];
     * with it they move here, and their visits and photos are counted as new or updated like any kept house's. The
     * way back for "I deleted three houses by mistake" that does not add the other 37 houses a second time.
     */
    val restoredHouses: Int = 0,
    /**
     * MERGE with `skipUpdates` only ("Keep mine, add only what's new", UX review round 11): rows the file has a newer
     * version of that the import leaves as they are on the phone. Without the flag they are [updatedHouses] /
     * [updatedVisits].
     */
    val keptMineHouses: Int = 0,
    val keptMineVisits: Int = 0,
    /**
     * Of [newVisits], the visits that go back into a house the import writes **over a tombstone** (restored, or
     * resurrected because the file's row is newer) after the server had unlinked them from it (Android review,
     * round 12). They are on the phone as loose street visits, so last edit wins alone would leave them there; the
     * import puts them back in their house instead. Counted as new because, to the user, the house gets its visits
     * back. Not shown on its own line; it is here so tests and the web importer can check the rule.
     */
    val relinkedVisits: Int = 0,
) {
    /** True when the import would change nothing; the screen then says so instead of offering "Import". */
    val isEmpty: Boolean
        get() = newHouses == 0 && updatedHouses == 0 && newVisits == 0 && updatedVisits == 0 && newPhotos == 0 &&
            restoredHouses == 0

    /** Rows that would be replaced. The confirmation dialog only appears when this is above zero. */
    val overwrites: Int get() = updatedHouses + updatedVisits
}

/**
 * The rows to write, already merged or re-identified. [photoSources] maps each photo row's **new** id to the ZIP
 * entry its bytes come from, because in [ImportMode.COPY] the row gets a fresh id while the file inside the ZIP
 * keeps the name the backup gave it.
 */
data class ImportActions(
    val mode: ImportMode,
    val houses: List<ExportHouse>,
    val visits: List<ExportVisit>,
    val photos: List<ExportPhoto>,
    val photoSources: Map<String, String>,
    /**
     * MERGE only: the ids in [houses] and [visits] that replace a row already on the phone (the preview's "the
     * backup has a newer version of"); every other row is new. So the result can say "Added 2, updated 3" in the
     * preview's own words rather than calling every written row "imported". Empty in COPY mode.
     */
    val updatedHouseIds: Set<String> = emptySet(),
    val updatedVisitIds: Set<String> = emptySet(),
    /**
     * MERGE with `restoreDeleted` only: the ids in [houses] that bring back a house this phone holds a tombstone
     * for. They keep their id; the writer clears the tombstone and stamps them *now*, so the undelete wins against
     * the server's (older) tombstone on the next sync instead of being deleted again by it.
     */
    val restoredHouseIds: Set<String> = emptySet(),
    /**
     * MERGE only (Android review, round 12): the ids in [visits] that are **relinked**, not written as the file has
     * them. Each is a visit of a house written over a tombstone ([restoredHouseIds], or an update of a house deleted
     * here) that is on the phone as a loose street visit, because the server unlinked it when it purged the house
     * (`HouseService.purge` sets `houseId = null` and stamps `updatedAt`). The writer keeps the phone's copy of the
     * visit (it is at least as new as the file's), sets its `houseId` from the file and stamps it *now*, past its own
     * `updatedAt`, so the relink beats the server's unlink on the next sync. A subset of the visits the preview
     * counts as new ([ImportPreview.relinkedVisits]).
     */
    val relinkedVisitIds: Set<String> = emptySet(),
)

/**
 * The pure part of importing a backup: decide what changes, then produce the rows. It never touches a database,
 * a file or a clock, so both apps behave identically and `ImportPlanTest` can pin every case.
 *
 * Local state is passed in as `id -> updatedAt` maps rather than entities, so the caller can read it with one
 * cheap query and this code does not need to know about Room or IndexedDB.
 *
 * **One rule holds this file together: the preview must promise exactly what the plan writes.** Every filter in
 * [plan] therefore has a counterpart in [preview], and `ImportPlanTest` asserts the two agree for both modes. A
 * number the import will not honour is worse than no number at all, because the user reads it as a promise about
 * their data.
 */
object ImportPlan {

    /**
     * [locallyDeletedHouseIds] are houses this phone holds a tombstone for. They are in [localHouses] on
     * purpose — a deleted house still has an `updatedAt`, and an older row in a backup must not bring it back —
     * but they are not somewhere a photo or a visit can be attached, so both sides have to know about them. Last
     * edit wins still applies: a house whose row in the *file* is newer than the tombstone is resurrected, and
     * then its photos do land.
     *
     * [localScoredHouseIds] are the live houses on this phone that have at least one checklist score; only
     * [ImportPreview.checklistsCleared] reads it.
     *
     * Two opt-in MERGE flags (UX review, round 11), ignored in COPY mode, which [plan] takes too and which must be
     * passed to both with the same values:
     *  - [restoreDeleted]: a house in the file that this phone holds a tombstone for, and that last edit wins would
     *    leave deleted, is brought back with its original id ([ImportPreview.restoredHouses]); its visits and
     *    photos then land like any kept house's. The undelete the screen offers as "Also bring back *n* houses
     *    deleted on this phone".
     *  - [skipUpdates]: a row the file has a newer version of is left as it is on the phone
     *    ([ImportPreview.keptMineHouses] / [ImportPreview.keptMineVisits]); only new rows are written. The Replace
     *    dialog's "Keep mine, add only what's new". A tombstone the file is newer than counts as such a row: it
     *    stays deleted unless [restoreDeleted] is on too.
     *
     * **A house written over a tombstone** (restored, or an update of a house deleted here) needs two more things
     * once the phone has synced the delete (Android review, round 12; checked against the backend's
     * `HouseService.purge` and `PhotoService.upload`):
     *  - The server unlinked its visits (`houseId = null`, newer `updatedAt`) and the pull brought them back that
     *    way. [localUnlinkedVisitIds] are this phone's live visits with no house; a visit of the file that belongs
     *    to such a house and is one of them is **relinked** whatever last edit wins says (counted in
     *    [ImportPreview.newVisits] and [ImportPreview.relinkedVisits], listed in [ImportActions.relinkedVisitIds]).
     *    An incoming row that is newer anyway is an ordinary update and carries its `houseId` itself.
     *  - The server turned its photos into tombstones and never takes a tombstoned id back ("a deleted photo is
     *    never resurrected"), so [plan] gives each of its photos a **fresh id**. The counts do not change.
     * On a phone that has not synced the delete neither applies: its visits are still linked and its photos are
     * still in [localPhotoIds].
     */
    fun preview(
        data: BackupData,
        localHouses: Map<String, Long>,
        localVisits: Map<String, Long>,
        localPhotoIds: Set<String>,
        photoEntriesInZip: Set<String>,
        mode: ImportMode,
        locallyDeletedHouseIds: Set<String> = emptySet(),
        localScoredHouseIds: Set<String> = emptySet(),
        restoreDeleted: Boolean = false,
        skipUpdates: Boolean = false,
        localUnlinkedVisitIds: Set<String> = emptySet(),
    ): ImportPreview {
        if (mode == ImportMode.COPY) {
            // Nothing local is consulted in COPY mode, not even a tombstone: every row gets a new id. What does
            // matter is that `plan` drops a visit or a photo whose houseId is not one of the *file's* own houses,
            // so the preview has to drop the same ones. A backup our exporter wrote can never contain such a row
            // (`ExportBundle.build` filters both to the houses it keeps); a hand-edited or third-party one can,
            // and surviving those is the whole job of an importer.
            val fileHouseIds = data.houses.mapTo(HashSet<String>()) { it.id }
            var withFiles = 0
            var orphaned = 0
            var missing = 0
            for (p in data.photos) {
                when {
                    BackupFormat.photoEntry(p.fileName) !in photoEntriesInZip -> missing++
                    p.houseId !in fileHouseIds -> orphaned++
                    else -> withFiles++
                }
            }
            return ImportPreview(
                mode = mode,
                newHouses = data.houses.size, updatedHouses = 0, newerHereHouses = 0, unchangedHouses = 0,
                newVisits = data.visits.count { it.houseId == null || it.houseId in fileHouseIds },
                updatedVisits = 0, newerHereVisits = 0, unchangedVisits = 0,
                newPhotos = withFiles, skippedPhotos = orphaned,
                photosMissingFromFile = missing,
            )
        }
        var newH = 0; var updH = 0; var hereH = 0; var sameH = 0; var clearedH = 0; var deletedH = 0
        var restoredH = 0; var mineH = 0
        for (h in data.houses) {
            val outcome = houseOutcome(h, localHouses, locallyDeletedHouseIds, restoreDeleted, skipUpdates)
            when (outcome) {
                HouseOutcome.NEW -> newH++
                HouseOutcome.UPDATE -> {
                    updH++
                    if (h.checklist.isEmpty() && h.id in localScoredHouseIds) clearedH++
                }
                HouseOutcome.RESTORE -> restoredH++
                // A tombstone that wins (or ties) keeps the house deleted: say so, not "kept" (deletedHereHouses).
                HouseOutcome.DELETED_HERE -> deletedH++
                HouseOutcome.KEPT_MINE -> mineH++
                HouseOutcome.NEWER_HERE -> hereH++
                HouseOutcome.SAME -> sameH++
            }
        }
        var newV = 0; var updV = 0; var hereV = 0; var sameV = 0; var deletedV = 0; var mineV = 0; var relinkV = 0
        val keptHouseIds = keptHouseIdsFor(data, localHouses, locallyDeletedHouseIds, restoreDeleted, skipUpdates)
        val overTombstone =
            overTombstoneHouseIds(data, localHouses, locallyDeletedHouseIds, restoreDeleted, skipUpdates)
        for (v in data.visits) {
            // A visit whose house is neither in the file nor on the phone would be an orphan; skip it quietly. One
            // whose house is deleted here and stays deleted is not written either, and is counted with that house.
            if (v.houseId != null && v.houseId !in keptHouseIds) {
                if (v.houseId in locallyDeletedHouseIds) deletedV++
                continue
            }
            when (visitOutcome(v, localVisits, overTombstone, localUnlinkedVisitIds, skipUpdates)) {
                VisitOutcome.NEW -> newV++
                VisitOutcome.RELINK -> {
                    newV++
                    relinkV++
                }
                VisitOutcome.UPDATE -> updV++
                VisitOutcome.KEPT_MINE -> mineV++
                VisitOutcome.NEWER_HERE -> hereV++
                VisitOutcome.SAME -> sameV++
            }
        }
        var newP = 0; var skipP = 0; var missingP = 0; var deletedP = 0
        for (p in data.photos) {
            val hasFile = BackupFormat.photoEntry(p.fileName) in photoEntriesInZip
            when {
                !hasFile -> missingP++
                // Same condition as `plan` below. Without it the preview promises photos of a house that is in
                // neither the file nor the phone, and the import then quietly writes fewer than it said.
                p.houseId !in keptHouseIds -> {
                    skipP++
                    if (p.houseId in locallyDeletedHouseIds) deletedP++
                }
                p.id in localPhotoIds -> skipP++
                else -> newP++
            }
        }
        return ImportPreview(
            mode, newH, updH, hereH, sameH, newV, updV, hereV, sameV, newP, skipP, missingP,
            checklistsCleared = clearedH,
            deletedHereHouses = deletedH,
            deletedHereVisits = deletedV,
            deletedHerePhotos = deletedP,
            restoredHouses = restoredH,
            keptMineHouses = mineH,
            keptMineVisits = mineV,
            relinkedVisits = relinkV,
        )
    }

    /**
     * How many houses a [ImportMode.COPY] import would put on this phone a second time: houses in the file whose id
     * is a **live** house here. The copy gets a new id, the original stays, and the user sees both.
     *
     * A house this phone holds a tombstone for is in [localHouses] (see [preview]) but is *not* a duplicate: it is
     * not on screen, so the copy is its only visible row. Warning that those houses "will appear twice" would be
     * untrue (Android review, 2026-09-22). (The better way back for them is now a merge with `restoreDeleted`,
     * which keeps their ids and adds nothing twice.) Pure and shared so the web importer uses the same rule.
     */
    fun copyDuplicates(
        data: BackupData,
        localHouses: Map<String, Long>,
        locallyDeletedHouseIds: Set<String> = emptySet(),
    ): Int = data.houses.count { it.id in localHouses && it.id !in locallyDeletedHouseIds }

    /**
     * The rows to write. [newId] supplies fresh UUIDs for [ImportMode.COPY] (Android `UUID.randomUUID()`, web
     * `crypto.randomUUID()`); it is a parameter so this stays pure and a test can make the ids predictable.
     *
     * [locallyDeletedHouseIds], [restoreDeleted], [skipUpdates] and [localUnlinkedVisitIds] mean the same thing as
     * in [preview], and have to be passed to both with the same values, or the numbers the user was shown stop
     * matching what gets written. In MERGE mode [newId] is called only for the photos of a house written over a
     * tombstone (see [preview]).
     *
     * [syncedDeletedHouseIds] are the tombstones of [locallyDeletedHouseIds] that have reached the server (Android:
     * `deleted = 1 AND dirty = 0`, pushed from here or pulled from another device). Only a house in it gets fresh
     * photo ids: a tombstone still waiting to be pushed has not been purged, so the server's photos are live and
     * keep their ids, including one this phone never downloaded (Android review, round 13). `null`, the default,
     * means "cannot tell" and treats every tombstone as synced, the safe side for the server. [preview] does not
     * take it: a photo's id never changes a count.
     */
    fun plan(
        data: BackupData,
        localHouses: Map<String, Long>,
        localVisits: Map<String, Long>,
        localPhotoIds: Set<String>,
        photoEntriesInZip: Set<String>,
        mode: ImportMode,
        newId: () -> String,
        locallyDeletedHouseIds: Set<String> = emptySet(),
        restoreDeleted: Boolean = false,
        skipUpdates: Boolean = false,
        localUnlinkedVisitIds: Set<String> = emptySet(),
        syncedDeletedHouseIds: Set<String>? = null,
    ): ImportActions {
        if (mode == ImportMode.COPY) {
            val houseIds = data.houses.associate { it.id to newId() }
            val houses = data.houses.map { it.copy(id = houseIds.getValue(it.id)) }
            val visits = data.visits
                .filter { it.houseId == null || it.houseId in houseIds }
                .map { it.copy(id = newId(), houseId = it.houseId?.let(houseIds::getValue)) }
            val photoSources = LinkedHashMap<String, String>()
            val photos = data.photos.mapNotNull { p ->
                val entry = BackupFormat.photoEntry(p.fileName)
                val houseId = houseIds[p.houseId]
                if (entry !in photoEntriesInZip || houseId == null) return@mapNotNull null
                val id = newId()
                photoSources[id] = entry
                p.copy(id = id, houseId = houseId)
            }
            return ImportActions(mode, houses, visits, photos, photoSources)
        }

        val updatedHouseIds = HashSet<String>()
        val restoredHouseIds = HashSet<String>()
        val houses = data.houses.filter {
            val outcome = houseOutcome(it, localHouses, locallyDeletedHouseIds, restoreDeleted, skipUpdates)
            if (outcome == HouseOutcome.UPDATE) updatedHouseIds.add(it.id)
            if (outcome == HouseOutcome.RESTORE) restoredHouseIds.add(it.id)
            outcome.writes
        }
        val keptHouseIds = keptHouseIdsFor(data, localHouses, locallyDeletedHouseIds, restoreDeleted, skipUpdates)
        val overTombstone =
            overTombstoneHouseIds(data, localHouses, locallyDeletedHouseIds, restoreDeleted, skipUpdates)
        val updatedVisitIds = HashSet<String>()
        val relinkedVisitIds = HashSet<String>()
        val visits = data.visits.filter {
            if (it.houseId != null && it.houseId !in keptHouseIds) return@filter false
            when (visitOutcome(it, localVisits, overTombstone, localUnlinkedVisitIds, skipUpdates)) {
                VisitOutcome.NEW -> true
                VisitOutcome.RELINK -> {
                    relinkedVisitIds.add(it.id)
                    true
                }
                VisitOutcome.UPDATE -> {
                    updatedVisitIds.add(it.id)
                    true
                }
                VisitOutcome.KEPT_MINE, VisitOutcome.NEWER_HERE, VisitOutcome.SAME -> false
            }
        }
        val photoSources = LinkedHashMap<String, String>()
        val photos = data.photos.mapNotNull { p ->
            val entry = BackupFormat.photoEntry(p.fileName)
            // Checked against the backup's own id: a photo this phone still has (a delete that was never synced
            // keeps its rows) is not written again under a new one.
            if (entry !in photoEntriesInZip || p.id in localPhotoIds || p.houseId !in keptHouseIds) {
                return@mapNotNull null
            }
            // The server keeps a tombstone for every photo of a house it purged and never takes that id back, so
            // an upload under the old id would be "accepted" and dropped. A fresh id is the only one that lands.
            // A tombstone that has not been pushed yet was not purged: its photos keep their ids.
            val purgedOnServer = p.houseId in overTombstone &&
                (syncedDeletedHouseIds == null || p.houseId in syncedDeletedHouseIds)
            val id = if (purgedOnServer) newId() else p.id
            photoSources[id] = entry
            if (id == p.id) p else p.copy(id = id)
        }
        return ImportActions(
            mode = mode,
            houses = houses,
            visits = visits,
            photos = photos,
            photoSources = photoSources,
            updatedHouseIds = updatedHouseIds,
            updatedVisitIds = updatedVisitIds,
            restoredHouseIds = restoredHouseIds,
            relinkedVisitIds = relinkedVisitIds,
        )
    }

    /**
     * The houses of the file a MERGE writes **over a tombstone**: restored with [restoreDeleted], or updated because
     * the file's row is newer than the tombstone. If the phone synced the delete, the server has unlinked their
     * visits and tombstoned their photos; see [preview].
     */
    private fun overTombstoneHouseIds(
        data: BackupData,
        localHouses: Map<String, Long>,
        locallyDeletedHouseIds: Set<String>,
        restoreDeleted: Boolean,
        skipUpdates: Boolean,
    ): Set<String> = data.houses.asSequence()
        .filter { it.id in locallyDeletedHouseIds }
        .filter {
            when (houseOutcome(it, localHouses, locallyDeletedHouseIds, restoreDeleted, skipUpdates)) {
                HouseOutcome.RESTORE, HouseOutcome.UPDATE -> true
                else -> false
            }
        }
        .mapTo(HashSet()) { it.id }

    /** What a MERGE does with one visit of the file whose house it keeps; shared by [preview] and [plan]. */
    private enum class VisitOutcome { NEW, UPDATE, RELINK, KEPT_MINE, NEWER_HERE, SAME }

    private fun visitOutcome(
        visit: ExportVisit,
        localVisits: Map<String, Long>,
        overTombstone: Set<String>,
        localUnlinkedVisitIds: Set<String>,
        skipUpdates: Boolean,
    ): VisitOutcome {
        val verdict = compare(localVisits[visit.id], visit.updatedAt)
        return when {
            verdict == Verdict.NEW -> VisitOutcome.NEW
            // Newer in the file: an ordinary update, and the file's row carries its houseId, so it relinks too.
            verdict == Verdict.INCOMING_NEWER && !skipUpdates -> VisitOutcome.UPDATE
            // The server unlinked it when it purged the house this import brings back: put it back in its house,
            // whatever the timestamps say (the unlink is what made the phone's copy "newer").
            visit.houseId != null && visit.houseId in overTombstone && visit.id in localUnlinkedVisitIds ->
                VisitOutcome.RELINK
            verdict == Verdict.INCOMING_NEWER -> VisitOutcome.KEPT_MINE
            verdict == Verdict.LOCAL_NEWER -> VisitOutcome.NEWER_HERE
            else -> VisitOutcome.SAME
        }
    }

    /**
     * The houses a [ImportMode.MERGE] import will leave in place and not tombstoned: everything already on the
     * phone that is not deleted, plus every house in the file that the import writes — new, newer in the file
     * (which also resurrects a deleted one), or restored with [restoreDeleted].
     *
     * Visits and photos hanging off anything else are counted as skipped and not written. Reporting them as new
     * was the bug this function exists to prevent: `Repository.applyImport` re-checks
     * `houses().get(houseId)?.deleted == false` before writing a photo file, so a photo of a house the user had
     * deleted locally was promised by the preview and then silently dropped.
     */
    private fun keptHouseIdsFor(
        data: BackupData,
        localHouses: Map<String, Long>,
        locallyDeletedHouseIds: Set<String>,
        restoreDeleted: Boolean,
        skipUpdates: Boolean,
    ): Set<String> {
        val kept = HashSet(localHouses.keys)
        kept.removeAll(locallyDeletedHouseIds)
        for (h in data.houses) {
            if (houseOutcome(h, localHouses, locallyDeletedHouseIds, restoreDeleted, skipUpdates).writes) kept.add(h.id)
        }
        return kept
    }

    /** What a MERGE does with one house of the file; the one decision [preview], [plan] and [keptHouseIdsFor] share. */
    private enum class HouseOutcome(val writes: Boolean) {
        /** Not on the phone at all. */
        NEW(true),

        /** The file's row is newer: it replaces the phone's (or resurrects a tombstone, last edit wins). */
        UPDATE(true),

        /** A tombstone here that would win, brought back anyway because the user asked ([restoreDeleted]). */
        RESTORE(true),

        /** A tombstone here that wins or ties: the house stays deleted. */
        DELETED_HERE(false),

        /** The file's row is newer, but the user asked to keep the phone's ([skipUpdates]). */
        KEPT_MINE(false),
        NEWER_HERE(false),
        SAME(false),
    }

    private fun houseOutcome(
        house: ExportHouse,
        localHouses: Map<String, Long>,
        locallyDeletedHouseIds: Set<String>,
        restoreDeleted: Boolean,
        skipUpdates: Boolean,
    ): HouseOutcome {
        val verdict = compare(localHouses[house.id], house.updatedAt)
        val deletedHere = house.id in locallyDeletedHouseIds
        return when {
            verdict == Verdict.NEW -> HouseOutcome.NEW
            verdict == Verdict.INCOMING_NEWER && !skipUpdates -> HouseOutcome.UPDATE
            deletedHere && restoreDeleted -> HouseOutcome.RESTORE
            deletedHere -> HouseOutcome.DELETED_HERE
            verdict == Verdict.INCOMING_NEWER -> HouseOutcome.KEPT_MINE
            verdict == Verdict.LOCAL_NEWER -> HouseOutcome.NEWER_HERE
            else -> HouseOutcome.SAME
        }
    }

    private enum class Verdict { NEW, INCOMING_NEWER, LOCAL_NEWER, SAME }

    /**
     * Last edit wins, the same rule the sync engine uses ([com.househunt.shared.sync.SyncRules]): equal
     * timestamps mean "already have it", so importing the same file twice changes nothing the second time.
     */
    private fun compare(localUpdatedAt: Long?, incomingUpdatedAt: Long): Verdict = when {
        localUpdatedAt == null -> Verdict.NEW
        incomingUpdatedAt > localUpdatedAt -> Verdict.INCOMING_NEWER
        incomingUpdatedAt == localUpdatedAt -> Verdict.SAME
        else -> Verdict.LOCAL_NEWER
    }
}
