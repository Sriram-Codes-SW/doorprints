package app.doorprints.shared.sync

/**
 * A locally stored row that takes part in two-way sync. On Android the Room entities (HouseEntity, VisitEntity in
 * :app) implement it; a future iOS store would do the same.
 */
interface SyncRecord {
    /** Epoch milliseconds of the last edit on whichever device made it. */
    val updatedAt: Long

    /** True while this row has local changes the server has not seen yet. */
    val dirty: Boolean
}

/**
 * Pure conflict rules for the sync loop (Repository.sync in :app), free of platform and database types.
 *
 * "Last edit wins": when the server sends a row this device also changed and has not pushed yet (dirty), the local
 * copy is kept only if it was edited strictly later. On a tie the server copy wins, so every device converges on
 * the same row.
 */
object SyncRules {
    fun keepLocal(localDirty: Boolean, localUpdatedAt: Long, incomingUpdatedAt: Long): Boolean =
        localDirty && localUpdatedAt > incomingUpdatedAt

    fun keepLocal(local: SyncRecord?, incoming: SyncRecord): Boolean =
        local != null && keepLocal(local.dirty, local.updatedAt, incoming.updatedAt)

    /**
     * Push order (Android review, round 17): a dirty visit that is a tombstone **with no house** is pushed before
     * any house, every other dirty visit after the houses.
     *
     * When a house tombstone reaches the server, its purge unlinks every visit still linked to that house and stamps
     * them with the server's now (`HouseService.purge` → `VisitRepository.unlinkHouse`). A visit tombstone pushed
     * after that carries an older stamp, loses last-write-wins, and the next pull brings the visit back as a live
     * loose visit. The undo of a copy import deletes houses and their visits together, and writes those visit
     * tombstones with `houseId = null` so they go first and the purge finds nothing left to unlink. They need no
     * house row on the server (no foreign key to satisfy), so pushing them first is always safe. Visits that are
     * live, or tombstones that still name a house, keep going after the houses, whose rows they may need.
     */
    fun pushesBeforeHouses(deleted: Boolean, houseId: String?): Boolean = deleted && houseId == null

    /** [visits] split by [pushesBeforeHouses]: first the ones to push before the houses, then the rest, in order. */
    fun <V> visitsByPushOrder(
        visits: List<V>,
        deleted: (V) -> Boolean,
        houseId: (V) -> String?,
    ): Pair<List<V>, List<V>> = visits.partition { pushesBeforeHouses(deleted(it), houseId(it)) }
}
