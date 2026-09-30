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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The common ZIP code of S4b-BL-81 (the iPhone's copies and imports) against `java.util.zip`, the reader and writer
 * Android's own copies go through: what [CopyWriter] writes, `ZipInputStream` reads (so the local headers are right, not
 * only the central directory), and what `ZipOutputStream` deflates, [BackupArchive] reads, with Android's answers for a
 * bad file. JVM tools stand in for the iPhone's CommonCrypto and zlib (`IosArchive.kt`).
 */
class ArchiveTest {

    private val tools = ArchiveTools(
        sha256 = {
            object : Sha256 {
                val digest = MessageDigest.getInstance("SHA-256")
                override fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
                override fun digest(): ByteArray = digest.digest()
            }
        },
        inflate = ::jvmInflate,
    )

    /** `Inflater(nowrap = true)` as the iPhone's zlib call: null past [max], a throw for a stream cut short. */
    private fun jvmInflate(compressed: ByteArray, max: Int): ByteArray? {
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IllegalArgumentException("cut short")
                if (out.size() + n > max) return null
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private class MemorySink : CopySink {
        val out = ByteArrayOutputStream()
        private var patches = mutableListOf<Pair<Long, ByteArray>>()
        override val position: Long get() = out.size().toLong()
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        override fun patch(at: Long, bytes: ByteArray) {
            patches += at to bytes.copyOf()
        }

        fun bytes(): ByteArray {
            val all = out.toByteArray()
            for ((at, b) in patches) b.copyInto(all, at.toInt())
            return all
        }
    }

    private class MemorySource(private val bytes: ByteArray) : RandomSource {
        override val size: Long get() = bytes.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return 0
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            return n
        }
    }

    private val photoBytes = ByteArray(5000) { (it * 31).toByte() }

    private val photos = object : CopyPhotos {
        override fun original(photoId: String) = if (photoId == "p1") photoBytes else null
        override fun dataUri(photoId: String) = "data:image/jpeg;base64,AAAA"
    }

    private fun write(format: ExportFormat, bundle: ExportBundle = ExportFixture.bundle()): ByteArray {
        val sink = MemorySink()
        CopyWriter.write(bundle, format, sink, photos, tools, "0.1.0")
        return sink.bytes()
    }

