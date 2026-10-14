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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The kinds a backup's `search` object names are a hint for a phone that never chose (kinds PR C, S4b-BL-274; docs/03
 * §18.14), read from the same `read` cases of `docs/schemas/kinds/search-kind-vectors.json` that every reader reads past:
 * only well-formed kinds with a default are a hint; the house's `{"kind":"house"}`, a first-version `{"kind":...}`, a wrong
 * type and an absent object name none. Not in commonTest: it reads repository files.
 */
class KindSearchHintTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val vectors = Json.parseToJsonElement(repoFile("docs/schemas/kinds/search-kind-vectors.json").readText()).jsonObject
    private fun JsonObject.text(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    @Test
    fun theKindsAFileNamesAreAHintOnlyWhenTheSearchObjectNamesWellFormedKinds() {
        val sample = Json.parseToJsonElement(repoFile("docs/schemas/backup-sample.json").readText()).jsonObject
        val taken = mapOf(
            "two kinds and a default" to (listOf("house", "school") to "school"),
            // A kind this build does not have is still a well-formed id: it is stored and read past, as on the website.
            "a kind this build does not have" to (listOf("clinic") to "clinic"),
        )
        for (c in vectors.getValue("read").jsonArray.map { it.jsonObject }) {
            val fields = LinkedHashMap(sample)
            if (c["absent"] == null) fields["search"] = c.getValue("search") else fields.remove("search")
            assertEquals(c.text("name"), taken[c.text("name")], BackupWire.spacesOf(JsonObject(fields)))
        }
    }
}
