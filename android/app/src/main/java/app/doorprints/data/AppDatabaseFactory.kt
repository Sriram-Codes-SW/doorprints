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
