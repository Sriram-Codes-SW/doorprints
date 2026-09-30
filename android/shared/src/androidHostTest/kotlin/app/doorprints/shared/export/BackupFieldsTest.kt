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

import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Completeness of the backup format's three record types against `docs/schemas/backup-sample.json` (readiness review
 * 2026-09-29, docs/14 §8 finding 4; TC-U-93): every key the sample's first house, visit and photo carry is a field of
 * `ExportHouse`, `ExportVisit` and `ExportPhoto`, in the sample's order, and the other way round. A new house field
 * (Sprint 4c) therefore lands in the sample and the model together, and the web (`backup-fields.spec.ts`) and the
 * server (`BackupParityTest`) check the same sample. Not in commonTest: it reads a repository file.
 */
class BackupFieldsTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val sample = Json.parseToJsonElement(repoFile("docs/schemas/backup-sample.json").readText()).jsonObject

    private fun keysOf(list: String) = sample.getValue(list).jsonArray.first().jsonObject.keys.toList()

    @Test
    fun theSampleHouseHasExactlyTheModelsFields() =
        assertEquals(ExportHouse.serializer().descriptor.elementNames.toList(), keysOf("houses"))

    @Test
    fun theSampleVisitHasExactlyTheModelsFields() =
        assertEquals(ExportVisit.serializer().descriptor.elementNames.toList(), keysOf("visits"))

    @Test
    fun theSamplePhotoHasExactlyTheModelsFields() =
        assertEquals(ExportPhoto.serializer().descriptor.elementNames.toList(), keysOf("photos"))

    /** The broker with the most keys (slice 1b): Ravi's row carries every optional one, Meena's only some. */
    @Test
    fun theSampleBrokerWithEveryKeyHasExactlyTheModelsFields() =
        assertEquals(
            ExportBroker.serializer().descriptor.elementNames.toList(),
            sample.getValue("brokers").jsonArray.map { it.jsonObject.keys.toList() }.maxBy { it.size },
        )

    /** The room with the most keys (slice 1c): the first house's master bedroom carries every one. */
    @Test
    fun theSampleRoomWithEveryKeyHasExactlyTheModelsFields() =
        assertEquals(
            app.doorprints.shared.model.HouseRoom.serializer().descriptor.elementNames.toList(),
            sample.getValue("houses").jsonArray.flatMap { h -> h.jsonObject["rooms"]?.jsonArray.orEmpty() }
                .map { it.jsonObject.keys.toList() }.maxBy { it.size },
        )

    /**
     * The criteria (slice 2): no one row has every key (`label` is custom-only, `archived` only when true), so every
     * row's keys are in the model's order and together they are all of the model's fields.
     */
    @Test
    fun theSampleCriteriaTogetherHaveExactlyTheModelsFieldsInItsOrder() {
        val model = ExportCriterion.serializer().descriptor.elementNames.toList()
        val rows = sample.getValue("criteria").jsonArray.map { it.jsonObject.keys.toList() }
        for (row in rows) assertEquals(model.filter { it in row }, row)
        assertEquals(model, model.filter { key -> rows.any { key in it } })
    }

    /**
     * The questions (slice 3a): `archived` is only on one row, so every row's keys are in the model's order and together
     * they are all of the model's fields.
     */
    @Test
    fun theSampleQuestionsTogetherHaveExactlyTheModelsFieldsInItsOrder() {
        val model = ExportQuestion.serializer().descriptor.elementNames.toList()
        val rows = sample.getValue("questions").jsonArray.map { it.jsonObject.keys.toList() }
        for (row in rows) assertEquals(model.filter { it in row }, row)
        assertEquals(model, model.filter { key -> rows.any { key in it } })
    }

    /** The answers (slice 3a): house 1's two answers together carry every key of `HouseAnswer`, each row in its order. */
    @Test
    fun theSampleAnswersTogetherHaveExactlyTheModelsFieldsInItsOrder() {
        val model = app.doorprints.shared.model.HouseAnswer.serializer().descriptor.elementNames.toList()
        val rows = sample.getValue("houses").jsonArray.flatMap { h -> h.jsonObject["answers"]?.jsonArray.orEmpty() }
            .map { it.jsonObject.keys.toList() }
        assertEquals(2, rows.size)
        for (row in rows) assertEquals(model.filter { it in row }, row)
        assertEquals(model, model.filter { key -> rows.any { key in it } })
    }

    /**
     * The viewings (slice 3b-1): the optional keys (`huntReminder`, `withWhom`, `notes`, `visitId`) are spread over the
     * two rows, so every row's keys are in the model's order and together (their union) they are all of the model's fields.
     */
    @Test
    fun theSampleViewingsTogetherHaveExactlyTheModelsFieldsInItsOrder() {
        val model = ExportViewing.serializer().descriptor.elementNames.toList()
        val rows = sample.getValue("viewings").jsonArray.map { it.jsonObject.keys.toList() }
        assertEquals(2, rows.size)
        for (row in rows) assertEquals(model.filter { it in row }, row)
        assertEquals(model, model.filter { key -> rows.any { key in it } })
    }

    @Test
    fun theSamplePreferenceHasExactlyTheModelsFields() =
        assertEquals(ExportPreference.serializer().descriptor.elementNames.toList(), keysOf("preferences"))

    /** `data.json`'s lists in the model's order: `criteria` and `preferences` after `brokers`, `questions`, then `viewings` last. */
    @Test
    fun theSamplesTopLevelKeysAreTheModelsInItsOrder() =
        assertEquals(BackupData.serializer().descriptor.elementNames.toList(), sample.keys.toList())

    /** The sample is the golden of a `/2` copy: its `brokers` list is what makes the format `/2` (README 1.1). */
    @Test
    fun theSampleIsTheFormatTheReadersAcceptAndTheOneAWriterPicks() {
        val format = sample.getValue("format").toString().trim('"')
        assertTrue(BackupFormat.accepts(format))
        val rooms = sample.getValue("houses").jsonArray.sumOf { h -> h.jsonObject["rooms"]?.jsonArray?.size ?: 0 }
        assertEquals(BackupFormat.idFor(sample.getValue("brokers").jsonArray.size, rooms), format)
        assertEquals(BackupFormat.ID_2, BackupFormat.idFor(0, rooms))
        // Slice 3a: questions alone, or a house with answers alone, make a `/2` file too.
        val questions = sample.getValue("questions").jsonArray.size
        val answers = sample.getValue("houses").jsonArray.sumOf { h -> h.jsonObject["answers"]?.jsonArray?.size ?: 0 }
        assertEquals(listOf(3, 2), listOf(questions, answers))
        assertEquals(BackupFormat.ID_2, BackupFormat.idFor(0, questions = questions))
        assertEquals(BackupFormat.ID_2, BackupFormat.idFor(0, answers = answers))
        // Slice 3b-1: a viewing alone makes a `/2` file too.
        assertEquals(BackupFormat.ID_2, BackupFormat.idFor(0, viewings = sample.getValue("viewings").jsonArray.size))
        assertEquals("doorprints-backup/2", format)
    }
}
