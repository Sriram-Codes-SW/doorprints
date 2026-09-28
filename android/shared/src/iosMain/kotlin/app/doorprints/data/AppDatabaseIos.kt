package app.doorprints.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The iOS builder for [AppDatabase] (CMP-4 P4a, CMP-8): `doorprints.db` in the app's data folder,
 * `Application Support/Doorprints`, which is excluded from backup ([iosDataDirectory]; threat model F-03, SEC-011).
 * iOS has no file from before the rename, so there is no `DatabaseFile` move here.
 */
fun iosAppDatabase(): AppDatabase = iosAppDatabase(iosDataDirectory() + "/" + IOS_DATABASE_NAME)

/**
 * [AppDatabase] on the file at [path], opened with Room's bundled SQLite driver (iOS has no framework SQLite for
 * Room) and [AppDatabase.MIGRATION_1_2]. The folder must exist. The app calls the overload without arguments; the
 * tests pass a file in a temporary folder (`AppDatabaseIosTest`, S4b-BL-24).
 */
fun iosAppDatabase(path: String): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .build()

/** Same name as Android's `DatabaseFile.NAME`. */
private const val IOS_DATABASE_NAME = "doorprints.db"
