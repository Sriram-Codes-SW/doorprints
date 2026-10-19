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
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The inflater Android really imports with (`jvmInflateRaw` behind [jvmArchiveTools]), not the test double of
 * [ArchiveTest]: a bomb costs the limit and no more, whatever the ZIP says about itself (TC-S-17, docs/02 T-T8).
 */
class JvmArchiveTest {

    private fun rawDeflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    @Test
    fun inflatingStopsAtTheLimitWhateverTheDataWouldCome() {
        val zeros = ByteArray(64 * 1024 * 1024)
        val packed = rawDeflate(zeros)
        assertTrue(packed.size < 100 * 1024, "the bomb is small: ${packed.size}")
        assertNull(jvmInflateRaw(packed, 1024 * 1024))
        assertNull(jvmInflateRaw(packed, zeros.size - 1))
        assertEquals(zeros.size, jvmInflateRaw(packed, zeros.size)?.size)
    }

    @Test
    fun brokenOrCutShortDataIsRefusedNotGuessedAt() {
        val packed = rawDeflate(ByteArray(100_000) { (it % 251).toByte() })
        assertContentEquals(ByteArray(100_000) { (it % 251).toByte() }, jvmInflateRaw(packed, 200_000))
        assertFailsWith<IllegalArgumentException> { jvmInflateRaw(packed.copyOf(10), 200_000) }
        assertFailsWith<IllegalArgumentException> { jvmInflateRaw(ByteArray(64) { 0xFF.toByte() }, 200_000) }
    }

    @Test
    fun aLyingBackupIsRefusedThroughTheRealTools() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.DATA_ENTRY))
            zip.write(ByteArray(20 * 1024 * 1024))
            zip.closeEntry()
        }
        val honest = out.toByteArray()
        val lying = honest.copyOf()
        val dir = (lying.size - 22 downTo 0).first { lying[it] == 0x50.toByte() && lying[it + 2] == 1.toByte() && lying[it + 3] == 2.toByte() }
        byteArrayOf(100, 0, 0, 0).copyInto(lying, dir + 24)
        for (zip in listOf(honest, lying)) {
            val source = object : RandomSource {
                override val size = zip.size.toLong()
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= zip.size) return 0
                    val n = minOf(length.toLong(), zip.size - position).toInt()
                    zip.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                    return n
                }
            }
            assertEquals(BackupProblem.TOO_LARGE, (BackupArchive.open(source, jvmArchiveTools) as ArchiveOpen.Failed).problem)
        }
    }
}
