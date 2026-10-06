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

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * One lock per file, shared by every store instance of this process that names the same file (by canonical path), so a
 * compare-and-set and a read-modify-write on two instances cannot interleave. Not a cross-process lock: the app has
 * one process and one writer per store (the backup worker or the sync worker, one at a time).
 */
internal object PathLocks {
    private val locks = ConcurrentHashMap<String, Any>()

    fun of(file: File): Any = locks.computeIfAbsent(file.canonicalPath) { Any() }
}

/**
 * A small text file written whole or not at all: the text goes to `<name>.tmp` beside it, is synced to disk, and
 * replaces the file by an atomic rename. A reader sees the old content or the new, never half; a crash leaves at worst
 * a stale `.tmp`, which the next write overwrites. A file that is missing, empty, or not readable reads as absent
 * (`null`) and never throws; a write that cannot be made throws [IOException] (state must not be lost silently).
 */
internal class AtomicJsonFile(val file: File) {
    fun readText(): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() } else null
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    @Throws(IOException::class)
    fun writeText(text: String) {
        synchronized(PathLocks.of(file)) {
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
