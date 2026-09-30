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

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iPhone's side of the common ZIP code (S4b-BL-81, `IosArchive.kt`) on the simulator: CommonCrypto's SHA-256, the
 * system zlib's raw DEFLATE with its limit, the POSIX sink's patches and the source's reads, and a whole backup written
 * to a file and read back. The same code against `java.util.zip` is `ArchiveTest` (host).
 */
class IosArchiveTest {
    private val path = NSTemporaryDirectory().trimEnd('/') + "/archive-test-" + NSUUID().UUIDString + ".zip"

    @OptIn(ExperimentalForeignApi::class)
    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    @Test
    fun sha256IsCommonCryptos() {
        val digest = iosArchiveTools.sha256().apply {
            update("ab".encodeToByteArray())
            update("xcx".encodeToByteArray(), 1, 1)
        }.digest()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hexOf(digest))
    }

    @Test
    fun zlibInflatesRawDeflateUpToTheLimit() {
        // "Doorprints " 50 times, raw DEFLATE (Python's zlib, wbits -15).
        val packed = byteArrayOf(115, -55, -49, 47, 42, 40, -54, -52, 43, 41, 86, 112, 25, 101, -114, 50, -79, 51, 1)
        assertEquals("Doorprints ".repeat(50), iosArchiveTools.inflate(packed, 550)!!.decodeToString())
        assertNull(iosArchiveTools.inflate(packed, 549))
        assertFailsWith<IllegalArgumentException> { iosArchiveTools.inflate(packed.copyOf(8), 550) }
        assertFailsWith<IllegalArgumentException> { iosArchiveTools.inflate(byteArrayOf(-1, -1, -1), 550) }
    }

    @Test
    fun aBackupWrittenToAFileReadsBack() {
        val photo = ByteArray(70_000) { (it % 251).toByte() }
        val bundle = ExportBundle.build(
            ExportOptions(utcOffsetMinutes = 330, exportedAtMillis = 1_790_072_130_000),
            listOf(ExportHouse(id = "h1", label = "Flat 4", lat = 12.9, lon = 77.6, createdAt = 1, updatedAt = 2)),
            emptyList(),
            listOf(ExportPhoto(id = "p1", houseId = "h1", fileName = "p1.jpg", createdAt = 3)),
        )
        PosixFileSink(path).use { sink ->
            CopyWriter.write(bundle, ExportFormat.BACKUP, sink, object : CopyPhotos {
                override fun original(photoId: String) = photo
                override fun dataUri(photoId: String): String? = null
            }, iosArchiveTools, "0.1.0")
        }
        PosixFileSource(path).use { source ->
            val archive = (BackupArchive.open(source, iosArchiveTools) as ArchiveOpen.Ok).archive
            assertEquals(BackupData.of(bundle), archive.data)
            assertContentEquals(photo, archive.photoBytes("photos/p1.jpg"))
            assertTrue(archive.manifest!!.files.all { it.sha256.length == 64 })
        }
    }

    @Test
    fun aFileThatIsNotThereFailsToOpen() {
        assertFailsWith<PosixFileException> { PosixFileSource("$path.missing") }
    }
}
