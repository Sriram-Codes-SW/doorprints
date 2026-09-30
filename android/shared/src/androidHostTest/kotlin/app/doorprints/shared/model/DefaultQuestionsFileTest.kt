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
}
