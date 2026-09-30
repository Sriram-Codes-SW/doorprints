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

package app.doorprints

import app.doorprints.data.HouseEntity
import app.doorprints.data.toExport
import app.doorprints.export.BackupOpen
import app.doorprints.export.BackupReader
import app.doorprints.export.Exporters
import app.doorprints.shared.export.ArchiveOpen
import app.doorprints.shared.export.ArchiveTools
import app.doorprints.shared.export.BackupArchive
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.CopyPhotos
import app.doorprints.shared.export.CopySink
import app.doorprints.shared.export.CopyWriter
import app.doorprints.shared.export.ExportBundle
import app.doorprints.shared.export.ExportFormat
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.ExportPhoto
import app.doorprints.shared.export.RandomSource
import app.doorprints.shared.export.Sha256
import app.doorprints.shared.model.HouseStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * A backup made on one phone imports on the other (S4b-BL-81): the iPhone's common [CopyWriter] backup opens in
 * Android's [BackupReader], and Android's [Exporters] backup (deflated) opens in the iPhone's common [BackupArchive],
 * with the same rows and photo either way. JVM stand-ins for the iPhone's CommonCrypto and zlib.
 */
class CommonArchiveInteropTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val options = ExportOptions(language = "en", utcOffsetMinutes = 330, exportedAtMillis = 1_790_072_130_000)

    private val house = HouseEntity(
        id = "h1", label = "Sunrise", address = "12, 5th Cross", street = "5th Cross", lat = 12.97, lon = 77.64,
        status = HouseStatus.SHORTLISTED, createdAt = 1_790_000_000_000, updatedAt = 1_790_072_130_120,
        deleted = false, dirty = false,
    )

    private val photo = ExportPhoto(id = "p1", houseId = "h1", fileName = "p1.jpg", createdAt = 1_790_004_000_000)
    private val photoBytes = ByteArray(4000) { (it * 13).toByte() }
    private val bundle = ExportBundle.build(options, listOf(house.toExport()), emptyList(), listOf(photo))

    private val tools = ArchiveTools(
        sha256 = {
            object : Sha256 {
                val digest = MessageDigest.getInstance("SHA-256")
                override fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
                override fun digest(): ByteArray = digest.digest()
            }
        },
        inflate = { compressed, max ->
            val inflater = Inflater(true)
            try {
                inflater.setInput(compressed)
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var over = false
                while (!inflater.finished() && !over) {
                    val n = inflater.inflate(chunk)
                    require(n > 0 || !inflater.needsInput()) { "cut short" }
                    if (out.size() + n > max) over = true else out.write(chunk, 0, n)
                }
                if (over) null else out.toByteArray()
            } finally {
                inflater.end()
            }
        },
    )

    private class FileSink(file: File) : CopySink, AutoCloseable {
        private val raf = RandomAccessFile(file, "rw")
        override val position: Long get() = raf.filePointer
        override fun write(bytes: ByteArray, offset: Int, length: Int) = raf.write(bytes, offset, length)
        override fun patch(at: Long, bytes: ByteArray) {
            val end = raf.filePointer
            raf.seek(at)
            raf.write(bytes)
            raf.seek(end)
        }
        override fun close() = raf.close()
    }

    private class FileSource(file: File) : RandomSource, AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        override val size: Long = raf.length()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            raf.seek(position)
            return raf.read(buffer, offset, length).coerceAtLeast(0)
        }
        override fun close() = raf.close()
    }

    @Test
    fun androidReadsTheIphonesBackup() {
        val file = temp.newFile("common.zip")
        FileSink(file).use { sink ->
            CopyWriter.write(bundle, ExportFormat.BACKUP, sink, object : CopyPhotos {
                override fun original(photoId: String) = photoBytes.takeIf { photoId == "p1" }
                override fun dataUri(photoId: String): String? = null
            }, tools, "0.1.0")
        }
        val opened = BackupReader.open(file)
        assertTrue("open failed: $opened", opened is BackupOpen.Ok)
        (opened as BackupOpen.Ok).reader.use { reader ->
            assertEquals(BackupData.of(bundle), reader.data)
            assertArrayEquals(photoBytes, reader.photoBytes("photos/p1.jpg"))
        }
    }

    @Test
    fun theIphoneReadsAndroidsBackup() {
        val photoDir = temp.newFolder("photos")
        File(photoDir, "p1.jpg").writeBytes(photoBytes)
        val file = temp.newFile("android.zip")
        // No readable copy of a photo here (no Bitmap on the JVM), so the HTML copy has none; the backup has the file.
        file.outputStream().use { out ->
            Exporters.write(bundle, ExportFormat.BACKUP, out, { id -> File(photoDir, "$id.jpg") }, "0.1.0")
        }
        FileSource(file).use { source ->
            val opened = BackupArchive.open(source, tools)
            assertTrue("open failed: $opened", opened is ArchiveOpen.Ok)
            val archive = (opened as ArchiveOpen.Ok).archive
            assertEquals(BackupData.of(bundle), archive.data)
            assertArrayEquals(photoBytes, archive.photoBytes("photos/p1.jpg"))
        }
    }
}
