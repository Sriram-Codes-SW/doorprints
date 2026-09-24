package app.doorprints.export

import app.doorprints.data.HouseEntity
import app.doorprints.data.VisitEntity

/**
 * The decisions behind *Undo this import* for a copy import (Android review, round 17), free of Room and Android so
 * that every rule is pinned by a JVM test (`CopyUndoTest`). `Repository.undoCopyImport` only reads the rows, asks
 * these functions and writes what they return.
 *
 * The undo writes **synced tombstones**: what it removes is removed from the server and from every other device too.
 * So the rule leans one way: anything the user may have touched since the import is kept, with everything in it, and
 * only a row that is exactly as the import wrote it goes.
 *
 * It is also where a copy's timestamps are chosen ([copyStamp]), because the undo can only recognise an untouched row
 * by the `updatedAt` it was written with.
 */
object CopyUndo {

    /** What the undo does with one recorded row. */
    enum class Decision {
        /** Exactly as the import wrote it: becomes a tombstone (a photo: its row and file are removed). */
        REMOVE,

        /** Touched since the import (or now part of something that stays): left alone, and a house is counted. */
        KEEP,

        /** Gone or already deleted since: nothing to do, and not counted. */
        SKIP,
    }

    /**
     * A recorded house as it is on the phone now: null when the row is gone. [liveVisits] are the house's visits that
     * are not deleted (id to `updatedAt`), [livePhotoIds] its photos that are not deleted (a photo the user deleted
     * that is still waiting to tell the server is not live).
     */
    data class HouseNow(
        val updatedAt: Long,
        val deleted: Boolean,
        val liveVisits: Map<String, Long>,
        val livePhotoIds: Collection<String>,
    )

    /**
     * A recorded house ([recordedAt]: the `updatedAt` the import wrote it with). It is **kept** when:
     *  - its own `updatedAt` changed (any edit, from this phone or synced from another device);
     *  - one of its live visits is not one the import wrote with that very `updatedAt`: a recorded visit edited
     *    since, or a visit the import did not add (a "been here", a visit moved into it) — [recordedVisits] maps each
     *    visit the import added to its `updatedAt`, and a visit that is not in it never matches;
     *  - it has a live photo the import did not add ([recordedPhotoIds]).
     *
     * A recorded photo the user has deleted since is not live and does not keep the house: deleting something is not
     * a reason to keep the rest. A house already deleted, or gone, is **skipped**.
     */
    fun decideHouse(
        now: HouseNow?,
        recordedAt: Long,
        recordedVisits: Map<String, Long>,
        recordedPhotoIds: Set<String>,
    ): Decision {
        if (now == null || now.deleted) return Decision.SKIP
        if (now.updatedAt != recordedAt) return Decision.KEEP
        for ((id, updatedAt) in now.liveVisits) {
            val written = recordedVisits[id] ?: return Decision.KEEP
            if (written != updatedAt) return Decision.KEEP
        }
        if (now.livePhotoIds.any { it !in recordedPhotoIds }) return Decision.KEEP
        return Decision.REMOVE
    }

    /** A recorded visit as it is on the phone now: null when the row is gone. */
    data class VisitNow(val updatedAt: Long, val deleted: Boolean, val houseId: String?)

    /**
     * A recorded visit ([recordedAt]: the `updatedAt` the import wrote it with), decided after the houses
     * ([removedHouseIds]: the houses this undo removes). It is **removed** when it is unchanged and is either a loose
     * street visit or in a removed house. It is **kept** when it was edited since (which includes being moved to
     * another house or out of one), or when it is in a house that stays — a kept copy, or any other house. A visit
     * already deleted, or gone, is **skipped**.
     */
    fun decideVisit(now: VisitNow?, recordedAt: Long, removedHouseIds: Set<String>): Decision {
        if (now == null || now.deleted) return Decision.SKIP
        if (now.updatedAt != recordedAt) return Decision.KEEP
        val houseId = now.houseId
        if (houseId != null && houseId !in removedHouseIds) return Decision.KEEP
        return Decision.REMOVE
    }

    /** A recorded photo goes, row and file, exactly when its house is removed (the server purges it with the house). */
    fun removesPhoto(photoHouseId: String, removedHouseIds: Set<String>): Boolean = photoHouseId in removedHouseIds

    /**
     * The `updatedAt` of an undo's tombstone: now, and past the row's own whatever the clock says, so the delete is the
     * newest edit on the server too (as a restored house is stamped, `Repository.applyImport`).
     */
    fun tombstoneStamp(updatedAt: Long, now: Long): Long = maxOf(now, updatedAt + 1)

    /** A removed house: a tombstone that syncs, as `Repository.deleteHouse` writes it. */
    fun houseTombstone(house: HouseEntity, now: Long): HouseEntity =
        house.copy(deleted = true, updatedAt = tombstoneStamp(house.updatedAt, now), dirty = true)

    /**
     * A removed visit: a tombstone that syncs, **with `houseId = null`** (Android review, round 17). A tombstone needs
     * no link (the server purges its place anyway), and without one it is pushed before the houses
     * (`SyncRules.pushesBeforeHouses`). Otherwise the house's tombstone reaches the server first, its purge
     * (`HouseService.purge` → `VisitRepository.unlinkHouse`) unlinks this visit and stamps it with *server* now, the
     * visit's own, older tombstone then loses last-write-wins, and the next pull brings every copied visit back as a
     * live loose street visit on every device.
     */
    fun visitTombstone(visit: VisitEntity, now: Long): VisitEntity =
        visit.copy(houseId = null, deleted = true, updatedAt = tombstoneStamp(visit.updatedAt, now), dirty = true)

    /**
     * The `updatedAt` a copy is written with (Android review, round 17): the backup's, as before, but never later than
     * [now]. A copy is a new row, so a future timestamp from a backup made on a phone whose clock ran fast means
     * nothing; kept as it was, the server would clamp it to *its* now (`ClientClock.accept`, beyond
     * `app.sync.max-clock-skew-seconds`), the next pull would write that back here, and the undo would see every
     * such copy as "edited since" and remove nothing. (A clock that is fast on *this* phone still gets clamped; see
     * android/shared/README.md section 8.)
     */
    fun copyStamp(backupUpdatedAt: Long, now: Long): Long = minOf(backupUpdatedAt, now)
}
