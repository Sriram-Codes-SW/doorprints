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

package app.doorprints.shared.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The seeded question bank against `docs/schemas/default-questions.json` (docs/11 5.5, slice 3a): [DefaultQuestions]
 * is that file, id for id, text for text in the four languages, as the web's `question.spec.ts` checks the same file.
 * Not in commonTest: it reads a repository file.
 */
class DefaultQuestionsFileTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val file = Json.parseToJsonElement(repoFile("docs/schemas/default-questions.json").readText()).jsonObject
    private val seed: List<JsonObject> = file.getValue("questions").jsonArray.map { it.jsonObject }

    @Test
    fun theEmbeddedDefaultsAreTheFileIdForIdAndTextForText() {
        assertEquals("doorprints-default-questions/1", file.getValue("format").jsonPrimitive.content)
        assertEquals(
            QuestionCategory.entries.map { it.name },
            file.getValue("categories").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(seed.map { it.getValue("id").jsonPrimitive.content }, DefaultQuestions.ALL.map { it.id })
        for ((json, d) in seed.zip(DefaultQuestions.ALL)) {
            assertEquals(d.id, json.getValue("category").jsonPrimitive.content, d.category.name)
            assertEquals(d.id, json.getValue("appliesTo").jsonPrimitive.content, d.appliesTo.name)
            assertEquals(d.id, json.getValue("defaultOn").jsonPrimitive.boolean, d.defaultOn)
            assertEquals(d.id, json.getValue("sort").jsonPrimitive.int, d.sort)
            val texts = json.getValue("text").jsonObject
            assertEquals(d.id, DefaultQuestions.LANGUAGES, texts.keys.toList())
            for (lang in DefaultQuestions.LANGUAGES) assertEquals("${d.id} $lang", texts.getValue(lang).jsonPrimitive.content, d.text[lang])
        }
    }

    // The school's seed (kinds PR 29, docs/03 section 18.23): docs/schemas/kinds/school-questions.json, a DRAFT for the owner.
    private val schoolFile = Json.parseToJsonElement(repoFile("docs/schemas/kinds/school-questions.json").readText()).jsonObject
    private val schoolSeed: List<JsonObject> = schoolFile.getValue("questions").jsonArray.map { it.jsonObject }

    @Test
    fun theSchoolsEmbeddedSeedIsItsFileIdForIdAndTextForText() {
        assertEquals("doorprints-kind-questions/1", schoolFile.getValue("format").jsonPrimitive.content)
        assertEquals(6, schoolSeed.size)
        assertEquals(schoolSeed.map { it.getValue("id").jsonPrimitive.content }, DefaultQuestions.SCHOOL.map { it.id })
        for ((json, d) in schoolSeed.zip(DefaultQuestions.SCHOOL)) {
            assertEquals(d.id, json.getValue("category").jsonPrimitive.content, d.category.name)
            assertEquals(d.id, json.getValue("appliesTo").jsonPrimitive.content, d.appliesTo.name)
            assertEquals(d.id, json.getValue("defaultOn").jsonPrimitive.boolean, d.defaultOn)
            assertEquals(d.id, json.getValue("sort").jsonPrimitive.int, d.sort)
            val texts = json.getValue("text").jsonObject
            assertEquals(d.id, DefaultQuestions.LANGUAGES, texts.keys.toList())
            for (lang in DefaultQuestions.LANGUAGES) assertEquals("${d.id} $lang", texts.getValue(lang).jsonPrimitive.content, d.text[lang])
        }
    }

    @Test
    fun theSchoolsCategoryHeadersAreItsFilesAndTheHouseKeepsTheStringsOfTheApp() {
        val labels = schoolFile.getValue("categoryLabels").jsonObject
        assertEquals(QuestionCategory.entries.map { it.name }.sorted(), labels.keys.sorted())
        for (c in QuestionCategory.entries) {
            val file = labels.getValue(c.name).jsonObject
            assertEquals(c.name, DefaultQuestions.LANGUAGES, file.keys.toList())
            for (lang in DefaultQuestions.LANGUAGES) assertEquals("${c.name} $lang", file.getValue(lang).jsonPrimitive.content, DefaultQuestions.categoryLabel("school", c, lang))
            // An unknown language reads English; the house has none (its headers are the app's strings); neither has a kind with no file.
            assertEquals(c.name, file.getValue("en").jsonPrimitive.content, DefaultQuestions.categoryLabel("school", c, "fr"))
            assertEquals(c.name, null, DefaultQuestions.categoryLabel("house", c, "en"))
            assertEquals(c.name, null, DefaultQuestions.categoryLabel("villa", c, "en"))
        }
        // The stored category ids of the school's questions are unchanged apart from the admission question, which a school words as Admission.
        assertEquals(
            listOf("MONEY", "LEGAL", "RULES", "BUILDING", "WATER_POWER", "OTHER"),
            DefaultQuestions.SCHOOL.map { it.category.name },
        )
        for (q in DefaultQuestions.SCHOOL) assertEquals(q.id, true, q.category.name in labels.keys)
    }

    @Test
    fun theTwoBanksNeverMeetAndTheKindOfAQuestionIsReadFromItsId() {
        val house = DefaultQuestions.ALL.map { it.id }.toSet()
        for (d in DefaultQuestions.SCHOOL) {
            assertEquals(d.id, false, d.id in house)
            assertEquals(d.id, "school", Question.kindOf(d.id))
            assertEquals(d.id, QuestionScope.BOTH, d.appliesTo)
            assertEquals(d.id, true, DefaultQuestions.byId(d.id) != null)
        }
        assertEquals(true, DefaultQuestions.ALL.all { Question.kindOf(it.id) == "house" })
        assertEquals("house", Question.kindOf("q_0123abcd"))
        assertEquals("school", Question.kindOf("q_school_0123abcd"))
        assertEquals(true, Question.isCustomId("q_school_0123abcd"))
        assertEquals(false, Question.isCustomId("q_school_0123abc"))
        assertEquals(false, Question.isCustomId("qd_school_fees"))
        assertEquals(DefaultQuestions.ALL, DefaultQuestions.of("house"))
        assertEquals(DefaultQuestions.SCHOOL, DefaultQuestions.of("school"))
        assertEquals(emptyList<DefaultQuestions.Default>(), DefaultQuestions.of("villa"))
        assertEquals(true, Regex("q_school_[0-9a-f]{8}").matches(Question.newCustomId({ false }, kind = "school")))
        assertEquals(true, Regex("q_[0-9a-f]{8}").matches(Question.newCustomId({ false })))
    }

    @Test
    fun theScopesOfAKindsQuestionsAreItsFilesAndASchoolHasNeitherRentNorSale() {
        for (kind in listOf("house", "school")) {
            val file = Json.parseToJsonElement(repoFile("docs/schemas/kinds/$kind.json").readText()).jsonObject
            val scopes = file.getValue("questions").jsonObject.getValue("scopes").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(kind, scopes.sorted(), QuestionScope.of(kind).map { it.name }.sorted())
        }
        assertEquals(listOf(QuestionScope.BOTH), QuestionScope.of("school"))
        assertEquals("a kind with no entry has them all", QuestionScope.entries.toList(), QuestionScope.of("villa"))
    }
}
