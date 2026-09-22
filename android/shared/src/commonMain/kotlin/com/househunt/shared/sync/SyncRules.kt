package com.househunt.shared.sync

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
}
