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
package app.doorprints.drive.photo

import app.doorprints.shared.api.IsoTime
import app.doorprints.shared.model.PhotoMeta
import app.doorprints.shared.sync.SyncKind
import app.doorprints.shared.sync.SyncRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The rules of getting a restored candidate's photos back from Google Drive (S4b-BL-293) against the shared vectors
 * `docs/schemas/photo-restore-vectors.json`, the file the website's `photo-restore-rules.spec.ts` reads. The vector file is
 * never edited to make this pass. Not in commonTest: it reads repository files.
 */
class PhotoRestoreVectorsTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val vectors = Json.parseToJsonElement(repoFile("docs/schemas/photo-restore-vectors.json").readText()).jsonObject
    private fun ms(iso: String) = IsoTime.parseMillis(iso)
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun theWindowAndTheToleranceAreTheVectors() {
        assertEquals("doorprints-photo-restore-vectors/1", vectors.text("format"))
        assertEquals(vectors.text("windowDays")!!.toInt(), PhotoRestoreRules.RESTORE_WINDOW_DAYS)
        assertEquals(vectors.text("withCandidateMs")!!.toLong(), PhotoRestoreRules.WITH_CANDIDATE_MS)
    }

    @Test
    fun aPhotoDeletedAMinuteBeforeItsCandidateOrLaterWasDeletedWithIt() {
        assertEquals(true, PhotoRestoreRules.deletedWithCandidate(1_000_000 - 60_000, 1_000_000))
        assertEquals(false, PhotoRestoreRules.deletedWithCandidate(1_000_000 - 60_001, 1_000_000))
        assertEquals(true, PhotoRestoreRules.deletedWithCandidate(1_000_001, 1_000_000))
    }

    @Test
    fun planCases() {
        val cases = vectors.getValue("plan").jsonArray.map { it.jsonObject }
        check(cases.isNotEmpty())
        for (c in cases) {
            val local = c.getValue("local").jsonArray.map { it.jsonObject }.map { GonePhoto(it.text("id")!!, ms(it.text("deletedAt")!!), "image/jpeg", null, PhotoMeta()) }
            val file = c.getValue("file").jsonArray.map { it.jsonObject }.map { r ->
                SyncRow.of(
                    SyncKind.PHOTOS,
                    buildJsonObject {
                        put("id", r.text("id")!!)
                        if (r["candidateId"] != null) put("candidateId", r.text("candidateId")!!) else put("houseId", r.text("houseId")!!)
                        put("contentType", "image/jpeg")
                        put("updatedAt", r.text("deletedAt")!!)
                        put("deleted", r.text("deleted")!!.toBoolean())
                        put("by", "device-aaaa-0001")
                        r.text("driveFileId")?.let { put("driveFileId", it); put("sha256", r.text("sha256")!!) }
                    },
                )
            }
            val refs = c.getValue("refs").jsonObject.mapValues { (_, v) -> PhotoRef(v.jsonObject.text("driveFileId")!!, v.jsonObject.text("sha256")!!) }
            val plan = PhotoRestoreRules.planRestore(c.text("houseId")!!, ms(c.text("houseDeletedAt")!!), ms(c.text("now")!!), local, file, refs)
            val expected = c.getValue("expect").jsonArray.map { it.jsonObject }
            assertEquals(c.text("name"), expected.map { it.text("id") to it.text("action") }, plan.map { it.photo.id to it.action.name })
            for (e in expected) {
                val want = e.text("driveFileId") ?: continue
                assertEquals(c.text("name"), want, plan.first { it.photo.id == e.text("id") }.photo.ref?.driveFileId)
            }
        }
    }

    @Test
    fun summaryCases() {
        val cases = vectors.getValue("summary").jsonArray.map { it.jsonObject }
        check(cases.isNotEmpty())
        for (c in cases) {
            val failed = c.getValue("failed").jsonArray.map { PhotoRestoreReason.valueOf(it.jsonPrimitive.content) }
            val got = PhotoRestoreRules.summarize(c.text("noted")!!.toInt(), c.text("back")!!.toInt(), failed, RestoreConnection.valueOf(c.text("connection")!!))
            val want = c.getValue("expect").jsonObject
            assertEquals(c.text("name"), want.text("total")!!.toInt(), got.total)
            assertEquals(c.text("name"), want.text("restored")!!.toInt(), got.restored)
            assertEquals(c.text("name"), want.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }, got.reasons.map { it.name })
        }
    }
}