    /** Every entry as `ZipInputStream` reads it from the local headers, which also checks each CRC and size. */
    private fun entries(zip: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                out[entry.name] = input.readBytes()
            }
        }
        return out
    }

    private fun open(bytes: ByteArray) = BackupArchive.open(MemorySource(bytes), tools)

    @Test
    fun theCsvCopyIsAZipOfTheTablesWithABomEach() {
        val bundle = ExportFixture.bundle()
        val read = entries(write(ExportFormat.CSV, bundle))
        val tables = ExportRows.tables(bundle)
        assertEquals(tables.map { "${it.name}.csv" }, read.keys.toList())
        tables.forEach { table ->
            assertEquals(CsvWriter.BOM + CsvWriter.write(table, bundle.options), read.getValue("${table.name}.csv").decodeToString())
        }
    }

    @Test
    fun theWorkbookHoldsEveryPart() {
        val bundle = ExportFixture.bundle()
        val read = entries(write(ExportFormat.XLSX, bundle))
        assertEquals(XlsxWriter.parts(bundle).associate { it.path to it.xml }, read.mapValues { it.value.decodeToString() })
    }

    @Test
    fun theHtmlAndMarkdownCopiesAreTheSharedWritersText() {
        val bundle = ExportFixture.bundle()
        assertEquals(HtmlWriter.write(bundle) { "data:image/jpeg;base64,AAAA" }, write(ExportFormat.HTML, bundle).decodeToString())
        assertEquals(MarkdownWriter.write(bundle), write(ExportFormat.MARKDOWN, bundle).decodeToString())
    }

    @Test
    fun theEntriesCarryTheExportTimeOnTheCopysClock() {
        ZipInputStream(ByteArrayInputStream(write(ExportFormat.CSV))).use { input ->
            // 2026-09-22T10:15:30Z at +05:30, to DOS's two seconds.
            assertEquals(LocalDateTime.of(2026, 9, 22, 15, 45, 30), input.nextEntry!!.timeLocal)
        }
    }

    @Test
    fun aBackupReadsBackAsTheSameRowsAndPhoto() {
        val bundle = ExportFixture.bundle()
        val zip = write(ExportFormat.BACKUP, bundle)
        val read = entries(zip)
        assertEquals(
            listOf(BackupFormat.DATA_ENTRY, ExportFormat.HTML.fileName(bundle), "photos/p1.jpg", BackupFormat.MANIFEST_ENTRY),
            read.keys.toList(),
        )
        val opened = open(zip)
        assertTrue("open failed: $opened", opened is ArchiveOpen.Ok)
        val archive = (opened as ArchiveOpen.Ok).archive
        assertEquals(BackupData.of(bundle), archive.data)
        assertEquals(setOf("photos/p1.jpg"), archive.photoEntries)
        assertArrayEquals(photoBytes, archive.photoBytes("photos/p1.jpg"))
        val manifest = archive.manifest!!
        assertEquals("0.1.0", manifest.appVersion)
        assertEquals(BackupCounts.of(archive.data), manifest.counts)
        // Every listed hash is the SHA-256 of the entry as java.util.zip reads it.
        manifest.files.forEach { file ->
            val bytes = read.getValue(file.path)
            assertEquals(file.path, hexOf(MessageDigest.getInstance("SHA-256").digest(bytes)), file.sha256)
            assertEquals(bytes.size.toLong(), file.sizeBytes)
        }
    }

    @Test
    fun anUpdateFileSaysWhoItIsFor() {
        val bundle = ExportFixture.bundle(ExportFixture.options().copy(since = 1_790_000_000_000, sharedTo = "Asha"))
        val archive = (open(write(ExportFormat.BACKUP, bundle)) as ArchiveOpen.Ok).archive
        assertEquals("Asha", archive.manifest!!.sharedTo)
        assertEquals("2026-09-21T14:13:20Z", archive.manifest!!.sharedSince)
    }

    /** What Android writes: the same entries deflated by `ZipOutputStream`. */
    private fun deflated(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun aDeflatedBackupAsAndroidWritesItReadsTheSame() {
        val bundle = ExportFixture.bundle()
        val archive = (open(deflated(entries(write(ExportFormat.BACKUP, bundle)))) as ArchiveOpen.Ok).archive
        assertEquals(BackupData.of(bundle), archive.data)
        assertArrayEquals(photoBytes, archive.photoBytes("photos/p1.jpg"))
    }

    @Test
    fun aBareDataJsonOpensWithoutPhotos() {
        val json = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(ExportFixture.bundle()))
        val archive = (open(("﻿" + json).encodeToByteArray()) as ArchiveOpen.Ok).archive
        assertNull(archive.manifest)
        assertEquals(BackupData.of(ExportFixture.bundle()), archive.data)
        assertNull(archive.photoBytes("photos/p1.jpg"))
    }

    private fun problemOf(bytes: ByteArray) = (open(bytes) as ArchiveOpen.Failed).problem

    @Test
    fun aBadFileGetsAndroidsAnswer() {
        val good = entries(write(ExportFormat.BACKUP))
        assertEquals(BackupProblem.NOT_A_BACKUP, problemOf("%PDF-1.7".encodeToByteArray()))
        assertEquals(BackupProblem.NOT_A_BACKUP, problemOf(ByteArray(0)))
        assertEquals(BackupProblem.SUSPICIOUS_PATH, problemOf(deflated(good + ("../evil" to ByteArray(1)))))
        assertEquals(BackupProblem.NOT_A_BACKUP, problemOf(deflated(good - BackupFormat.MANIFEST_ENTRY)))
        assertEquals(BackupProblem.UNSUPPORTED_VERSION, problemOf("""{"format":"doorprints-backup/9"}""".encodeToByteArray()))
        assertEquals(BackupProblem.NOT_A_BACKUP, problemOf("""{"format":"house-hunt-export/1"}""".encodeToByteArray()))
        // data.json edited after the manifest was written.
        val edited = good.getValue(BackupFormat.DATA_ENTRY).decodeToString().replace("Sunrise", "Sunset").encodeToByteArray()
        assertEquals(BackupProblem.CHECKSUM_MISMATCH, problemOf(deflated(good + (BackupFormat.DATA_ENTRY to edited))))
        val tooMany = (0..BackupFormat.MAX_ENTRIES).associate { "x$it" to ByteArray(0) }
        assertEquals(BackupProblem.TOO_MANY_ENTRIES, problemOf(deflated(good + tooMany)))
    }

    @Test
    fun aDamagedStoredEntryIsNotABackupAndABadPhotoIsSkipped() {
        val zip = write(ExportFormat.BACKUP)
        // A byte inside data.json (the first entry, right after its 30-byte header and name): the CRC catches it.
        val damaged = zip.copyOf().also { it[30 + BackupFormat.DATA_ENTRY.length + 5] = 'X'.code.toByte() }
        assertEquals(BackupProblem.NOT_A_BACKUP, problemOf(damaged))
        // The photo changed but still has a valid CRC: its SHA-256 in the manifest refuses it; the rest imports.
        val entries = entries(zip)
        val swapped = deflated(entries + ("photos/p1.jpg" to ByteArray(5000) { (it * 17 + 3).toByte() }))
        val archive = (open(swapped) as ArchiveOpen.Ok).archive
        assertNull(archive.photoBytes("photos/p1.jpg"))
        assertNull(archive.photoBytes("photos/../data.json"))
    }

    @Test
    fun anEntryThatInflatesPastItsDeclaredSizeIsCutOff() {
        val zeros = ByteArray(2 * 1024 * 1024)
        val zip = deflated(mapOf("big.bin" to zeros))
        val reader = (ZipReader.open(MemorySource(zip), 10) as ZipOpen.Ok).reader
        val entry = reader.entry("big.bin")!!
        assertEquals(zeros.size.toLong(), entry.size)
        assertNull(reader.readAtMost(entry, 1024, tools))
        // The central directory lies: it says 100 bytes. The declared filter passes; the counted output does not.
        val lying = zip.copyOf()
        val dir = (lying.size - 22 downTo 0).first { i ->
            lying[i] == 0x50.toByte() && lying[i + 1] == 0x4b.toByte() && lying[i + 2] == 1.toByte() && lying[i + 3] == 2.toByte()
        }
        byteArrayOf(100, 0, 0, 0).copyInto(lying, dir + 24)
        val liar = (ZipReader.open(MemorySource(lying), 10) as ZipOpen.Ok).reader
        val claimed = liar.entry("big.bin")!!
        assertEquals(100L, claimed.size)
        assertNull(liar.readAtMost(claimed, 64 * 1024, tools))
    }

    @Test
    fun utf8OutNeverSplitsASurrogatePair() {
        val chunks = mutableListOf<ByteArray>()
        val out = Utf8Out { chunks += it }
        val text = "a".repeat(16 * 1024 - 1) + "🏠" + "घर".repeat(5000)
        text.forEach { out.append(it) }
        out.flush()
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.decodeToString().encodeToByteArray().contentEquals(it) })
        assertEquals(text, chunks.fold(ByteArray(0)) { acc, b -> acc + b }.decodeToString())
    }

    @Test
    fun crc32IsZipsCrc() {
        val bytes = "Doorprints, घर".encodeToByteArray()
        val jvm = java.util.zip.CRC32().apply { update(bytes) }.value.toInt()
        assertEquals(jvm, Crc32.of(bytes))
        assertEquals(jvm, Crc32.update(Crc32.update(0, bytes, 0, 4), bytes, 4, bytes.size - 4))
    }

    @Test
    fun pdfIsNotACommonFormat() {
        assertEquals(ExportFormat.entries - ExportFormat.PDF, CopyWriter.formats)
    }
}
