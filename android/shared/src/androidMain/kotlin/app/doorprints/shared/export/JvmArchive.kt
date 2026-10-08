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

package app.doorprints.shared.export

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/*
 * Android's side of the common ZIP code (S4b-BL-76): SHA-256 from `MessageDigest`, raw DEFLATE from `Inflater` and a
 * file read at any position through `RandomAccessFile`, what `IosArchive.kt` is on the iPhone. With them Android imports
 * through the same `BackupArchive` as the iPhone.
 */

/** [ArchiveTools] on the JVM. */
val jvmArchiveTools: ArchiveTools = ArchiveTools(
    sha256 = {
        object : Sha256 {
            private val digest = MessageDigest.getInstance("SHA-256")
            override fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
            override fun digest(): ByteArray = digest.digest()
        }
    },
    inflate = ::jvmInflateRaw,
)

/**
 * Raw DEFLATE through `Inflater(nowrap = true)` in 64 KiB steps; null once more than [maxBytes] came out, checked after
 * every step, so a bomb costs [maxBytes] and a step. Throws [IllegalArgumentException] for broken or cut-short data.
 */
internal fun jvmInflateRaw(compressed: ByteArray, maxBytes: Int): ByteArray? {
    val inflater = Inflater(true)
    try {
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val n = try {
                inflater.inflate(chunk)
            } catch (e: DataFormatException) {
                throw IllegalArgumentException("broken DEFLATE", e)
            }
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IllegalArgumentException("cut short")
            if (out.size().toLong() + n > maxBytes) return null
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}

/** A file read at any position; one reader at a time (the import). */
class FileRandomSource(file: File) : RandomSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    /**
     * Reads up to [length] bytes at [position]; 0 at the end of the file. Synchronized because seek and read share
     * the file position.
     */
    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        raf.seek(position)
        return raf.read(buffer, offset, length).coerceAtLeast(0)
    }

    override fun close() = raf.close()
}
