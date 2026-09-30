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

package app.doorprints.shared.records

import app.doorprints.data.RecordEntity
import app.doorprints.data.toDto
import app.doorprints.data.toEntity
import app.doorprints.shared.api.RecordDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The record envelope's rules (docs/11 5.30 item 2, ADR-28): the type and id rules, the payload cap, a typed round
 * trip through the row and the wire, and a payload this app cannot read decoding to null rather than throwing. No
 * record type ships yet (slice 1 adds brokers), so a test-only one stands in.
 */
class RecordRulesTest {

    /** A test-only record; `note` has a default so a payload without it still decodes. */
    @Serializable
    private data class Pin(val name: String, val lat: Double, val note: String? = null)

    private val pins = RecordType("pin", Pin.serializer())

    @Test
    fun typeNamesAreShortLowerCamelWords() {
        for (ok in listOf("a", "broker", "photoMeta", "moveIn", "x1", "a".repeat(40))) assertTrue(RecordRules.isValidType(ok), ok)
        for (bad in listOf("", "Broker", "1a", "a-b", "a_b", "a.b", "a b", "a/b", "a".repeat(41))) {
            assertFalse(RecordRules.isValidType(bad), bad)
            assertFailsWith<IllegalArgumentException> { RecordType(bad, Pin.serializer()) }
        }
    }

    @Test
    fun idsAreSafeInAPath() {
        for (ok in listOf("a", "b1", "550e8400-e29b-41d4-a716-446655440000", "a.b_c-D", "a".repeat(64))) {
            assertTrue(RecordRules.isValidId(ok), ok)
        }
        for (bad in listOf("", " ", "a b", "a/b", ".", "..", "a\\b", "a".repeat(65), "ü")) assertFalse(RecordRules.isValidId(bad), bad)
    }

    @Test
    fun thePayloadCapCountsBytesNotCharacters() {
        assertTrue(RecordRules.fitsPayload("a".repeat(RecordRules.MAX_PAYLOAD_BYTES)))
        assertFalse(RecordRules.fitsPayload("a".repeat(RecordRules.MAX_PAYLOAD_BYTES + 1)))
        // A Tamil letter is three bytes of UTF-8.
        assertFalse(RecordRules.fitsPayload("த".repeat(RecordRules.MAX_PAYLOAD_BYTES / 3 + 1)))
        assertEquals(65_536, RecordRules.MAX_PAYLOAD_BYTES)
        assertEquals(5_000, RecordRules.MAX_ROWS_PER_TYPE)
    }

    @Test
    fun aValueRoundTripsThroughTheRowAndTheWire() {
        val pin = Pin(name = "Home", lat = 12.97)
        val text = pins.encode(pin)
        // Defaults written, nulls left out, no pretty-printing: the same rules as the backup's JSON.
        assertEquals("{\"name\":\"Home\",\"lat\":12.97}", text)
        val row = RecordEntity(type = "pin", id = "p1", payload = text, updatedAt = 1_790_072_130_120)
        assertEquals(pin, row.decode(pins))

        val dto = row.toDto()
        assertEquals("pin", dto.type)
        assertEquals("p1", dto.id)
        assertEquals(JsonPrimitive("Home"), dto.payload["name"])
        assertEquals("2026-09-22T10:15:30.120Z", dto.updatedAt)
        assertFalse(dto.deleted)
        // Pulled rows are clean; everything else survives.
        assertEquals(row.copy(dirty = false), dto.toEntity())
    }

    @Test
    fun aPayloadThisAppCannotReadDecodesToNull() {
        // A newer app's shape (a required field renamed), a hand-edited row, not JSON at all: none throws.
        for (payload in listOf("{\"label\":\"Home\",\"lat\":1}", "{\"name\":\"Home\",\"lat\":\"north\"}", "[]", "", "not json")) {
            assertNull(RecordEntity(type = "pin", id = "p1", payload = payload, updatedAt = 1).decode(pins), payload)
        }
        // An unknown key from a newer app is not a reason to skip the row.
        assertNotNull(RecordEntity(type = "pin", id = "p1", payload = "{\"name\":\"Home\",\"lat\":1,\"colour\":\"red\"}", updatedAt = 1).decode(pins))
    }

    @Test
    fun aWireRowThisPhoneCannotUseBecomesNoRowAndATombstoneKeepsAnEmptyObject() {
        val fine = RecordDto(type = "pin", id = "p1", payload = buildJsonObject { put("name", JsonPrimitive("x")) }, updatedAt = "2026-09-22T10:15:30Z")
        assertNotNull(fine.toEntity())
        assertNull(fine.copy(type = "Pin").toEntity())
        assertNull(fine.copy(id = "a/b").toEntity())
        val big = buildJsonObject { put("name", JsonPrimitive("x".repeat(RecordRules.MAX_PAYLOAD_BYTES))) }
        assertNull(fine.copy(payload = big).toEntity())
        // A missing server time becomes now, as for houses.
        assertTrue(fine.copy(updatedAt = null).toEntity()!!.updatedAt > 0)

        val tombstone = RecordEntity(type = "pin", id = "p1", payload = "{}", updatedAt = 2, deleted = true)
        assertEquals(JsonObject(emptyMap()), tombstone.toDto().payload)
        assertTrue(tombstone.toDto().deleted)
        assertEquals("{}", RecordDto(type = "pin", id = "p1", deleted = true).toEntity()!!.payload)
    }
}
