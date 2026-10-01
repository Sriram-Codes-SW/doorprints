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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The move-in defaults against `docs/schemas/default-movein.json` (docs/11 5.24, slice 5): [MoveIn.DEFAULTS] is that
 * file, id for id, sort for sort and text for text in the four languages, as the web's test checks the same file. Not
 * in commonTest: it reads a repository file.
 */
class DefaultMoveInFileTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val file = Json.parseToJsonElement(repoFile("docs/schemas/default-movein.json").readText()).jsonObject
    private val items: List<JsonObject> = file.getValue("items").jsonArray.map { it.jsonObject }

    @Test
    fun m4_theEmbeddedDefaultsAreTheFileIdForIdAndTextForText() {
        assertEquals("doorprints-default-movein/1", file.getValue("format").jsonPrimitive.content)
        assertEquals(6, items.size)
        assertEquals(items.map { it.getValue("id").jsonPrimitive.content }, MoveIn.DEFAULTS.map { it.id })
        for ((json, d) in items.zip(MoveIn.DEFAULTS)) {
            assertEquals(d.id, json.getValue("sort").jsonPrimitive.int, d.sort)
            val texts = json.getValue("text").jsonObject
            assertEquals(d.id, MoveIn.LANGUAGES, texts.keys.toList())
            for (lang in MoveIn.LANGUAGES) assertEquals("${d.id} $lang", texts.getValue(lang).jsonPrimitive.content, d.text[lang])
        }
        // *Start moving in* adds exactly the file's items, in its order, in the language asked for.
        assertEquals(
            items.map { it.getValue("text").jsonObject.getValue("te").jsonPrimitive.content },
            MoveIn.addDefaults(null, "te").map { it.text },
        )
    }
}
