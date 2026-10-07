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

package app.doorprints.drive.store

import app.doorprints.concurrent.PlatformLock
import app.doorprints.drive.ios.toByteArray
import app.doorprints.drive.ios.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.IOException
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/**
 * [StateFile] on the iPhone: the text is written with `NSData.writeToFile(_:atomically: true)` (a temporary file in the
 * same folder, moved over the old one: a reader sees the old text or the new) after the folder is made. Files live in
 * the app's `drive` folder of the data directory, which carries `NSURLIsExcludedFromBackupKey` (`prepareDataDirectory`),
 * so iCloud and computer backups never copy a device id or a pin to another phone. The default file protection (readable
 * after the first unlock) is kept on purpose: the background run reads these while the phone is locked again. A file
 * that is missing, blank or unreadable reads as absent and never throws; a write that fails throws [IOException].
 */
@OptIn(ExperimentalForeignApi::class)
class IosStateFile(private val path: String) : StateFile {
    override val lock: PlatformLock get() = PathLocks.of(path)

    override fun readText(): String? =
        NSData.dataWithContentsOfFile(path)?.toByteArray()?.decodeToString()?.takeIf { it.isNotBlank() }

    override fun exists(): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    override fun stamp(): Pair<Long, Long>? {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return null
        val modified = (attributes[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970?.times(1000.0)?.toLong() ?: 0L
        val size = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
        return modified to size
    }

    override fun delete() {
        lock.withLock {
            val manager = NSFileManager.defaultManager
            if (manager.fileExistsAtPath(path) && !manager.removeItemAtPath(path, error = null)) {
                throw IOException("cannot remove ${path.substringAfterLast('/')}")
            }
        }
    }

    override fun writeText(text: String) {
        lock.withLock {
            val folder = path.substringBeforeLast('/', "")
            if (folder.isEmpty()) throw IOException("no folder for ${path.substringAfterLast('/')}")
            val made = NSFileManager.defaultManager.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = null, error = null)
            if (!made) throw IOException("not a folder: ${folder.substringAfterLast('/')}")
            if (!text.encodeToByteArray().toNSData().writeToFile(path, atomically = true)) {
                throw IOException("cannot write ${path.substringAfterLast('/')}")
            }
        }
    }
}

actual fun stateFileAt(path: String): StateFile = IosStateFile(path)
