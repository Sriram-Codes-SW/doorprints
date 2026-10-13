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

import app.doorprints.data.RecordEntity
import app.doorprints.data.ViewingStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared viewing payload vectors (`docs/schemas/viewing-payload-vectors.json`, S4b-BL-247) on the phone and the
 * iPhone (`:shared`): a stored row names its candidate as `candidateId`, one stored or synced before as `houseId`, both
 * keys a bad row that is skipped; what the phone writes back says `candidateId`, byte for byte as the website and the
 * server write it. Not in commonTest: it reads a repository file. Twins: the website's `viewing-payload-vectors.spec.ts`,
 * the server's `ViewingPayloadVectorsTest`. docs/06 TC-U-208.
 */
class ViewingPayloadVectorsTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    @Test
    fun everyStoredPayloadNamesTheVectorsCandidateAndIsWrittenBackWithCandidateId() {
        val vectors = Json.parseToJsonElement(repoFile("docs/schemas/viewing-payload-vectors.json").readText()).jsonObject
        assertEquals("doorprints-viewing-payload-vectors/1", vectors.getValue("format").jsonPrimitive.content)
        var read = 0
        var old = 0
        for (element in vectors.getValue("reads").jsonArray) {
            val c = element.jsonObject
            val name = c.getValue("name").jsonPrimitive.content
            val payload = c.getValue("payload") as JsonObject
            val row = RecordEntity(ViewingType.name, c.getValue("id").jsonPrimitive.content, payload.toString(), 1L)
            val viewing = ViewingStore.of(row)
            val expected = c.getValue("candidateId")
            if (expected is JsonNull) {
                assertNull(name, viewing)
                continue
            }
            assertNotNull(name, viewing)
            assertEquals(name, expected.jsonPrimitive.content, viewing!!.houseId)
            val written = ViewingType.encode(viewing)
            assertEquals(name, c.getValue("written").jsonPrimitive.content, written)
            assertFalse(name, "\"houseId\"" in written)
            read++
            if (ViewingPayloadSerializer.OLD_KEY in payload) old++
        }
        assertTrue("cases read", read >= 4)
        assertTrue("cases of the key before S4b-BL-247 read", old >= 2)
    }
}
