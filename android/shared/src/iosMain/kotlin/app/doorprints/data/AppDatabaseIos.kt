package app.doorprints.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * The iOS builder for [AppDatabase] (CMP-4 P4a): `doorprints.db` in the app's Application Support folder
 * (Apple's place for internal data; Documents is visible to the user when file sharing is on), opened with Room's
 * bundled SQLite driver. Compile-only until the iOS shell (CMP-8); nothing calls it yet. iOS has no file from before
 * the rename, so there is no `DatabaseFile` move here.
 */
@OptIn(ExperimentalForeignApi::class)
fun iosAppDatabase(): AppDatabase {
    val support = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    val path = requireNotNull(support?.path) { "No Application Support folder" } + "/" + IOS_DATABASE_NAME
    return Room.databaseBuilder<AppDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .build()
}

/** Same name as Android's `DatabaseFile.NAME`. */
private const val IOS_DATABASE_NAME = "doorprints.db"
