/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

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
