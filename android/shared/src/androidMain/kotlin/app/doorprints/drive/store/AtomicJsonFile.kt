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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The lock of a [file] by its canonical path (so `a/../x.json` and `x.json` share one); see [PathLocks]. */
fun PathLocks.of(file: File): PlatformLock = of(file.canonicalPath)

/**
 * A small text file written whole or not at all: the text goes to `<name>.tmp` beside it, is synced to disk, and
 * replaces the file by an atomic rename. A reader sees the old content or the new, never half; a crash leaves at worst
 * a stale `.tmp`, which the next write overwrites. A file that is missing, empty, or not readable reads as absent
 * (`null`) and never throws; a write that cannot be made throws [IOException] (state must not be lost silently).
 * Every store instance of this process that names the same file shares one lock ([PathLocks]): not a cross-process lock
 * (the app has one process and one writer per store).
 */
class AtomicJsonFile(val file: File) : StateFile {
    override val lock: PlatformLock get() = PathLocks.of(file)

    override fun readText(): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() } else null
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    override fun exists(): Boolean = file.isFile

    override fun stamp(): Pair<Long, Long>? = if (file.isFile) file.lastModified() to file.length() else null

    @Throws(IOException::class)
    override fun delete() {
        lock.withLock {
            if (file.exists() && !file.delete()) throw IOException("cannot remove ${file.name}")
        }
    }

    @Throws(IOException::class)
    override fun writeText(text: String) {
        lock.withLock {
            val dir = file.absoluteFile.parentFile ?: throw IOException("no folder for ${file.name}")
            dir.mkdirs()
            if (!dir.isDirectory) throw IOException("not a folder: ${dir.name}")
            val tmp = File(dir, file.name + TMP_SUFFIX)
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: IOException) {
                if (tmp.isFile) tmp.delete()
                throw e
            }
        }
    }

    private companion object {
        const val TMP_SUFFIX = ".tmp"
    }
}

actual fun stateFileAt(path: String): StateFile = AtomicJsonFile(File(path))

/**
 * The Android spellings of the file-based stores' constructors: the same names over a [File], so the app and its tests
 * keep writing `FileDriveStateStore(File(...))` (the stores are common code since the iPhone's Drive, over [StateFile]).
 */
fun FileDriveStateStore(file: File) = FileDriveStateStore(AtomicJsonFile(file))

fun FileSyncStateStore(file: File) = FileSyncStateStore(AtomicJsonFile(file))

fun FilePhotoStateStore(file: File) = FilePhotoStateStore(AtomicJsonFile(file))

fun FileKeysWatermarkStore(file: File) = FileKeysWatermarkStore(AtomicJsonFile(file))

fun FileControlWatermarkStore(file: File) = FileControlWatermarkStore(AtomicJsonFile(file))

fun FileFolderTrustStores(dir: File) = FileFolderTrustStores(dir.path)

fun FileDeletionStore(dir: File, forgetter: FolderForgetter? = null) = FileDeletionStore(dir.path, forgetter)

fun DriveFileStores(dir: File) = DriveFileStores(dir.path)
