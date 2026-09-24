package app.doorprints.data

import android.content.Context
import androidx.room.Room

/**
 * Opens the app's Room database on Android (CMP-4 P4a). [AppDatabase], its entities, DAOs and `MIGRATION_1_2` live in
 * `:shared` commonMain; the Android-only part stays here: the [Context], the file name through [DatabaseFile] (which
 * first moves a `househunt.db` from before the rename) and the builder. No SQLite driver is set, so Room keeps using
 * the framework SQLite through its SupportSQLite open helper: the same file, journal mode (WAL where the device
 * supports it), threads and invalidation tracking as before the move.
 */
fun AppDatabase.Companion.create(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, DatabaseFile.resolve(context))
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .build()
