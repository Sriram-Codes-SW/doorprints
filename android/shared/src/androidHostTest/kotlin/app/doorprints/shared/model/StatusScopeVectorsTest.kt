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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * TAKEN and *Close this hunt* act within the chosen candidate's own kind (S4b-BL-306; docs/02 T-D15), against
 * `docs/schemas/kinds/status-scope-vectors.json`, the file the website's `house-status.spec.ts` reads. Not in commonTest:
 * it reads a repository file.
 */
class StatusScopeVectorsTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val vectors = Json.parseToJsonElement(repoFile("docs/schemas/kinds/status-scope-vectors.json").readText()).jsonObject

    private fun houses(v: JsonObject) = v.getValue("houses").jsonArray.map { it.jsonObject }.map {
        StatusHouse(it.getValue("id").jsonPrimitive.content, HouseStatus.valueOf(it.getValue("status").jsonPrimitive.content), it["kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun theFormatIsTheOneTheSchemasReadmeNames() {
        assertEquals("doorprints-status-scope-vectors/1", vectors.getValue("format").jsonPrimitive.content)
    }

    @Test
    fun choosingTakenReturnsTheTakenCandidateOfTheSameKindOnly() {
        for (v in vectors.getValue("choose").jsonArray.map { it.jsonObject }) {
            val after = HouseStatusRules.choose(houses(v), v.getValue("id").jsonPrimitive.content, HouseStatus.valueOf(v.getValue("status").jsonPrimitive.content))
            assertEquals(v.getValue("name").jsonPrimitive.content, v.getValue("after").jsonArray.map { it.jsonPrimitive.content }, after.map { it.status.name })
        }
    }

    @Test
    fun closeThisHuntMarksTheOpenCandidatesOfTheTakenOnesKindOnly() {
        for (v in vectors.getValue("close").jsonArray.map { it.jsonObject }) {
            assertEquals(
                v.getValue("name").jsonPrimitive.content,
                v.getValue("targets").jsonArray.map { it.jsonPrimitive.content },
                HouseStatusRules.closeTargets(houses(v), v.getValue("takenId").jsonPrimitive.content),
            )
        }
    }
}
