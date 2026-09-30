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

import app.doorprints.export.BackupOpen
import app.doorprints.export.BackupReader
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.BackupFormat
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.BackupValidation
import app.doorprints.shared.export.ExportBundle
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.ExportRows
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
 * Android's half of the `doorprints-backup/1` and `/2` contract (the sample is a `/2` document since slice 1b), checked against the one file all three implementations
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
        assertEquals(listOf(3, 3, 2, 2), listOf(data.houses.size, data.visits.size, data.photos.size, data.brokerRows.size))
        // Slice 1b: houses 1 and 3 name a broker of the file, and the sample is a `/2` document.
        assertEquals("doorprints-backup/2", data.format)
        assertEquals(listOf("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", null, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), data.houses.map { it.brokerId })
        // Slice 1c: house 1 has two rooms, in the order shown, with every key the format has on the first.
        assertEquals(listOf(listOf("Master bedroom", "Kitchen"), null, null), data.houses.map { h -> h.rooms?.map { it.name } })
        assertEquals(listOf(396, 366, 4, 0), data.houses.first().rooms!!.first().let { listOf(it.lengthCm, it.widthCm, it.condition, it.sort) })
        // Slice 2: three criteria (an archived built-in, a custom one with its label, a must-have) and the rating share.
        assertEquals(listOf("noise", "c_1a2b3c4d", "water"), data.criterionRows.map { it.key })
        assertEquals(listOf(true, null, null), data.criterionRows.map { it.archived })
        assertEquals("Pets allowed", data.criterionRows[1].label)
        assertEquals(listOf("score.ratingShare" to "0.4"), data.preferenceRows.map { it.key to it.value })
        // Under the sample's own scoring, the cross-check both apps share: house 1 scores 4.1 (water High, the archived
        // noise and the newer app's key left out, 40 % rating), house 2 nothing, house 3 its one star (1.0).
        val bundle = ExportBundle.build(
            ExportOptions(), data.houses, data.visits, data.photos, data.brokerRows, data.criterionRows, data.preferenceRows,
        )
        assertEquals(listOf("4.1", null, "1.0"), data.houses.map { h -> bundle.overallOf(h)?.let { ExportRows.fixed(it, 1) } })
        // Slice 3a: three questions (two seeded defaults and an archived one of the person's own) and house 1's two
        // answers, an answered one from the bank and an open one asked ad hoc, in the order shown.
        assertEquals(listOf("qd_deposit", "qd_maintenance", "q_9f8e7d6c"), data.questionRows.map { it.id })
        assertEquals(listOf(null, null, true), data.questionRows.map { it.archived })
        assertEquals(listOf(listOf("ANSWERED", "OPEN"), null, null), data.houses.map { h -> h.answers?.map { it.status } })
        assertEquals(listOf("qd_maintenance", null), data.houses.first().answers!!.map { it.questionId })
        // Slice 3b-1: two viewings of house 1, a DONE one with whom and its visit, a PLANNED second viewing with notes
        // and the Hunt reminder, in the file's order (updatedAt, then id).
        assertEquals(listOf("v_3c4d5e6f", "v_a1b2c3d4"), data.viewingRows.map { it.id })
        assertEquals(listOf("DONE", "PLANNED"), data.viewingRows.map { it.status })
        assertEquals(listOf(null, true), data.viewingRows.map { it.huntReminder })
        assertEquals(listOf("Ravi Kumar", null), data.viewingRows.map { it.withWhom })
        // Slice 4a: two areas (one with the wake-up off), two places and two area notes (one on an area, one on a street).
        assertEquals(listOf("a_1f2e3d4c", "a_5b6c7d8e"), data.areaRows.map { it.id })
        assertEquals(listOf(null, false), data.areaRows.map { it.enabled })
        assertEquals(listOf(500, 1200), data.areaRows.map { it.radiusM })
        assertEquals(listOf("Office", "Amma's home"), data.placeRows.map { it.name })
        assertEquals(listOf("a_1f2e3d4c" to null, null to "MG Road"), data.areaNoteRows.map { it.areaId to it.street })
        // House 1 (13.006, 80.2574, GPS, MG Road) is 74 m from the Adyar area's centre, so both notes reach it, newest
        // first; house 3 (Beach Road) gets none; house 2 has no point, no street and so none either.
        val slice4a = ExportBundle.build(
            ExportOptions(), data.houses, data.visits, data.photos,
            areas = data.areaRows, places = data.placeRows, areaNotes = data.areaNoteRows,
        )
        assertEquals(listOf(listOf("n_55667788", "n_11223344"), emptyList(), emptyList()), data.houses.map { h -> slice4a.areaNotesOf(h).map { it.id } })
        assertEquals(listOf(listOf("Office" to "8.6", "Amma's home" to "288.8"), emptyList(), listOf("Office" to "8.6", "Amma's home" to "293.0")),
            data.houses.map { h -> ExportRows.distanceRows(h, slice4a).map { it[0] to it[1] } })
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
            brokers = sample.brokerRows.reversed(),
            criteria = sample.criterionRows.reversed(),
            preferences = sample.preferenceRows.reversed(),
            questions = sample.questionRows.reversed(),
            viewings = sample.viewingRows.reversed(),
            areas = sample.areaRows.reversed(),
            places = sample.placeRows.reversed(),
            areaNotes = sample.areaNoteRows.reversed(),
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
            assertEquals(2, preview.newBrokers)
            assertEquals(3, preview.newCriteria)
            assertEquals(1, preview.newPreferences)
            assertEquals(3, preview.newQuestions)
            assertEquals(2, preview.newViewings)
            assertEquals(listOf(2, 2, 2), listOf(preview.newAreas, preview.newPlaces, preview.newAreaNotes))
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
        // S4b-BL-72: `/2` is read (BackupFormat.MAX_VERSION); the first number past it is a newer app's file.
        assertEquals(
            BackupProblem.UNSUPPORTED_VERSION,
            problemOf("{\"format\":\"doorprints-backup/${BackupFormat.MAX_VERSION + 1}\"}"),
        )
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
