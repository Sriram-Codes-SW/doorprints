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

package app.doorprints.ui

import app.doorprints.shared.model.Checklist
import app.doorprints.shared.model.HouseStatus
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.allStringResources
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The safety test of kinds PR 3, written before anything moved (S4b-BL-207, docs/06 TC-U-209): every Android key of
 * `docs/schemas/kinds/kind-label-vectors.json` is a Compose string of `:ui`, and the status and checklist labels the
 * screens read ([labelResource], [ChecklistResources]) are the very resources the vectors pin. `:shared`'s
 * `KindLabelsVectorsTest` checks the words in the four languages; the website's `kind-labels.spec.ts` its side. Not in
 * commonTest: it reads a repository file.
 */
class KindLabelsScreensTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val cases: List<JsonObject> = Json.parseToJsonElement(repoFile("docs/schemas/kinds/kind-label-vectors.json").readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        .filter { it["resolved"] !is JsonNull }

    private fun androidKey(ref: String): String =
        cases.single { it["ref"]?.jsonPrimitive?.content == ref && "cadence" !in it }.getValue("android").jsonPrimitive.content

    @Test
    fun everyPinnedAndroidKeyIsAComposeString() {
        for (case in cases) {
            val key = case.getValue("android")
            if (key is JsonNull) continue
            assertNotNull(Res.allStringResources[key.jsonPrimitive.content], "${case["name"]}: $key")
        }
    }

    @Test
    fun theStatusAndChecklistLabelsTheScreensReadAreThePinnedResources() {
        for (s in HouseStatus.entries) assertEquals(Res.allStringResources[androidKey("status.${s.name}")], s.labelResource, s.name)
        for (key in Checklist.keys) assertEquals(Res.allStringResources[androidKey("check.$key")], ChecklistResources.items[key], key)
        assertEquals(Checklist.keys.size, cases.count { it.getValue("ref").jsonPrimitive.content.startsWith("check.") })
    }
}
