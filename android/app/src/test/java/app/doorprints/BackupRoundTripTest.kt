package app.doorprints

import app.doorprints.data.HouseEntity
import app.doorprints.data.VisitEntity
import app.doorprints.data.toExport
import app.doorprints.export.BackupOpen
import app.doorprints.export.BackupReader
import app.doorprints.export.Exporters
import app.doorprints.shared.export.BackupFormat
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.ExportBundle
import app.doorprints.shared.export.ExportFormat
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.export.ImportPlan
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.VisitSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The exact round trip of docs/11 section 5.2, end to end on the JVM: write a real backup ZIP with [Exporters],
 * read it back with [BackupReader], and check the plan an import would make. No emulator is needed because the
 * backup path touches only `java.util.zip`, `MessageDigest` and the pure writers — a bundle with no photos never
 * reaches `Bitmap`.
 */
class BackupRoundTripTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val options = ExportOptions(
        language = "en",
        utcOffsetMinutes = 330,
        exportedAtMillis = 1_790_072_130_000,
    )

    private val house = HouseEntity(
        id = "h1", label = "Sunrise \"B\" | 2BHK", address = "12, 5th Cross", street = "5th Cross",
        locality = "Indiranagar", lat = 12.978321, lon = 77.640812, status = HouseStatus.SHORTLISTED,
        price = 32_000, priceType = "RENT", bedrooms = 2, rating = 4, contactName = "Owner",
        contactPhone = "+91 98450 00000", listingUrl = null, notes = "Water 24x7\n=SUM(A1)",
        checklist = mapOf("water" to 5, "noise" to 2), createdAt = 1_790_000_000_000,
        updatedAt = 1_790_072_130_120, deleted = false, dirty = false,
    )

    private val visit = VisitEntity(
        id = "v1", houseId = "h1", lat = 12.978, lon = 77.64, street = "5th Cross",
        arrivedAt = 1_790_003_000_000, leftAt = 1_790_003_600_000, source = VisitSource.MANUAL,
        updatedAt = 1_790_003_600_000, deleted = false, dirty = false,
    )

    private fun writeBackup(): File {
        val bundle = ExportBundle.build(
            options, listOf(house.toExport()), listOf(visit.toExport()), emptyList(),
        )
        val file = temp.newFile(ExportFormat.BACKUP.fileName(bundle))
        file.outputStream().use { out ->
            Exporters.write(bundle, ExportFormat.BACKUP, out, { id -> File("/does/not/exist/$id.jpg") }, "0.1.0")
        }
        return file
    }

    @Test
    fun aBackupReadsBackAsTheSameRows() {
        val opened = BackupReader.open(writeBackup())
        assertTrue("open failed: $opened", opened is BackupOpen.Ok)
        (opened as BackupOpen.Ok).reader.use { reader ->
            assertEquals(BackupFormat.ID, reader.data.format)
            assertEquals(listOf(house.toExport()), reader.data.houses)
            assertEquals(listOf(visit.toExport()), reader.data.visits)
            val manifest = reader.manifest
            assertNotNull(manifest)
            assertEquals(1, manifest!!.counts.houses)
            assertEquals(1, manifest.counts.visits)
            assertEquals(0, manifest.counts.photos)
            assertEquals("2026-09-22T10:15:30Z", manifest.createdAt)
            // The manifest must hash data.json and the HTML copy that travels with it.
            assertEquals(
                setOf(BackupFormat.DATA_ENTRY, "Doorprints-2026-09-22.html"),
                manifest.files.map { it.path }.toSet(),
            )
            assertTrue(manifest.files.all { it.sha256.length == 64 && it.sizeBytes > 0 })
        }
    }

    @Test
    fun importingIntoAnEmptyPhoneAddsEverythingOnce() {
        (BackupReader.open(writeBackup()) as BackupOpen.Ok).reader.use { reader ->
            val fresh = ImportPlan.preview(
                reader.data, emptyMap(), emptyMap(), emptySet(), reader.photoEntries, ImportMode.MERGE,
            )
            assertEquals(1, fresh.newHouses)
            assertEquals(1, fresh.newVisits)
            assertEquals(0, fresh.overwrites)

            val already = ImportPlan.preview(
                reader.data,
                mapOf("h1" to house.updatedAt),
                mapOf("v1" to visit.updatedAt),
                emptySet(),
                reader.photoEntries,
                ImportMode.MERGE,
            )
            assertTrue("re-importing the same file must change nothing", already.isEmpty)
        }
    }

    /**
     * Hunt mode records a dwell at a place that is not a house yet with `houseId = null`. Such a visit has to be in
     * the backup (last, docs/schemas/README.md section 5), be counted in the manifest, and come back on a restore in
     * both modes — before this, every backup silently left it out.
     */
    @Test
    fun aVisitWithNoHouseSurvivesTheBackupAndIsRestored() {
        val unlinked = visit.copy(
            id = "v0", houseId = null, source = VisitSource.AUTO, arrivedAt = 1_790_001_000_000, leftAt = null,
            updatedAt = 1_790_001_000_000,
        )
        val bundle = ExportBundle.build(
            options, listOf(house.toExport()), listOf(visit.toExport(), unlinked.toExport()), emptyList(),
        )
        val file = temp.newFile("with-unlinked.zip")
        file.outputStream().use { out ->
            Exporters.write(bundle, ExportFormat.BACKUP, out, { id -> File("/does/not/exist/$id.jpg") }, "0.1.0")
        }
        (BackupReader.open(file) as BackupOpen.Ok).reader.use { reader ->
            assertEquals(listOf("v1", "v0"), reader.data.visits.map { it.id })
            assertEquals(unlinked.toExport(), reader.data.visits.last())
            assertEquals(2, reader.manifest!!.counts.visits)

            val preview = ImportPlan.preview(
                reader.data, emptyMap(), emptyMap(), emptySet(), reader.photoEntries, ImportMode.MERGE,
            )
            assertEquals(2, preview.newVisits)
            val merged = ImportPlan.plan(
                reader.data, emptyMap(), emptyMap(), emptySet(), reader.photoEntries, ImportMode.MERGE,
                newId = { error("MERGE keeps the file's ids") },
            )
            assertEquals(unlinked.toExport(), merged.visits.single { it.id == "v0" })

            var next = 0
            val copied = ImportPlan.plan(
                reader.data, emptyMap(), emptyMap(), emptySet(), reader.photoEntries, ImportMode.COPY,
                newId = { "n${next++}" },
            )
            assertEquals(2, copied.visits.size)
            assertNull(copied.visits.single { it.source == unlinked.source.name }.houseId)
        }
    }

    @Test
    fun aTamperedDataJsonIsRefused() {
        val original = writeBackup()
        val tampered = temp.newFile("tampered.zip")
        rewriteEntry(original, tampered, BackupFormat.DATA_ENTRY) { bytes ->
            bytes.toString(Charsets.UTF_8).replace("Sunrise", "Sunsets").toByteArray(Charsets.UTF_8)
        }
        val opened = BackupReader.open(tampered)
        assertTrue("expected a checksum failure, got $opened", opened is BackupOpen.Failed)
    }

    @Test
    fun anArchiveThatIsNotABackupIsRefused() {
        val notOurs = temp.newFile("random.zip")
        ZipOutputStream(notOurs.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("hello.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        assertTrue(BackupReader.open(notOurs) is BackupOpen.Failed)
        val notAZip = temp.newFile("random.bin")
        notAZip.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertTrue(BackupReader.open(notAZip) is BackupOpen.Failed)
    }

    @Test
    fun anEntryWithAPathEscapeIsRefused() {
        val nasty = temp.newFile("nasty.zip")
        ZipOutputStream(nasty.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../../etc/passwd"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        assertTrue(BackupReader.open(nasty) is BackupOpen.Failed)
    }

    /**
     * The ZIP-bomb defence has to hold against a *lying* archive, which is the only kind that matters.
     *
     * `ZipEntry.size` comes from the central directory — a number the file's author wrote — and
     * `java.util.zip.ZipFile` never checks it while it inflates. This writes an entry whose real contents are
     * larger than `MAX_DATA_JSON_BYTES`, then rewrites the central directory to claim the entry is 100 bytes.
     * Every arithmetic check on the declared numbers passes; only counting what actually comes out of the
     * inflater catches it. Before that fix this test did not fail with `TOO_LARGE` — it OOMed.
     */
    @Test
    fun anEntryThatUnderstatesItsSizeIsRefusedRatherThanInflated() {
        val lying = temp.newFile("lying.zip")
        ZipOutputStream(lying.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.DATA_ENTRY))
            val block = ByteArray(1024 * 1024) { 'a'.code.toByte() }
            // 18 MiB of one repeated byte: a few kilobytes on disk, well past the 16 MiB data.json limit.
            repeat(18) { zip.write(block) }
            zip.closeEntry()
        }
        understateCentralDirectorySize(lying, BackupFormat.DATA_ENTRY, 100)

        val zipEntrySize = java.util.zip.ZipFile(lying).use { it.getEntry(BackupFormat.DATA_ENTRY)!!.size }
        assertEquals("the archive must actually be lying for this test to mean anything", 100L, zipEntrySize)

        val opened = BackupReader.open(lying)
        assertTrue("expected a refusal, got $opened", opened is BackupOpen.Failed)
        assertEquals(BackupProblem.TOO_LARGE, (opened as BackupOpen.Failed).problem)
    }

    /**
     * Rewrites one entry's "uncompressed size" field in the central directory. The directory is located from the
     * end-of-central-directory record rather than by scanning for the signature, so compressed data that happens
     * to contain the same four bytes cannot confuse it.
     */
    private fun understateCentralDirectorySize(file: File, entryName: String, declared: Int) {
        val bytes = file.readBytes()
        var eocd = -1
        for (i in bytes.size - 22 downTo 0) {
            if (u32(bytes, i) == 0x06054B50L) {
                eocd = i
                break
            }
        }
        assertTrue("no end-of-central-directory record", eocd >= 0)
        var at = u32(bytes, eocd + 16).toInt()
        val count = u16(bytes, eocd + 10)
        repeat(count) {
            assertEquals("not a central directory header", 0x02014B50L, u32(bytes, at))
            val nameLen = u16(bytes, at + 28)
            val extraLen = u16(bytes, at + 30)
            val commentLen = u16(bytes, at + 32)
            if (String(bytes, at + 46, nameLen, Charsets.UTF_8) == entryName) {
                putU32(bytes, at + 24, declared)
            }
            at += 46 + nameLen + extraLen + commentLen
        }
        file.writeBytes(bytes)
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long =
        (u16(bytes, at).toLong()) or (u16(bytes, at + 2).toLong() shl 16)

    private fun putU32(bytes: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) bytes[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    /** Copies a ZIP, replacing one entry's bytes; used to prove the checksum check bites. */
    private fun rewriteEntry(source: File, target: File, path: String, change: (ByteArray) -> ByteArray) {
        java.util.zip.ZipFile(source).use { zip ->
            ZipOutputStream(target.outputStream()).use { out ->
                for (entry in zip.entries().toList()) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(if (entry.name == path) change(bytes) else bytes)
                    out.closeEntry()
                }
            }
        }
    }
}
