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

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * The folder that holds Doorprints' data on iOS (CMP-8; threat model F-03, SEC-011): `Application Support/Doorprints`,
 * Apple's place for internal app data (Documents is visible to the user when file sharing is on), created on first
 * use and excluded from iCloud and computer backups (`NSURLIsExcludedFromBackupKey`), as Android keeps the database
 * out of its backups. Returns the folder's path. Public since CMP-8b: the iOS shell (`:ui`'s `IosAppContainer`) keeps
 * the photo files in its `photos` folder.
 */
@OptIn(ExperimentalForeignApi::class)
fun iosDataDirectory(): String {
    val support = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    return prepareDataDirectory(requireNotNull(support?.path) { "No Application Support folder" } + "/" + DATA_FOLDER)
}

/**
 * Creates [path] (with any missing parents) if needed and excludes it from backup; the flag on the folder covers
 * everything in it. Safe to call on every start: an existing folder, or one already excluded, is left as it is.
 * Throws if the folder cannot be made or the flag cannot be set (or does not read back as set), so the data never
 * lands in a backed-up place.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun prepareDataDirectory(path: String): String {
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val made = NSFileManager.defaultManager.createDirectoryAtPath(
            path = path,
            withIntermediateDirectories = true,
            attributes = null,
            error = error.ptr,
        )
        check(made) { "Could not create the data folder: ${error.value?.localizedDescription}" }
    }
    if (!isExcludedFromBackup(path)) {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val set = NSURL.fileURLWithPath(path, isDirectory = true)
                .setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = error.ptr)
            check(set) { "Could not exclude the data folder from backup: ${error.value?.localizedDescription}" }
        }
        check(isExcludedFromBackup(path)) { "The data folder's exclude-from-backup flag did not stick: $path" }
    }
    return path
}

/**
 * Whether [path] carries `NSURLIsExcludedFromBackupKey`. Read through a new `NSURL`, so a value cached by another
 * instance cannot answer for the file system.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun isExcludedFromBackup(path: String): Boolean {
    val values = NSURL.fileURLWithPath(path).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
    return (values?.get(NSURLIsExcludedFromBackupKey) as? NSNumber)?.boolValue == true
}

/** The folder under Application Support; see [iosDataDirectory]. */
private const val DATA_FOLDER = "Doorprints"
