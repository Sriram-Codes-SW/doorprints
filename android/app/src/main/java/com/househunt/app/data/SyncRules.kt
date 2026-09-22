package com.househunt.app.data

/**
 * Pure conflict rules for [Repository.sync], kept free of Android and Room types so they are unit-tested on the JVM.
 *
 * "Last edit wins": when the server sends a row this phone also changed and has not pushed yet (dirty), the local
 * copy is kept only if it was edited strictly later. On a tie the server copy wins, so every device converges on
 * the same row.
 */
object SyncRules {
    fun keepLocal(localDirty: Boolean, localUpdatedAt: Long, incomingUpdatedAt: Long): Boolean =
        localDirty && localUpdatedAt > incomingUpdatedAt

    fun keepLocal(local: HouseEntity?, incoming: HouseEntity): Boolean =
        local != null && keepLocal(local.dirty, local.updatedAt, incoming.updatedAt)

    fun keepLocal(local: VisitEntity?, incoming: VisitEntity): Boolean =
        local != null && keepLocal(local.dirty, local.updatedAt, incoming.updatedAt)
}
