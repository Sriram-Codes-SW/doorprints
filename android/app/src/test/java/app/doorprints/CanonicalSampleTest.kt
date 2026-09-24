package app.doorprints

import app.doorprints.export.BackupOpen
import app.doorprints.export.BackupReader
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.BackupFormat
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.BackupValidation
import app.doorprints.shared.export.ExportBundle
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.export.ImportPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigDecimal

/**
 * Android's half of the `doorprints-backup/1` contract, checked against the one file all three implementations
 * answer to: `docs/schemas/backup-sample.json` (docs/schemas/README.md section 8.1, ticket S4-00/a).
 *
 * This is a **parsed-JSON** comparison, not a byte golden, and it has to be: the sample is written the way a browser
 * writes it, so house 2's "no location yet" is `"lat":0`, while `kotlinx.serialization` writes the same number as
 * `0.0`. Everything else must match exactly — the rows, their order, the order of the keys in every object, the
 * absence of nulls and every value — with numbers compared as numbers.
 *
 * The rows go in **shuffled** (houses, visits, photos and each checklist reversed), so the test checks the ordering
 * rules of section 5 rather than the order the file happened to be read in. The sample interleaves house 3's visit
 * and photo with house 1's on purpose, so a writer that sorted visits and photos globally would fail here.
 *
 * It lives in `:app` rather than `:shared` `commonTest` because it reads a file from the repository, and common code
 * has no file-system API (the iOS compile would reject it). The sample is outside `android/`, so a change to it alone
 * does not trigger the Android workflow's path filter — see android/shared/README.md section 9.
 */
class CanonicalSampleTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sampleText: String by lazy { locateSample().readText(Charsets.UTF_8).trim() }

    @Test
    fun theKotlinReaderAcceptsTheCanonicalSample() {
        val data = BackupFormat.json.decodeFromString(BackupData.serializer(), sampleText)
        assertNull(BackupValidation.checkData(data))
        assertEquals(listOf(3, 3, 2), listOf(data.houses.size, data.visits.size, data.photos.size))
        // The unknown checklist key from a newer app survives the read (NFR-025).
        assertEquals(2, data.houses.first().checklist["newItemFromNewerApp"])
    }

    @Test
    fun theKotlinWriterProducesTheCanonicalDocument() {
        val sample = BackupFormat.json.decodeFromString(BackupData.serializer(), sampleText)
        val bundle = ExportBundle.build(
            ExportOptions(exportedAtMillis = sample.exportedAt),
            houses = sample.houses.reversed().map { house ->
                house.copy(checklist = house.checklist.entries.reversed().associate { it.key to it.value })
            },
            visits = sample.visits.reversed(),
            photos = sample.photos.reversed(),
        )
        // The fixture has to tell the two ordering rules apart, or this test pins nothing.
        assertNotEquals(sample.visits.map { it.id }, bundle.visits.map { it.id })
        assertNotEquals(sample.photos.map { it.id }, bundle.photos.map { it.id })

        val written = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(bundle))
        assertFalse("writers never emit null (section 4.1)", written.contains(":null"))
        assertEquals(canonical(Json.parseToJsonElement(sampleText)), canonical(Json.parseToJsonElement(written)))
    }

    /**
     * The server's `GET /api/export` downloads exactly this: a bare `data.json`, no ZIP, no manifest, no photo
     * bytes. docs/schemas/README.md section 2 says an importer must take it, so the phone can restore a copy made
     * from the server. Its photo rows are reported as missing from the file and never written.
     */
    @Test
    fun aBareDataJsonFromTheServerOpensWithoutAManifest() {
        val file = temp.newFile("Doorprints-backup-2026-09-22.json")
        file.writeText(sampleText, Charsets.UTF_8)
        val opened = BackupReader.open(file)
        assertTrue("expected the sample to open, got $opened", opened is BackupOpen.Ok)
        (opened as BackupOpen.Ok).reader.use { reader ->
            assertNull(reader.manifest)
            assertTrue(reader.photoEntries.isEmpty())
            assertEquals(3, reader.data.houses.size)
            assertNull(reader.photoBytes("photos/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.jpg"))
            val preview = ImportPlan.preview(
                reader.data, emptyMap(), emptyMap(), emptySet(), reader.photoEntries, ImportMode.MERGE,
            )
            assertEquals(3, preview.newHouses)
            assertEquals(3, preview.newVisits)
            assertEquals(0, preview.newPhotos)
            assertEquals(2, preview.photosMissingFromFile)
        }
    }

    /** A bare file is refused with the reason that sends the user to the right fix. */
    @Test
    fun aBareFileThatIsNotOursSaysWhy() {
        fun problemOf(text: String): BackupProblem? {
            val file = temp.newFile()
            file.writeText(text, Charsets.UTF_8)
            return (BackupReader.open(file) as? BackupOpen.Failed)?.problem
        }
        assertEquals(BackupProblem.NOT_A_BACKUP, problemOf("[]"))
        assertEquals(BackupProblem.NOT_A_BACKUP, problemOf("not json at all"))
        assertEquals(BackupProblem.NOT_A_BACKUP, problemOf("{\"houses\":[]}"))
        assertEquals(BackupProblem.NOT_A_BACKUP, problemOf("{\"format\":\"house-hunt-export/1\",\"houses\":[]}"))
        assertEquals(BackupProblem.UNSUPPORTED_VERSION, problemOf("{\"format\":\"doorprints-backup/2\"}"))
        // Ours, but a house without lat/lon/status/...: refused whole (section 4.4), not imported at 0, 0.
        assertEquals(
            BackupProblem.BROKEN_DATA,
            problemOf("{\"format\":\"doorprints-backup/1\",\"exportedAt\":1,\"houses\":[{\"id\":\"h1\"}]}"),
        )
    }

    /**
     * The document as one string that keeps what the contract pins — object key order, array order, strings,
     * booleans — and prints every number in one form, so `0` and `0.0` compare equal and `13.006` stays `13.006`.
     */
    private fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.joinToString(",", "{", "}") { (key, value) ->
            JsonPrimitive(key).toString() + ":" + canonical(value)
        }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            element.isString -> element.toString()
            element.content == "true" || element.content == "false" -> element.content
            else -> BigDecimal(element.content).stripTrailingZeros().toPlainString()
        }
    }

    private fun locateSample(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, SAMPLE_PATH)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("$SAMPLE_PATH not found above ${File("").absolutePath}")
    }

    private companion object {
        const val SAMPLE_PATH = "docs/schemas/backup-sample.json"
    }
}
