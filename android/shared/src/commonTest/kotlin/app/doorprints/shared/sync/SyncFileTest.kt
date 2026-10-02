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

package app.doorprints.shared.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The `sync/1` file (S4b-BL-130): the caps that the shared vectors cannot carry (a 16 MiB file, 20 001 rows), the
 * canonical writer, and the UTF-8 count. The problem codes are in [SyncVectorsTest].
 */
class SyncFileTest {
    private val a = "device-a01"
    private val now = 1_790_000_000_000L

    private fun file(rows: Map<SyncKind, List<SyncRow>>, seq: Long = 1) = SyncFile(a, seq, now, rows)

    private fun problem(text: String, device: String = a): SyncFileProblem =
        assertFailsWith<SyncFileException> { SyncFiles.parse(text, device) }.problem

    @Test
    fun theWriterIsCanonicalAndReadsBack() {
        val rows = mapOf(
            SyncKind.HOUSES to listOf(simRow("houses/h2", now, a, false), simRow("houses/h1", now - 1, a, true)),
            SyncKind.RECORDS to listOf(simRow("records/note/r1", now, a, false), simRow("records/area/r1", now, a, false)),
        )
        val text = SyncFiles.encode(file(rows, seq = 7))
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(listOf("format", "deviceId", "seq", "writtenAt", "houses", "visits", "records", "photos"), root.keys.toList())
        assertEquals(listOf("h1", "h2"), root.getValue("houses").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(listOf("area", "note"), root.getValue("records").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        val back = SyncFiles.parse(text, a)
        assertEquals(7L, back.seq)
        assertEquals(now, back.writtenAt)
        assertEquals(listOf("h1", "h2"), back.rows(SyncKind.HOUSES).map { it.key })
        assertEquals(SyncStamp(now - 1, a, true), back.rows(SyncKind.HOUSES)[0].stamp)
        assertEquals(text, SyncFiles.encode(back), "writing what was read gives the same text")
    }

    @Test
    fun theWriterRefusesWhatAReaderWouldRefuse() {
        val twice = mapOf(SyncKind.HOUSES to listOf(simRow("houses/h1", now, a, false), simRow("houses/h1", now + 1, a, false)))
        assertEquals(SyncFileProblem.DUPLICATE_ROW, assertFailsWith<SyncFileException> { SyncFiles.encode(file(twice)) }.problem)
        val many = mapOf(SyncKind.HOUSES to List(SyncKind.HOUSES.maxRows + 1) { simRow("houses/h$it", now, a, false) })
        assertEquals(SyncFileProblem.TOO_LARGE, assertFailsWith<SyncFileException> { SyncFiles.encode(file(many)) }.problem)
    }

    @Test
    fun aListOverItsCapIsRefused() {
        val row = """{"id":"h","updatedAt":"2026-09-21T14:13:20Z","deleted":false,"by":"$a"}"""
        val rows = List(SyncKind.HOUSES.maxRows + 1) { row.replace("\"h\"", "\"h$it\"") }.joinToString(",")
        val text = """{"format":"doorprints-sync/1","deviceId":"$a","seq":1,"writtenAt":"2026-09-21T14:13:20Z",""" +
            """"houses":[$rows],"visits":[],"records":[],"photos":[]}"""
        assertEquals(SyncFileProblem.TOO_LARGE, problem(text))
        val atCap = text.replace(",$row".replace("\"h\"", "\"h${SyncKind.HOUSES.maxRows}\""), "")
        assertEquals(SyncKind.HOUSES.maxRows, SyncFiles.parse(atCap, a).rows(SyncKind.HOUSES).size)
    }

    @Test
    fun allListsTogetherAreCapped() {
        val per = SyncFiles.MAX_ROWS / 3 + 1
        fun list(prefix: String) = JsonArray(List(per) { simRow("visits/$prefix$it", now, a, false).json })
        fun records() = JsonArray(List(per) { simRow("records/note/r$it", now, a, false).json })
        fun photos() = JsonArray(List(per) { simRow("photos/p$it", now, a, false).json })
        val text = JsonObject(
            mapOf(
                "format" to JsonPrimitive(SyncFiles.FORMAT), "deviceId" to JsonPrimitive(a), "seq" to JsonPrimitive(1),
                "writtenAt" to JsonPrimitive("2026-09-21T14:13:20Z"), "houses" to JsonArray(emptyList()),
                "visits" to list("v"), "records" to records(), "photos" to photos(),
            ),
        ).toString()
        assertEquals(SyncFileProblem.TOO_LARGE, problem(text))
    }

    @Test
    fun aFileOverSixteenMebibytesIsRefusedBeforeParsing() {
        val notes = "x".repeat(SyncFiles.MAX_BYTES)
        val text = """{"format":"doorprints-sync/1","deviceId":"$a","seq":1,"writtenAt":"2026-09-21T14:13:20Z",""" +
            """"houses":[{"id":"h1","notes":"$notes","updatedAt":"2026-09-21T14:13:20Z","deleted":false,"by":"$a"}],""" +
            """"visits":[],"records":[],"photos":[]}"""
        assertEquals(SyncFileProblem.TOO_LARGE, problem(text))
        assertEquals(SyncFileProblem.TOO_LARGE, problem("é".repeat(SyncFiles.MAX_BYTES / 2 + 1)), "counted in UTF-8 bytes")
    }

    @Test
    fun deepNestingIsAProblemNotACrash() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        val text = """{"format":"doorprints-sync/1","deviceId":"$a","seq":1,"writtenAt":"2026-09-21T14:13:20Z",""" +
            """"houses":[],"visits":[],"records":[],"photos":[],"x":$deep}"""
        // Refused before parsing, never a stack overflow (kotlinx's tree reader overflows on this).
        assertEquals(SyncFileProblem.TOO_LARGE, problem(text))
        assertEquals(1, SyncFiles.nestingDepth("{\"a\":\"[[[[\\\"[[\"}"), "brackets in strings, escaped quotes included, do not count")
        assertEquals(3, SyncFiles.nestingDepth("{\"a\":[{}],\"b\":[[]]}"))
    }

    @Test
    fun theUtf8CountMatchesTheEncoder() {
        val samples = listOf("", "abc", "é", "हिन्दी", "தமிழ்", "తెలుగు", "😀", "a😀b")
        for (s in samples) assertEquals(s.encodeToByteArray().size.toLong(), SyncFiles.utf8Length(s), s)
        // An unpaired surrogate counts as U+FFFD's 3 bytes, as the website's TextEncoder writes it.
        assertEquals(3L, SyncFiles.utf8Length("\uD800"))
        assertEquals(4L, SyncFiles.utf8Length("\uDC00x"))
        assertEquals(7L, SyncFiles.utf8Length("\uD800\uD800\uDC00"))
    }
}
