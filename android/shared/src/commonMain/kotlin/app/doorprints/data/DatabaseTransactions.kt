package app.doorprints.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/**
 * Runs [block] in one write transaction on the database's writer connection: it commits when [block] returns and
 * rolls back when it throws (a cancellation included). The DAO calls inside join it. Room's common API (CMP-4 P4c,
 * S4b-BL-23), in place of Android's `RoomDatabase.withTransaction`.
 *
 * **The same transaction on Android** (Room 2.8.5, no driver, so the framework open helper: "compatibility mode").
 * `useWriterConnection` moves [block] onto Room's transaction thread as `withTransaction` did, and `BEGIN IMMEDIATE`
 * becomes `SupportSQLiteDatabase.beginTransactionNonExclusive()`, the call `withTransaction` made in WAL mode (the
 * framework's default here). Only without WAL did `withTransaction` take `beginTransaction()` (EXCLUSIVE); the app's
 * one process has a single connection then, so nobody else could read during the write either way. A DAO call inside
 * sees the open transaction and nests into it (Room does not start a second one), and after the commit Room refreshes
 * the invalidation tracker once, so each observing `Flow` re-reads once. `RepositoryTransactionTest` pins the commit,
 * the rollback and the refresh.
 */
suspend fun <R> AppDatabase.withImmediateTransaction(block: suspend () -> R): R {
    // The block runs in Room's transaction context, not under the caller's Job, so cancelling the caller does not
    // stop it. `withTransaction` rolled back when the caller was cancelled; to keep that, the caller is checked before
    // the commit, inside the transaction, so a cancelled caller rolls everything back (RepositoryTransactionTest).
    val caller = currentCoroutineContext()
    return useWriterConnection { transactor ->
        transactor.immediateTransaction {
            block().also { caller.ensureActive() }
        }
    }
}

/** The tables a copy is built from; see [localTablesChanged]. Private, so no caller can change what is observed. */
private val LOCAL_TABLES = arrayOf("houses", "visits", "photos")

/**
 * Emits at once, and again after each committed change to the houses, visits or photos table (Room's common
 * `InvalidationTracker.createFlow`; the same function `:app` called before CMP-4 P4c).
 */
fun AppDatabase.localTablesChanged(): Flow<Set<String>> = invalidationTracker.createFlow(*LOCAL_TABLES)
